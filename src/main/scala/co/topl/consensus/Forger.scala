package co.topl.consensus

import akka.actor._
import akka.util.Timeout
import akka.pattern.ask
import co.topl.attestation.keyManagement.KeyManager.ForgerView
import co.topl.attestation.keyManagement.KeyManager.ReceivableMessages.{
  GetForgerView,
  GetPublicKeyFromAddress,
  SignMessageWithAddress
}
import co.topl.attestation.{Address, PublicKeyPropositionCurve25519, SignatureCurve25519}
import co.topl.consensus.Forger.{ChainParams, PickTransactionsResult}
import co.topl.consensus.genesis.{HelGenesis, PrivateGenesis, ToplnetGenesis, ValhallaGenesis}
import co.topl.modifier.ModifierId
import co.topl.modifier.block.Block
import co.topl.modifier.box.{ArbitBox, SimpleValue}
import co.topl.modifier.transaction.{ArbitTransfer, PolyTransfer, Transaction}
import co.topl.nodeView.CurrentView
import co.topl.nodeView.NodeViewHolder.ReceivableMessages.{
  EliminateTransactions,
  GetDataFromCurrentView,
  LocallyGeneratedModifier
}
import co.topl.nodeView.history.History
import co.topl.nodeView.mempool.MemPool
import co.topl.nodeView.state.State
import co.topl.settings.{AppContext, AppSettings, NodeViewReady}
import co.topl.utils.NetworkType._
import co.topl.utils.TimeProvider.Time
import co.topl.utils.{Int128, Logging, TimeProvider}

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

/** Forger takes care of attempting to create new blocks using the wallet provided in the NodeView
  * Must be singleton
  */
class Forger(settings: AppSettings, appContext: AppContext, keyManager: ActorRef)(implicit
  ec:                  ExecutionContext,
  np:                  NetworkPrefix
) extends Actor
    with Logging {

  //type HR = HistoryReader[Block, BifrostSyncInfo]
  type TX = Transaction.TX

  // Import the types of messages this actor RECEIVES
  import Forger.ReceivableMessages._

  // the nodeViewHolder actor ref for retrieving the current state
  private var nodeViewHolderRef: Option[ActorRef] = None

  // a timestamp updated on each forging attempt
  private var forgeTime: Time = appContext.timeProvider.time

  override def preStart(): Unit = {
    // determine the set of applicable protocol rules for this software version
    protocolMngr = ProtocolVersioner(settings.application.version, settings.forging.protocolVersions)

    //register for application initialization message
    context.system.eventStream.subscribe(self, classOf[NodeViewReady])
    context.system.eventStream.subscribe(self, GenerateGenesis.getClass)
  }

  ////////////////////////////////////////////////////////////////////////////////////
  ////////////////////////////// ACTOR MESSAGE HANDLING //////////////////////////////

  // ----------- CONTEXT
  override def receive: Receive =
    initialization orElse
    nonsense

  private def readyToForge: Receive =
    readyHandlers orElse
    nonsense

  private def activeForging: Receive =
    activeHandlers orElse
    nonsense

  // ----------- MESSAGE PROCESSING FUNCTIONS
  private def initialization: Receive = {
    case GenerateGenesis => generateGenesis
    case NodeViewReady(nvhRef: ActorRef) =>
      log.info(s"${Console.YELLOW}Forger transitioning to the operational state${Console.RESET}")
      nodeViewHolderRef = Some(nvhRef)
      context become readyToForge
  }

  private def readyHandlers: Receive = {
    case StartForging =>
      log.info("Received a START signal, forging will commence shortly.")
      scheduleForgingAttempt() // schedule the next forging attempt
      context become activeForging

    case StopForging =>
      log.warn(s"Received a STOP signal while not forging. Signal ignored")
  }

  private def activeHandlers: Receive = {
    case StartForging =>
      log.warn(s"Forger: Received a START signal while forging. Signal ignored")

    case StopForging =>
      log.info(s"Forger: Received a stop signal. Forging will terminate after this trial")
      context become readyToForge

    case CurrentView(history: History, state: State, mempool: MemPool) =>
      updateForgeTime() // update the forge timestamp
      tryForging(history, state, mempool) // initiate forging attempt
      scheduleForgingAttempt() // schedule the next forging attempt
  }

  private def nonsense: Receive = { case nonsense: Any =>
    log.warn(s"Got unexpected input $nonsense from ${sender()}")
  }

  ////////////////////////////////////////////////////////////////////////////////////
  //////////////////////////////// METHOD DEFINITIONS ////////////////////////////////
  /** Updates the forging actors timestamp */
  private def updateForgeTime(): Unit = forgeTime = appContext.timeProvider.time

  /** Schedule a forging attempt */
  private def scheduleForgingAttempt(): Unit =
    nodeViewHolderRef match {
      case Some(nvh: ActorRef) =>
        context.system.scheduler.scheduleOnce(settings.forging.blockGenerationDelay)(nvh ! GetDataFromCurrentView)

      case _ =>
        log.warn("No ledger actor found. Stopping forging attempts")
        self ! StopForging
    }

  /** Return the correct genesis parameters for the chosen network.
    * NOTE: the default private network is set in AppContext so the fall-through should result in an error.
    */
  private def generateGenesis(implicit timeout: Timeout = 10 seconds): Unit = {
    var blockResult = (keyManager ? GetForgerView)
      .mapTo[ForgerView]
      .map { forgerView =>
        (appContext.networkType match {
          case Mainnet         => ToplnetGenesis.getGenesisBlock
          case ValhallaTestnet => ValhallaGenesis.getGenesisBlock
          case HelTestnet      => HelGenesis.getGenesisBlock
          case LocalTestnet    => PrivateGenesis(forgerView.addresses, settings).getGenesisBlock
          case PrivateTestnet  => PrivateGenesis(forgerView.addresses, settings).getGenesisBlock
          case _               => throw new Error("Undefined network type.")
        }).map { case (block: Block, ChainParams(totalStake, initDifficulty)) =>
          maxStake = totalStake
          difficulty = initDifficulty
          height = 0

          block
        }
      }

    sender() ! Await.result(blockResult, timeout.duration)
  }

  /** Primary method for attempting to forge a new block and publish it to the network
    *
    * @param history history instance for gathering chain parameters
    * @param state   state instance for semantic validity tests of transactions
    * @param memPool mempool instance for picking transactions to include in the block if created
    */
  private def tryForging(history: History, state: State, memPool: MemPool)(implicit
    timeout:                      Timeout = settings.forging.blockGenerationDelay
  ): Unit =
    try {
      (keyManager ? GetForgerView)
        .mapTo[ForgerView]
        .onComplete({
          case Success(view)  => forge(history, state, memPool, view)
          case Failure(error) => throw error
        })
    } catch {
      case ex: Throwable =>
        log.warn(s"Disabling forging due to exception: $ex. Resolve forging error and try forging again.")
        self ! StopForging
    }

  private def publicKeyFromAddress(
    address:          Address
  )(implicit timeout: Timeout = settings.forging.blockGenerationDelay): Try[PublicKeyPropositionCurve25519] = {
    val getPublicKey = (keyManager ? GetPublicKeyFromAddress(address)).mapTo[Try[PublicKeyPropositionCurve25519]]
    Await.result(getPublicKey, settings.forging.blockGenerationDelay)
  }

  private def signWithAddress(address: Address)(
    message:                           Array[Byte]
  )(implicit timeout:                  Timeout = settings.forging.blockGenerationDelay): Try[SignatureCurve25519] = {
    val signFunction = (keyManager ? SignMessageWithAddress(address, message)).mapTo[Try[SignatureCurve25519]]
    Await.result(signFunction, settings.forging.blockGenerationDelay)
  }

  private def forge(
    history:              History,
    state:                State,
    memPool:              MemPool,
    forgerView:           ForgerView
  ): Unit = {
    log.debug(
      s"${Console.MAGENTA}Attempting to forge with settings ${protocolMngr.current(history.height)} " +
      s"and from addresses: ${forgerView.addresses}${Console.RESET}"
    )

    log.info(
      s"${Console.CYAN}Trying to generate a new block on top of ${history.bestBlock.id}. Parent has " +
      s"height ${history.height} and difficulty ${history.difficulty} ${Console.RESET}"
    )

    val rewardAddr = forgerView.rewardAddr.getOrElse(throw new Error("No rewards address specified"))

    // get the set of boxes to use for testing
    val boxes = getArbitBoxes(state, forgerView.addresses) match {
      case Success(bx) => bx
      case Failure(ex) => throw ex
    }

    log.debug(s"Trying to generate block from total stake ${boxes.map(_.value.quantity).foldLeft[Int128](0)(_ + _)}")
    require(
      boxes.map(_.value.quantity).foldLeft[Int128](0)(_ + _) > 0,
      "No Arbits could be found to stake with, exiting attempt"
    )

    // create the coinbase reward transaction
    val arbitReward = createArbitReward(rewardAddr, history.bestBlock.id) match {
      case Success(cb) => cb
      case Failure(ex) => throw ex
    }

    // pick the transactions from the mempool for inclusion in the block (if successful)
    val transactions = pickTransactions(memPool, state, history.height) match {
      case Success(res) =>
        if (res.toEliminate.nonEmpty) nodeViewHolderRef.foreach(_ ! EliminateTransactions(res.toEliminate.map(_.id)))
        res.toApply

      case Failure(ex) => throw ex
    }

    // create the unsigned fee reward transaction
    val polyReward =
      createPolyReward(transactions.map(_.fee).foldLeft[Int128](0)(_ + _), rewardAddr, history.bestBlock.id) match {
        case Success(tx) => tx
        case Failure(ex) => throw ex
      }

    // retrieve the latest TWO block times for updating the difficulty if we forge a new blow
    val prevTimes = history.getTimestampsFrom(history.bestBlock, nxtBlockNum)

    // check forging eligibility
    leaderElection(
      history.bestBlock,
      prevTimes,
      boxes,
      Seq(arbitReward, polyReward),
      transactions
    ) match {
      case Some(block) =>
        log.debug(s"Locally generated block: $block")
        context.system.eventStream.publish(LocallyGeneratedModifier[Block](block))

      case _ => log.debug(s"Failed to generate block")
    }
  }

  /** Get the set of Arbit boxes owned by all unlocked keys in the key ring
    *
    * @param state state instance used to lookup the balance for all unlocked keys
    * @return a set of arbit boxes to use for testing leadership eligibility
    */
  private def getArbitBoxes(state: State, addresses: Set[Address]): Try[Seq[ArbitBox]] = Try {
    if (addresses.nonEmpty) {
      addresses.flatMap {
        state
          .getTokenBoxes(_)
          .getOrElse(Seq())
          .collect { case box: ArbitBox => box }
      }.toSeq
    } else {
      throw new Error("No boxes available for forging!")
    }
  }

  /** Attempt to create an unsigned coinbase transaction that will distribute the block reward
    * if forging is successful
    *
    * @return an unsigned coinbase transaction
    */
  private def createArbitReward(
    rewardAdr: Address,
    parentId:  ModifierId
  ): Try[ArbitTransfer[PublicKeyPropositionCurve25519]] =
    Try {
      ArbitTransfer(
        IndexedSeq(),
        IndexedSeq((rewardAdr, SimpleValue(inflation))),
        Map[PublicKeyPropositionCurve25519, SignatureCurve25519](),
        Int128(0),
        forgeTime,
        Some(parentId.toString + "_"), // the underscore is for letting miners add their own message in the future
        minting = true
      )
    }

  private def createPolyReward(
    amount:    Int128,
    rewardAdr: Address,
    parentId:  ModifierId
  ): Try[PolyTransfer[PublicKeyPropositionCurve25519]] =
    Try {
      PolyTransfer(
        IndexedSeq(),
        IndexedSeq((rewardAdr, SimpleValue(amount))),
        Map[PublicKeyPropositionCurve25519, SignatureCurve25519](),
        0,
        forgeTime,
        Some(parentId.toString + "_"), // the underscore is for letting miners add their own message in the future
        minting = true
      )
    }

  /** Pick a set of transactions from the mempool that result in a valid state when applied to the current state
    *
    * @param memPool the set of pending transactions
    * @param state   state to use for semantic validity checking
    * @return a sequence of valid transactions
    */
  private def pickTransactions(memPool: MemPool, state: State, chainHeight: Long): Try[PickTransactionsResult] = Try {

    memPool
      .take[Int128](numTxInBlock(chainHeight))(-_.tx.fee) // returns a sequence of transactions ordered by their fee
      .filter(
        _.tx.fee > settings.forging.minTransactionFee
      ) // default strategy ignores zero fee transactions in mempool
      .foldLeft(PickTransactionsResult(Seq(), Seq())) { case (txAcc, utx) =>
        // ensure that each transaction opens a unique box by checking that this transaction
        // doesn't open a box already being opened by a previously included transaction
        val boxAlreadyUsed = utx.tx.boxIdsToOpen.exists(id => txAcc.toApply.flatMap(_.boxIdsToOpen).contains(id))

        // if any newly created box matches a box already in the UTXO set in state, remove the transaction
        val boxAlreadyExists = utx.tx.newBoxes.exists(b => state.getBox(b.id).isDefined)

        (boxAlreadyUsed, boxAlreadyExists) match {
          case (false, false) =>
            state.semanticValidate(utx.tx) match {
              case Success(_) => PickTransactionsResult(txAcc.toApply :+ utx.tx, txAcc.toEliminate)
              case Failure(ex) =>
                log.debug(
                  s"${Console.RED}Transaction ${utx.tx.id} failed semantic validation. " +
                  s"Transaction will be removed.${Console.RESET} Failure: $ex"
                )
                PickTransactionsResult(txAcc.toApply, txAcc.toEliminate :+ utx.tx)
            }

          case (_, true) =>
            log.debug(
              s"${Console.RED}Transaction ${utx.tx.id} was rejected from the forger transaction queue" +
              s" because a newly created box already exists in state. The transaction will be removed."
            )
            PickTransactionsResult(txAcc.toApply, txAcc.toEliminate :+ utx.tx)

          case (true, _) =>
            log.debug(
              s"${Console.RED}Transaction ${utx.tx.id} was rejected from forger transaction queue" +
              s" because a box was used already in a previous transaction. The transaction will be removed."
            )
            PickTransactionsResult(txAcc.toApply, txAcc.toEliminate :+ utx.tx)
        }
      }
  }

  /** Performs the leader election procedure and returns a block if successful
    *
    * @param parent       block to forge on top of
    * @param boxes        set of Arbit boxes to attempt to forge with
    * @param txsToInclude sequence of transactions for inclusion in the block body
    * @return a block if the leader election is successful (none if test failed)
    */
  private def leaderElection(
    parent:       Block,
    prevTimes:    Vector[TimeProvider.Time],
    boxes:        Seq[ArbitBox],
    rawRewards:   Seq[TX],
    txsToInclude: Seq[TX]
  ): Option[Block] = {

    val target = calcAdjustedTarget(parent, parent.height, parent.difficulty, forgeTime)

    // test procedure to determine eligibility
    val successfulHits = boxes
      .map { box =>
        (box, calcHit(parent)(box))
      }
      .filter { test =>
        BigInt(test._2) < (test._1.value.quantity.doubleValue() * target).toBigInt
      }

    log.debug(s"Successful hits: ${successfulHits.size}")

    successfulHits.headOption.flatMap { case (box, _) =>
      {
        // generate the address the owns the generator box
        val matchingAddr = Address(box.evidence)

        // lookup the public associated with the box,
        // (this is separate from the signing function so that the private key never leaves the KeyRing)
        val publicKey = publicKeyFromAddress(matchingAddr) match {
          case Success(pk) => pk
          case Failure(error) => throw error
        }

        // use the private key that owns the generator box to create a function that will sign the new block
        val signingFunction = signWithAddress(matchingAddr) _

        // use the secret key that owns the successful box to sign the rewards transactions
        val getAttMap: TX => Map[PublicKeyPropositionCurve25519, SignatureCurve25519] = (tx: TX) => {
          val sig = signingFunction(tx.messageToSign) match {
            case Success(sig) => sig
            case Failure(ex)  => throw ex
          }
          Map(publicKey -> sig)
        }

        val signedRewards = rawRewards.map {
          case tx: ArbitTransfer[_] => tx.copy(attestation = getAttMap(tx))
          case tx: PolyTransfer[_]  => tx.copy(attestation = getAttMap(tx))
        }

        // calculate the newly forged blocks updated difficulty
        val newDifficulty = calcNewBaseDifficulty(parent.height + 1, parent.difficulty, prevTimes :+ forgeTime)

        // add the signed coinbase transaction to the block, sign it, and return the newly forged block
        Block.createAndSign(
          parent.id,
          forgeTime,
          signedRewards ++ txsToInclude,
          box,
          publicKey,
          parent.height + 1,
          newDifficulty,
          blockVersion(parent.height + 1)
        )(signingFunction)

      } match {
        case Success(block) => Some(block)
        case Failure(ex) =>
          log.warn(s"A successful hit was found but failed to forge block due to exception: $ex")
          None
      }
    }
  }
}

////////////////////////////////////////////////////////////////////////////////////
/////////////////////////////// COMPANION SINGLETON ////////////////////////////////

object Forger {

  val actorName = "forger"

  case class ChainParams(totalStake: Int128, difficulty: Long)

  case class PickTransactionsResult(toApply: Seq[Transaction.TX], toEliminate: Seq[Transaction.TX])

  object ReceivableMessages {

    case object GenerateGenesis

    case object StartForging

    case object StopForging

  }

}

////////////////////////////////////////////////////////////////////////////////////
//////////////////////////////// ACTOR REF HELPER //////////////////////////////////

object ForgerRef {

  def props(settings: AppSettings, appContext: AppContext, keyManager: ActorRef)(implicit ec: ExecutionContext): Props =
    Props(new Forger(settings, appContext, keyManager)(ec, appContext.networkType.netPrefix))

  def apply(name: String, settings: AppSettings, appContext: AppContext, keyManager: ActorRef)(implicit
    system:       ActorSystem,
    ec:           ExecutionContext
  ): ActorRef =
    system.actorOf(props(settings, appContext, keyManager), name)
}
