package co.topl.nodeView

import akka.Done
import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.util.Timeout
import cats.data.EitherT
import co.topl.catsakka.AskException
import co.topl.consensus.Forger
import co.topl.consensus.Forger.ReceivableMessages.GenerateGenesis
import co.topl.modifier.NodeViewModifier.ModifierTypeId
import co.topl.modifier.block.serialization.BlockSerializer
import co.topl.modifier.block.{Block, PersistentNodeViewModifier, TransactionCarryingPersistentNodeViewModifier}
import co.topl.modifier.transaction.Transaction
import co.topl.modifier.transaction.serialization.TransactionSerializer
import co.topl.modifier.transaction.validation.implicits._
import co.topl.modifier.{ModifierId, NodeViewModifier}
import co.topl.network.NodeViewSynchronizer.ReceivableMessages._
import co.topl.nodeView.NodeViewHolder.{consensusCheckpoints, UpdateInformation}
import co.topl.nodeView.history.GenericHistory.ProgressInfo
import co.topl.nodeView.history.History
import co.topl.nodeView.mempool.MemPool
import co.topl.nodeView.state.State
import co.topl.settings.{AppContext, AppSettings, NodeViewReady}
import co.topl.utils.Logging
import co.topl.utils.NetworkType.NetworkPrefix
import co.topl.utils.StringDataTypes.Base58Data
import co.topl.utils.serialization.BifrostSerializer

import scala.annotation.tailrec
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

/**
 * Composite local view of the node
 *
 * Contains instances for History, MinimalState, Vault, MemoryPool.
 * The instances are read-only for external world.
 * Updates of the composite view(the instances are to be performed atomically.
 */
class NodeViewHolder(settings: AppSettings, appContext: AppContext)(implicit ec: ExecutionContext, np: NetworkPrefix)
    extends Actor
    with Logging {

  // Import the types of messages this actor can RECEIVE
  import NodeViewHolder.ReceivableMessages._

  type TX = Transaction.TX
  type PMOD = Block
  type HIS = History
  type MS = State
  type MP = MemPool
  type NodeView = (HIS, MS, MP)

  /**
   * The main data structure a node software is taking care about, a node view consists
   * of four elements to be updated atomically: history (log of persistent modifiers),
   * state (result of log's modifiers application to pre-historical(genesis) state,
   * user-specific information stored in vault (it could be e.g. a wallet), and a memory pool.
   */
  private var nodeView: NodeView = restoreState().getOrElse(genesisState)

  /**
   * Cache for modifiers. If modifiers are coming out-of-order, they are to be stored in this cache.
   */
  protected lazy val modifiersCache: ModifiersCache[PMOD, HIS] =
    new DefaultModifiersCache[PMOD, HIS](settings.network.maxModifiersCacheSize)

  lazy val modifierCompanions: Map[ModifierTypeId, BifrostSerializer[_ <: NodeViewModifier]] =
    Map(Block.modifierTypeId -> BlockSerializer, Transaction.modifierTypeId -> TransactionSerializer)

  /** Define actor control behavior */
  override def preStart(): Unit = {
    // subscribe to particular messages this actor expects to receive
    context.system.eventStream.subscribe(self, classOf[LocallyGeneratedModifier[PMOD]])

    log.info(s"${Console.YELLOW}NodeViewHolder publishing ready signal${Console.RESET}")
    context.system.eventStream.publish(NodeViewReady(this.self))
  }

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    super.preRestart(reason, message)
    reason.printStackTrace()
    System.exit(100) // this actor shouldn't be restarted at all so kill the whole app if that happened
  }

  override def postStop(): Unit = {
    log.info(s"${Console.RED}Application is going down NOW!${Console.RESET}")
    nodeView._1.closeStorage() // close History storage
    nodeView._2.closeStorage() // close State storage
  }

  ////////////////////////////////////////////////////////////////////////////////////
  ////////////////////////////// ACTOR MESSAGE HANDLING //////////////////////////////

  // ----------- CONTEXT
  override def receive: Receive =
    processModifiers orElse
    transactionsProcessing orElse
    getNodeViewChanges orElse
    nonsense

  // ----------- MESSAGE PROCESSING FUNCTIONS

  protected def processModifiers: Receive = {
    case ModifiersFromRemote(mods: Iterable[PMOD] @unchecked) => processRemoteModifiers(mods)

    case LocallyGeneratedModifier(mod: Block) =>
      log.info(s"Got locally generated modifier ${mod.id} of type ${mod.modifierTypeId}")
      pmodModify(mod)
  }

  protected def transactionsProcessing: Receive = {
    case newTxs: NewTransactions => newTxs.txs.foreach(txModify)

    case EliminateTransactions(ids) =>
      log.debug(s"${Console.YELLOW} Removing transactions with ids: $ids from mempool${Console.RESET}")
      val updatedPool = memoryPool().filter(tx => !ids.contains(tx.id))
      updateNodeView(updatedMempool = Some(updatedPool))
      ids.foreach { id =>
        val e = new Exception("Became invalid")
        context.system.eventStream.publish(FailedTransaction(id, e, immediateFailure = false))
      }
  }

  protected def getNodeViewChanges: Receive = { case GetNodeViewChanges(history, state, mempool) =>
    if (history) sender() ! ChangedHistory(nodeView._1.getReader)
    if (state) sender() ! ChangedState(nodeView._2.getReader)
    if (mempool) sender() ! ChangedMempool(nodeView._3.getReader)
  }

  protected def nonsense: Receive = { case nonsense: Any =>
    log.warn(s"NodeViewHolder: got unexpected input $nonsense from ${sender()}")
  }

  ////////////////////////////////////////////////////////////////////////////////////
  //////////////////////////////// METHOD DEFINITIONS ////////////////////////////////

  protected def history(): HIS = nodeView._1

  protected def minimalState(): MS = nodeView._2

  protected def memoryPool(): MP = nodeView._3

  /**
   * Restore a local view during a node startup. If no any stored view found
   * (e.g. if it is a first launch of a node) None is to be returned
   */
  def restoreState(): Option[NodeView] =
    if (State.exists(settings)) {
      Some(
        (
          History.readOrGenerate(settings),
          State.readOrGenerate(settings),
          MemPool.emptyPool
        )
      )
    } else None

  /** Hard-coded initial view all the honest nodes in a network are making progress from. */
  private[nodeView] def genesisState(implicit timeout: Timeout = 10 seconds): NodeView = {
    // this has to be resolved before moving on with the rest of the initialization procedure
    val genesisBlock = Await.result(getGenesisBlock, timeout.duration)

    // generate the nodeView and return
    (
      History.readOrGenerate(settings).append(genesisBlock).get._1,
      State.genesisState(settings, Seq(genesisBlock)),
      MemPool.emptyPool
    )
  }

  private def getGenesisBlock(implicit timeout: Timeout): Future[Block] =
    // need to lookup the actor reference for the forger
    context.actorSelection("../" + Forger.actorName).resolveOne().flatMap { consensusRef =>
      // if a reference was found, ask for the genesis block
      (consensusRef ? GenerateGenesis).mapTo[Try[Block]].map {
        case Success(block) => block
        case Failure(ex) =>
          throw new Error(s"${Console.RED}Failed to initialize genesis due to error${Console.RESET} $ex")
      }
    }

  /**
   * Handles adding remote modifiers to the default cache and then attempts to apply them to the history.
   * Since this cache is unordered, we continue to loop through the cache until it's size remains constant.
   * This indicates that no more modifiers in the cache can be appended into history
   *
   * @param mods recieved persistent modifiers from the remote peer
   */
  protected def processRemoteModifiers(mods: Iterable[PMOD]): Unit = {

    /** First-order loop that tries to pop blocks out of the cache and apply them into history */
    @tailrec
    def applyLoop(applied: Seq[PMOD]): Seq[PMOD] =
      modifiersCache.popCandidate(history()) match {
        case Some(mod) =>
          pmodModify(mod)
          applyLoop(mod +: applied)
        case None =>
          applied
      }

    /** Second-order loop that continues to call the first-order loop until the size of the cache remains constant */
    @tailrec
    def applyAllApplicable(acc: Seq[PMOD], startSize: Int): Seq[PMOD] = {
      val applied = applyLoop(acc)
      if (modifiersCache.size == startSize) applied
      else applyAllApplicable(applied, modifiersCache.size)
    }

    // add all newly received modifiers to the cache
    mods.foreach(m => modifiersCache.put(m.id, m))

    // record the initial size for comparison
    val initialSize = modifiersCache.size

    // begin looping through the cache and trying to apply all applicable modifiers
    val applied = applyAllApplicable(Seq(), initialSize)

    // clean the cache if it is growing too large
    val cleared = modifiersCache.cleanOverfull()

    context.system.eventStream.publish(ModifiersProcessingResult(applied, cleared))
    log.debug(s"Cache size before: $initialSize")
    log.debug(s"Cache size after: ${modifiersCache.size}")
  }

  /**
   * @param tx
   */
  protected def txModify(tx: TX): Unit =
    tx.syntacticValidation.toEither match {
      case Right(_) =>
        memoryPool().put(tx, appContext.timeProvider.time) match {
          case Success(_) =>
            log.debug(s"Unconfirmed transaction $tx added to the memory pool")
            context.system.eventStream.publish(SuccessfulTransaction[TX](tx))

          case Failure(e) =>
            context.system.eventStream.publish(FailedTransaction(tx.id, e, immediateFailure = true))
        }

      case Left(e) =>
        context.system.eventStream.publish(
          FailedTransaction(tx.id, new Exception(e.head.toString), immediateFailure = true)
        )
    }

  //todo: update state in async way?
  /**
   * @param pmod
   */
  protected def pmodModify(pmod: PMOD): Unit =
    if (!history().contains(pmod.id)) {
      context.system.eventStream.publish(StartingPersistentModifierApplication(pmod))

      def isBlockTxsValidated: Boolean =
        consensusCheckpoints.contains(pmod.id) || pmod.transactions.forall(_.semanticValidation(minimalState()).isValid)

      // check that the transactions are semantically valid
      if (isBlockTxsValidated) {
        log.info(s"Apply modifier ${pmod.id} of type ${pmod.modifierTypeId} to nodeViewHolder")

        // append the block to history
        history().append(pmod) match {
          case Success((historyBeforeStUpdate, progressInfo)) =>
            log.debug(s"Going to apply modifications to the state: $progressInfo")
            context.system.eventStream.publish(SyntacticallySuccessfulModifier(pmod))
            context.system.eventStream.publish(NewOpenSurface(historyBeforeStUpdate.openSurfaceIds()))

            if (progressInfo.toApply.nonEmpty) {
              val (newHistory, newStateTry, blocksApplied) =
                updateState(historyBeforeStUpdate, minimalState(), progressInfo, IndexedSeq())

              newStateTry match {
                case Success(newMinState) =>
                  val newMemPool = updateMemPool(progressInfo.toRemove, blocksApplied, memoryPool(), newMinState)

                  log.info(s"Persistent modifier ${pmod.id} applied successfully")
                  updateNodeView(Some(newHistory), Some(newMinState), Some(newMemPool))

                case Failure(e) =>
                  log.warn(s"Can`t apply persistent modifier (id: ${pmod.id}, contents: $pmod) to minimal state", e)
                  updateNodeView(updatedHistory = Some(newHistory))
                  context.system.eventStream.publish(SemanticallyFailedModification(pmod, e))
              }
            } else {
              requestDownloads(progressInfo)
              updateNodeView(updatedHistory = Some(historyBeforeStUpdate))
            }
          case Failure(e) =>
            log.warn(s"Can`t apply persistent modifier (id: ${pmod.id}, contents: $pmod) to history", e)
            context.system.eventStream.publish(SyntacticallyFailedModification(pmod, e))
        }
      } else {
        log.warn(s"Trying to apply modifier ${pmod.id} containing invalid transactions")
      }
    } else {
      log.warn(s"Trying to apply modifier ${pmod.id} that's already in history")
    }

  /**
   * @param mod - the block to retrieve transactions from
   * @return the sequence of transactions from a block
   */
  protected def extractTransactions(mod: PMOD): Seq[TX] = mod match {
    case tcm: TransactionCarryingPersistentNodeViewModifier[_] => tcm.transactions
    case _                                                     => Seq()
  }

  /**
   * @param pi
   */
  private def requestDownloads(pi: ProgressInfo[PMOD]): Unit =
    pi.toDownload.foreach { case (tid, id) =>
      context.system.eventStream.publish(DownloadRequest(tid, id))
    }

  /**
   * @param suffix
   * @param rollbackPoint
   * @return
   */
  private def trimChainSuffix(suffix: IndexedSeq[PMOD], rollbackPoint: ModifierId): IndexedSeq[PMOD] = {
    val idx = suffix.indexWhere(_.id == rollbackPoint)
    if (idx == -1) IndexedSeq() else suffix.drop(idx)
  }

  /** Below is a description of how state updates are managed */
  /**
   * --------------------------------------------------------------------------------------------------------------- /
   *  Assume that history knows the following blocktree:
   *
   *           G
   *          / \
   *   G
   *        /     \
   *       G
   *
   *    where path with G-s is about canonical chain (G means semantically valid modifier), path with * is sidechain
   *    (* means that semantic validity is unknown). New modifier is coming to the sidechain, it sends rollback to
   *    the root + application of the sidechain to the state. Assume that state is finding that some modifier in the
   *    sidechain is incorrect:
   *
   *           G
   *          / \
   *         G   G
   *        /     \
   *       B       G
   *      /
   *
   *  In this case history should be informed about the bad modifier and it should retarget state
   *
   *    //todo: improve the comment below
   *
   *    We assume that we apply modifiers sequentially (on a single modifier coming from the network or generated locally),
   *    and in case of failed application of some modifier in a progressInfo, rollback point in an alternative should be not
   *    earlier than a rollback point of an initial progressInfo.
   *  / --------------------------------------------------------------------------------------------------------------- *
   */

  @tailrec
  private def updateState(
    history:       HIS,
    state:         MS,
    progressInfo:  ProgressInfo[PMOD],
    suffixApplied: IndexedSeq[PMOD]
  ): (HIS, Try[MS], Seq[PMOD]) = {

    requestDownloads(progressInfo)

    val (stateToApplyTry: Try[MS], suffixTrimmed: IndexedSeq[PMOD]) = if (progressInfo.chainSwitchingNeeded) {
      @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
      val branchingPoint = progressInfo.branchPoint.get //todo: .get
      if (state.version != branchingPoint) {
        state.rollbackTo(branchingPoint) -> trimChainSuffix(suffixApplied, branchingPoint)
      } else Success(state) -> IndexedSeq()
    } else Success(state) -> suffixApplied

    stateToApplyTry match {
      case Success(stateToApply) =>
        val stateUpdateInfo = applyState(history, stateToApply, suffixTrimmed, progressInfo)

        stateUpdateInfo.failedMod match {
          case Some(_) =>
            @SuppressWarnings(Array("org.wartremover.warts.OptionPartial"))
            val alternativeProgressInfo = stateUpdateInfo.alternativeProgressInfo.get
            updateState(stateUpdateInfo.history, stateUpdateInfo.state, alternativeProgressInfo, stateUpdateInfo.suffix)
          case None => (stateUpdateInfo.history, Success(stateUpdateInfo.state), stateUpdateInfo.suffix)
        }
      case Failure(e) =>
        log.error("Rollback failed: ", e)
        context.system.eventStream.publish(RollbackFailed)
        //todo: what to return here? the situation is totally wrong
        ???
    }
  }

  /**
   * Update NodeView with new components and notify subscribers of changed components
   *
   * @param updatedHistory
   * @param updatedState
   * @param updatedMempool
   */
  protected def updateNodeView(
    updatedHistory: Option[HIS] = None,
    updatedState:   Option[MS] = None,
    updatedMempool: Option[MP] = None
  ): Unit = {
    val newNodeView =
      (
        updatedHistory.getOrElse(history()),
        updatedState.getOrElse(minimalState()),
        updatedMempool.getOrElse(memoryPool())
      )

    if (updatedHistory.nonEmpty)
      context.system.eventStream.publish(ChangedHistory(newNodeView._1.getReader))

    if (updatedState.nonEmpty)
      context.system.eventStream.publish(ChangedState(newNodeView._2.getReader))

    if (updatedMempool.nonEmpty)
      context.system.eventStream.publish(ChangedMempool(newNodeView._3.getReader))

    nodeView = newNodeView
  }

  //todo: this method causes delays in a block processing as it removes transactions from mempool and checks
  //todo: validity of remaining transactions in a synchronous way. Do this job async!
  protected def updateMemPool(blocksRemoved: Seq[PMOD], blocksApplied: Seq[PMOD], memPool: MP, state: MS): MP = {
    // drop the first two transactions, since these are the reward transactions and invalid
    val rolledBackTxs = blocksRemoved.flatMap(b => extractTransactions(b).drop(2))

    val appliedTxs = blocksApplied.flatMap(extractTransactions)

    memPool
      .putWithoutCheck(rolledBackTxs, appContext.timeProvider.time)
      .filter { tx =>
        !appliedTxs.exists(t => t.id == tx.id) && {
          tx.syntacticValidation.isValid
        }
      }
  }

  /**
   * Attempts to update the local view of state by applying a set of blocks
   *
   * @param history the initial view of history prior to updating
   * @param stateToApply the initial view of state prior to updating
   * @param suffixTrimmed ???
   * @param progressInfo class with blocks that need to be applied to state
   * @return
   */
  protected def applyState(
    history:       HIS,
    stateToApply:  MS,
    suffixTrimmed: IndexedSeq[PMOD],
    progressInfo:  ProgressInfo[PMOD]
  ): UpdateInformation[HIS, MS, PMOD] = {

    val updateInfoInit = UpdateInformation[HIS, MS, PMOD](history, stateToApply, None, None, suffixTrimmed)

    progressInfo.toApply.foldLeft(updateInfoInit) { case (updateInfo, modToApply) =>
      if (updateInfo.failedMod.isEmpty) {
        updateInfo.state.applyModifier(modToApply) match {
          case Success(stateAfterApply) =>
            val newHis = history.reportModifierIsValid(modToApply)
            context.system.eventStream.publish(SemanticallySuccessfulModifier(modToApply))
            UpdateInformation(newHis, stateAfterApply, None, None, updateInfo.suffix :+ modToApply)

          case Failure(e) =>
            val (newHis, newProgressInfo) = history.reportModifierIsInvalid(modToApply, progressInfo)
            context.system.eventStream.publish(SemanticallyFailedModification(modToApply, e))
            UpdateInformation(newHis, updateInfo.state, Some(modToApply), Some(newProgressInfo), updateInfo.suffix)
        }
      } else updateInfo
    }
  }
}

////////////////////////////////////////////////////////////////////////////////////
/////////////////////////////// COMPANION SINGLETON ////////////////////////////////

object NodeViewHolder {
  val actorName = "nodeViewHolder"

  case class UpdateInformation[HIS, MS, PMOD <: PersistentNodeViewModifier](
    history:                 HIS,
    state:                   MS,
    failedMod:               Option[PMOD],
    alternativeProgressInfo: Option[ProgressInfo[PMOD]],
    suffix:                  IndexedSeq[PMOD]
  )

  object ReceivableMessages {

    // Explicit request of NodeViewChange events of certain types.
    case class GetNodeViewChanges(history: Boolean, state: Boolean, mempool: Boolean)

    // Retrieve data from current view
    case object GetDataFromCurrentView

    // Modifiers received from the remote peer with new elements in it
    case class ModifiersFromRemote[PMOD <: PersistentNodeViewModifier](modifiers: Iterable[PMOD])

    case class LocallyGeneratedModifier[PMOD <: PersistentNodeViewModifier](pmod: PMOD)

    sealed trait NewTransactions { val txs: Iterable[Transaction.TX] }

    case class LocallyGeneratedTransaction(tx: Transaction.TX) extends NewTransactions {
      override val txs: Iterable[Transaction.TX] = Iterable(tx)
    }

    case class TransactionsFromRemote(txs: Iterable[Transaction.TX]) extends NewTransactions

    case class EliminateTransactions(ids: Seq[ModifierId])

  }

  val consensusCheckpoints: Seq[ModifierId] = Seq(
    ModifierId.fromBase58(
      Base58Data.unsafe("29QHPjqyLB1QN6DhArf125Nu3qfgKLcPRnZGvaCX8qDNf")
    ) // block height 255181 Valhalla testnet
  )
}

////////////////////////////////////////////////////////////////////////////////////
//////////////////////////////// ACTOR REF HELPER //////////////////////////////////

object NodeViewHolderRef {

  def props(settings: AppSettings, appContext: AppContext)(implicit ec: ExecutionContext): Props =
    Props(new NodeViewHolder(settings, appContext)(ec, appContext.networkType.netPrefix))

  def apply(name: String, settings: AppSettings, appContext: AppContext)(implicit
    system:       ActorSystem,
    ec:           ExecutionContext
  ): ActorRef =
    system.actorOf(props(settings, appContext), name)
}

sealed trait GetHistoryFailure
case class GetHistoryFailureException(throwable: Throwable) extends GetHistoryFailure
sealed trait GetStateFailure
case class GetStateFailureException(throwable: Throwable) extends GetStateFailure
sealed trait GetMempoolFailure
case class GetMempoolFailureException(throwable: Throwable) extends GetMempoolFailure
sealed trait BroadcastTxFailure
case class BroadcastTxFailureException(throwable: Throwable) extends BroadcastTxFailure

trait NodeViewHolderInterface {
  def getHistory(): EitherT[Future, GetHistoryFailure, History]
  def getState(): EitherT[Future, GetStateFailure, State]
  def getMempool(): EitherT[Future, GetMempoolFailure, MemPool]
  def broadcastTransaction(tx: Transaction.TX): EitherT[Future, BroadcastTxFailure, Done.type]
}

class ActorNodeViewHolderInterface(actorRef: ActorRef)(implicit ec: ExecutionContext, timeout: Timeout)
    extends NodeViewHolderInterface {
  import cats.implicits._
  import co.topl.catsakka.CatsActor._

  override def getHistory(): EitherT[Future, GetHistoryFailure, History] =
    actorRef
      .askEither[ChangedHistory[History]](
        NodeViewHolder.ReceivableMessages.GetNodeViewChanges(history = true, state = false, mempool = false)
      )
      .leftMap { case AskException(throwable) => GetHistoryFailureException(throwable) }
      .leftMap(e => e: GetHistoryFailure)
      .map(_.reader)

  override def getState(): EitherT[Future, GetStateFailure, State] =
    actorRef
      .askEither[ChangedState[State]](
        NodeViewHolder.ReceivableMessages.GetNodeViewChanges(history = false, state = true, mempool = false)
      )
      .leftMap { case AskException(throwable) => GetStateFailureException(throwable) }
      .leftMap(e => e: GetStateFailure)
      .map(_.reader)

  override def getMempool(): EitherT[Future, GetMempoolFailure, MemPool] =
    actorRef
      .askEither[ChangedMempool[MemPool]](
        NodeViewHolder.ReceivableMessages.GetNodeViewChanges(history = false, state = false, mempool = true)
      )
      .leftMap { case AskException(throwable) => GetMempoolFailureException(throwable) }
      .leftMap(e => e: GetMempoolFailure)
      .map(_.reader)

  def broadcastTransaction(tx: Transaction.TX): EitherT[Future, BroadcastTxFailure, Done.type] =
    (actorRef ! NodeViewHolder.ReceivableMessages.LocallyGeneratedTransaction(tx))
      .asRight[BroadcastTxFailure]
      .map(_ => Done)
      .toEitherT[Future]
}
