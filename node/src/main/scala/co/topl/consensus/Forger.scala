package co.topl.consensus

import akka.Done
import akka.actor._
import cats.data.EitherT
import akka.pattern.{ask, pipe}
import akka.util.Timeout
import cats.data.Validated
import cats.implicits._
import co.topl.attestation.{Address, PublicKeyPropositionCurve25519, SignatureCurve25519}
import co.topl.consensus.Forger.{AttemptForgingFailure, ChainParams, PickTransactionsResult}
import co.topl.consensus.KeyManager.ReceivableMessages._
import co.topl.consensus.KeyManager.{AttemptForgingKeyView, ForgerStartupKeyView}
import co.topl.consensus.genesis.{HelGenesis, PrivateGenesis, ToplnetGenesis, ValhallaGenesis}
import co.topl.modifier.block.Block
import co.topl.modifier.box.{ArbitBox, ProgramId}
import co.topl.modifier.transaction.{ArbitTransfer, PolyTransfer, Transaction}
import co.topl.nodeView.NodeViewHolder.ReceivableMessages.{EliminateTransactions, LocallyGeneratedModifier}
import co.topl.network.NodeViewSynchronizer.ReceivableMessages.{ChangedHistory, ChangedMempool, ChangedState}
import co.topl.network.message.BifrostSyncInfo
import co.topl.nodeView.NodeViewHolder.ReceivableMessages._
import co.topl.nodeView.history.HistoryReader
import co.topl.nodeView.mempool.MemPoolReader
import co.topl.nodeView.state.StateReader
import co.topl.settings.{AppContext, AppSettings, NodeViewReady}
import co.topl.utils.NetworkType._
import co.topl.utils.{Int128, Logging, TimeProvider}

import scala.collection.immutable.ListMap
import scala.language.implicitConversions
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag
import scala.util.{Failure, Success, Try}

/**
 * Forger takes care of attempting to create new blocks using the wallet provided in the NodeView
 * Must be singleton
 */
class Forger[
  HR <: HistoryReader[Block, BifrostSyncInfo]: ClassTag,
  SR <: StateReader[ProgramId, Address]: ClassTag,
  MR <: MemPoolReader[Transaction.TX]: ClassTag
](settings: AppSettings, appContext: AppContext, keyManager: ActorRef)(implicit ec: ExecutionContext, np: NetworkPrefix)
    extends Actor
    with Logging {

  type TX = Transaction.TX

  // Import the types of messages this actor RECEIVES
  import Forger.ReceivableMessages._

  // the nodeViewHolder actor ref for retrieving the current state
  private var nodeViewHolderRef: Option[ActorRef] = None

  override def preStart(): Unit = {
    // determine the set of applicable protocol rules for this software version
    protocolMngr = ProtocolVersioner(settings.application.version, settings.forging.protocolVersions)
    consensusStorage = ConsensusStorage(settings, appContext.networkType)

    //register for application initialization message
    context.system.eventStream.subscribe(self, classOf[NodeViewReady])
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

  private def activeForging(hrOpt: Option[HR], srOpt: Option[SR], mrOpt: Option[MR]): Receive =
    getReaders(hrOpt, srOpt, mrOpt) orElse
    activeHandlers orElse
    nonsense

  // ----------- MESSAGE PROCESSING FUNCTIONS
  private def initialization(implicit timeout: Timeout = 10 seconds): Receive = {
    case GenerateGenesis => generateGenesis
    case NodeViewReady(nvhRef: ActorRef) =>
      log.info(s"${Console.YELLOW}Forger transitioning to the operational state${Console.RESET}")
      nodeViewHolderRef = Some(nvhRef)
      context become readyToForge
      checkPrivateForging() // Generate keys again for private forging
  }

  private def readyHandlers: Receive = {
    case StartForging =>
      log.info("Received a START signal, forging will commence shortly.")
      context become activeForging(None, None, None)
      scheduleForgingAttempt() // schedule the next forging attempt

    case StopForging =>
      log.warn(s"Received a STOP signal while not forging. Signal ignored")
  }

  private def activeHandlers: Receive = {
    case StartForging =>
      log.warn(s"Forger: Received a START signal while forging. Signal ignored")

    case StopForging =>
      log.info(s"Forger: Received a stop signal. Forging will terminate after this trial")
      context become readyToForge
  }

  private def getReaders(hrOpt: Option[HR], srOpt: Option[SR], mrOpt: Option[MR]): Receive = {
    case ChangedHistory(hr: HR) => forgeWhenReady(Some(hr), srOpt, mrOpt)
    case ChangedState(sr: SR)   => forgeWhenReady(hrOpt, Some(sr), mrOpt)
    case ChangedMempool(mr: MR) => forgeWhenReady(hrOpt, srOpt, Some(mr))
  }

  private def nonsense: Receive = { case nonsense: Any =>
    log.warn(s"Got unexpected input $nonsense from ${sender()}")
  }

  ////////////////////////////////////////////////////////////////////////////////////
  //////////////////////////////// METHOD DEFINITIONS ////////////////////////////////
  /** Updates the forging actors timestamp */
  private def getForgeTime: TimeProvider.Time = appContext.timeProvider.time

  /**
   * Initializes addresses, updates max stake, and begins forging if using private network.
   * @param timeout time to wait for responses from key manager
   */
  private def checkPrivateForging()(implicit timeout: Timeout): Unit =
    if (Seq(PrivateTestnet, LocalTestnet).contains(appContext.networkType)) {
      (keyManager ? GenerateInitialAddresses)
        .mapTo[Try[ForgerStartupKeyView]]
        .map {
          case Success(ForgerStartupKeyView(_, Some(_))) =>
            // if forging has been enabled, then we should send the StartForging signal
            if (settings.forging.forgeOnStartup) self ! StartForging

          case Success(ForgerStartupKeyView(_, None)) =>
            log.warn("Forging not started: no reward address set.")

          case _ =>
            log.warn("Forging not started: failed to generate initial addresses in Key Ring.")
        }
        .recover { case ex =>
          log.warn("Forging not started: failed to generate initial addresses in Key Ring: ", ex)
        }
    }

  /**
   * Return the correct genesis parameters for the chosen network.
   * NOTE: the default private network is set in AppContext so the fall-through should result in an error.
   */
  private def generateGenesis(implicit timeout: Timeout = 10 seconds): Unit = {
    def generatePrivateGenesis(): Future[Try[(Block, ChainParams)]] =
      (keyManager ? GenerateInitialAddresses)
        .mapTo[Try[ForgerStartupKeyView]]
        .map {
          case Success(view) => PrivateGenesis(view.addresses, settings).getGenesisBlock
          case Failure(ex) =>
            throw new Error("Unable to generate genesis block, no addresses generated.", ex)
        }

    def initializeFromChainParamsAndGetBlock(block: Try[(Block, ChainParams)]): Try[Block] =
      block.map { case (block: Block, ChainParams(totalStake, initDifficulty)) =>
        consensusStorage.updateConsensusStorage(block.id, ConsensusParams(totalStake, initDifficulty, 0, 0))

        block
      }

    appContext.networkType match {
      case Mainnet         => sender() ! initializeFromChainParamsAndGetBlock(ToplnetGenesis.getGenesisBlock)
      case ValhallaTestnet => sender() ! initializeFromChainParamsAndGetBlock(ValhallaGenesis.getGenesisBlock)
      case HelTestnet      => sender() ! initializeFromChainParamsAndGetBlock(HelGenesis.getGenesisBlock)
      case LocalTestnet | PrivateTestnet =>
        generatePrivateGenesis()
          .map(initializeFromChainParamsAndGetBlock)
          .pipeTo(sender())
      case _ => throw new Error("Undefined network type.")
    }
  }

  /** Schedule a forging attempt */
  private def scheduleForgingAttempt(): Unit =
    nodeViewHolderRef match {
      case Some(nvh: ActorRef) =>
        context.system.scheduler.scheduleOnce(settings.forging.blockGenerationDelay)(
          nvh ! GetNodeViewChanges(history = true, state = true, mempool = true)
        )

      case _ =>
        log.warn("No ledger actor found. Stopping forging attempts")
        self ! StopForging
    }

  /** Helper function to attempt to forge if all readers are available, otherwise update the context */
  private def forgeWhenReady(hrOpt: Option[HR], srOpt: Option[SR], mrOpt: Option[MR]): Unit =
    checkNeededReaders(hrOpt, srOpt, mrOpt) match {
      case Some(_) => scheduleForgingAttempt() // this happens when there is a successful attempt
      case None =>
        context.become(activeForging(hrOpt, srOpt, mrOpt)) // still waiting for components from NodeViewHolder
    }

  /**
   * This function checks if the needed readers have been provided to the context in order to attempt forging.
   * If they have not, a None will be returned and forging will be attempted once all node view components
   * have been supplied
   */
  private def checkNeededReaders(hrOpt: Option[HR], srOpt: Option[SR], mrOpt: Option[MR]): Option[Unit] =
    for {
      hr <- hrOpt
      mr <- mrOpt
      sr <- srOpt
    } yield {
      tryForging(hr, sr, mr, getForgeTime) // initiate forging attempt
      context.become(activeForging(None, None, None)) // reset forging attempt (get new node view)
    }

  /**
   * Primary method for attempting to forge a new block and publish it to the network
   *
   * @param historyReader read-only history instance for gathering chain parameters
   * @param stateReader   read-only state instance for semantic validity tests of transactions
   * @param memPoolReader read-only mempool instance for picking transactions to include in the block if created
   */
  private def tryForging(historyReader: HR, stateReader: SR, memPoolReader: MR, forgeTime: TimeProvider.Time)(implicit
    timeout:                            Timeout = settings.forging.blockGenerationDelay
  ): Unit =
    try {
      (keyManager ? GetAttemptForgingKeyView)
        .mapTo[AttemptForgingKeyView]
        .map(view => attemptForging(historyReader, stateReader, memPoolReader, forgeTime, view))
        .foreach {
          case Right(block) =>
            context.system.eventStream.publish(LocallyGeneratedModifier[Block](block))
          case Left(Forger.ForgingError(error)) =>
            log.warn("Forger was eligible to forge a new block, but an error occurred.", error)
          case Left(Forger.LeaderElectionFailure(LeaderElection.NoAddressesAvailable)) =>
            log.warn("Forger has no addresses available to stake with.")
          case Left(Forger.LeaderElectionFailure(LeaderElection.NoBoxesEligible)) =>
            log.debug("No boxes were eligible to forge with.")
          case Left(Forger.LeaderElectionFailure(LeaderElection.NoArbitBoxesAvailable)) =>
            log.debug("No arbit boxes available to stake with.")
        }
    } catch {
      case ex: Throwable =>
        log.warn(s"Disabling forging due to exception: $ex. Resolve forging error and try forging again.")
        self ! StopForging
    }

  /**
   * Determines if forging eligibility and forges block if eligible.
   * @param historyReader read-only history
   * @param stateReader read-only state
   * @param memPoolReader read-only mem-pool
   * @param forgeTime time since last forge
   * @param attemptForgingKeyView forging view of the key ring
   * @return a block if forging was successful and None otherwise
   */
  private def attemptForging(
    historyReader:         HR,
    stateReader:           SR,
    memPoolReader:         MR,
    forgeTime:             TimeProvider.Time,
    attemptForgingKeyView: AttemptForgingKeyView
  ): Either[AttemptForgingFailure, Block] = {
    log.debug(
      s"${Console.MAGENTA}Attempting to forge with settings ${protocolMngr.current(historyReader.height)} " +
      s"and from addresses: ${attemptForgingKeyView.addresses}${Console.RESET}"
    )

    log.info(
      s"${Console.CYAN}Trying to generate a new block on top of ${historyReader.bestBlock.id}. Parent has " +
      s"height ${historyReader.height} and difficulty ${historyReader.difficulty} ${Console.RESET}"
    )

    val rewardAddress = attemptForgingKeyView.rewardAddr.getOrElse(throw new Error("No rewards address specified"))

    // pick the transactions from the mempool for inclusion in the block (if successful)
    val transactions = pickTransactions(memPoolReader, stateReader, historyReader.height) match {
      case Success(res) =>
        if (res.toEliminate.nonEmpty) nodeViewHolderRef.foreach(_ ! EliminateTransactions(res.toEliminate.map(_.id)))
        res.toApply

      case Failure(ex) => throw ex
    }

    val parentBlock = historyReader.bestBlock

    // create the coinbase and unsigned fee reward transactions
    val rewards = Rewards(transactions, rewardAddress, parentBlock.id, forgeTime) match {
      case Success(r)  => r
      case Failure(ex) => throw ex
    }

    // retrieve the latest TWO block times for updating the difficulty if we forge a new blow
    val prevTimes = historyReader.getTimestampsFrom(parentBlock, nxtBlockNum)

    // check forging eligibility and forge block if successful
    LeaderElection
      .getEligibleBox(parentBlock, attemptForgingKeyView.addresses, forgeTime, stateReader)
      .leftMap(Forger.LeaderElectionFailure)
      .flatMap(
        forgeBlockWithBox(
          _,
          parentBlock,
          prevTimes,
          rewards,
          transactions,
          forgeTime,
          attemptForgingKeyView.sign,
          attemptForgingKeyView.getPublicKey
        )
      )
  }

  /**
   * Pick a set of transactions from the mempool that result in a valid state when applied to the current state
   *
   * @param memPoolReader the set of pending transactions
   * @param stateReader state to use for semantic validity checking
   * @return a sequence of valid transactions
   */
  private def pickTransactions(memPoolReader: MR, stateReader: SR, chainHeight: Long): Try[PickTransactionsResult] =
    Try {

      memPoolReader
        .take[Int128](numTxInBlock(chainHeight))(-_.tx.fee) // returns a sequence of transactions ordered by their fee
        .filter(
          _.tx.fee >= settings.forging.minTransactionFee
        ) // default strategy ignores zero fee transactions in mempool
        .foldLeft(PickTransactionsResult(Seq(), Seq())) { case (txAcc, utx) =>
          // ensure that each transaction opens a unique box by checking that this transaction
          // doesn't open a box already being opened by a previously included transaction
          val boxAlreadyUsed = utx.tx.boxIdsToOpen.exists(id => txAcc.toApply.flatMap(_.boxIdsToOpen).contains(id))

          // if any newly created box matches a box already in the UTXO set in state, remove the transaction
          val boxAlreadyExists = utx.tx.newBoxes.exists(b => stateReader.getBox(b.id).isDefined)

          (boxAlreadyUsed, boxAlreadyExists) match {
            case (false, false) =>
              import co.topl.modifier.transaction.validation.implicits._
              utx.tx.semanticValidation(stateReader) match {
                case Validated.Valid(_) => PickTransactionsResult(txAcc.toApply :+ utx.tx, txAcc.toEliminate)
                case Validated.Invalid(ex) =>
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

  /**
   * Forges a block with the given eligible arbit box and state parameters.
   * @param box an eligible arbit box
   * @param parent the parent block
   * @param prevTimes the previous block times to determine next difficulty
   * @param rawRewards the raw forging rewards
   * @param txsToInclude the set of transactions to be entered into the block
   * @param forgeTime the current timestamp
   * @param sign a function for signing messages
   * @param getPublicKey a function for getting the public key associated with an address
   * @return a block if forging was successful and None otherwise
   */
  private def forgeBlockWithBox(
    box:          ArbitBox,
    parent:       Block,
    prevTimes:    Vector[TimeProvider.Time],
    rawRewards:   Seq[TX],
    txsToInclude: Seq[TX],
    forgeTime:    TimeProvider.Time,
    sign:         Address => Array[Byte] => Try[SignatureCurve25519],
    getPublicKey: Address => Try[PublicKeyPropositionCurve25519]
  ): Either[Forger.ForgingError, Block] = {

    // generate the address the owns the generator box
    val matchingAddr = Address(box.evidence)

    // lookup the public associated with the box,
    // (this is separate from the signing function so that the private key never leaves the KeyRing)
    val publicKey = getPublicKey(matchingAddr) match {
      case Success(pk) => pk
      case Failure(error) =>
        log.warn("Error occurred while getting public key for address.")
        throw error
    }

    // use the private key that owns the generator box to create a function that will sign the new block
    val signingFunction = sign(matchingAddr)

    // use the secret key that owns the successful box to sign the rewards transactions
    val getAttMap: TX => ListMap[PublicKeyPropositionCurve25519, SignatureCurve25519] = (tx: TX) => {
      val sig = signingFunction(tx.messageToSign) match {
        case Success(sig) => sig
        case Failure(ex)  => throw ex
      }
      ListMap(publicKey -> sig)
    }

    val signedRewards = rawRewards.map {
      case tx: ArbitTransfer[_] => tx.copy(attestation = getAttMap(tx))
      case tx: PolyTransfer[_]  => tx.copy(attestation = getAttMap(tx))
    }

    // calculate the newly forged blocks updated difficulty
    val newDifficulty = calcNewBaseDifficulty(parent.height + 1, parent.difficulty, prevTimes :+ forgeTime)

    // add the signed coinbase transaction to the block, sign it, and return the newly forged block
    Block
      .createAndSign(
        parent.id,
        forgeTime,
        signedRewards ++ txsToInclude,
        box,
        publicKey,
        parent.height + 1,
        newDifficulty,
        blockVersion(parent.height + 1)
      )(signingFunction)
      .toEither
      .leftMap(Forger.ForgingError)
  }
}

////////////////////////////////////////////////////////////////////////////////////
/////////////////////////////// COMPANION SINGLETON ////////////////////////////////

object Forger {

  val actorName = "forger"

  case class ChainParams(totalStake: Int128, difficulty: Long)

  case class PickTransactionsResult(toApply: Seq[Transaction.TX], toEliminate: Seq[Transaction.TX])

  sealed trait AttemptForgingFailure
  case class LeaderElectionFailure(reason: LeaderElection.IneligibilityReason) extends AttemptForgingFailure
  case class ForgingError(error: Throwable) extends AttemptForgingFailure

  object ReceivableMessages {

    case object GenerateGenesis

    case object StartForging

    case object StopForging

  }

}

////////////////////////////////////////////////////////////////////////////////////
//////////////////////////////// ACTOR REF HELPER //////////////////////////////////

object ForgerRef {

  def props[
    HR <: HistoryReader[Block, BifrostSyncInfo]: ClassTag,
    SR <: StateReader[ProgramId, Address]: ClassTag,
    MR <: MemPoolReader[Transaction.TX]: ClassTag
  ](settings: AppSettings, appContext: AppContext, keyManager: ActorRef)(implicit
    ec:       ExecutionContext,
    np:       NetworkPrefix
  ): Props =
    Props(new Forger[HR, SR, MR](settings, appContext, keyManager))

  def apply[
    HR <: HistoryReader[Block, BifrostSyncInfo]: ClassTag,
    SR <: StateReader[ProgramId, Address]: ClassTag,
    MR <: MemPoolReader[Transaction.TX]: ClassTag
  ](name:   String, settings: AppSettings, appContext: AppContext, keyManager: ActorRef)(implicit
    system: ActorSystem,
    ec:     ExecutionContext
  ): ActorRef = {
    implicit val np: NetworkPrefix = appContext.networkType.netPrefix
    system.actorOf(props[HR, SR, MR](settings, appContext, keyManager), name)
  }

}

sealed trait StartForgingFailure
sealed trait StopForgingFailure

trait ForgerInterface {
  def startForging(): EitherT[Future, StartForgingFailure, Done.type]
  def stopForging(): EitherT[Future, StopForgingFailure, Done.type]
}

class ActorForgerInterface(actorRef: ActorRef)(implicit ec: ExecutionContext) extends ForgerInterface {

  override def startForging(): EitherT[Future, StartForgingFailure, Done.type] =
    (actorRef ! Forger.ReceivableMessages.StartForging).asRight[StartForgingFailure].map(_ => Done).toEitherT[Future]

  override def stopForging(): EitherT[Future, StopForgingFailure, Done.type] =
    (actorRef ! Forger.ReceivableMessages.StopForging).asRight[StopForgingFailure].map(_ => Done).toEitherT[Future]
}
