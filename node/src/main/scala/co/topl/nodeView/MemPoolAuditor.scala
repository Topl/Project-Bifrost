package co.topl.nodeView

import akka.actor.SupervisorStrategy.{Restart, Stop}
import akka.actor.{
  Actor,
  ActorInitializationException,
  ActorKilledException,
  ActorRef,
  ActorRefFactory,
  DeathPactException,
  OneForOneStrategy,
  Props
}
import akka.dispatch.Dispatchers
import co.topl.attestation.Address
import co.topl.modifier.ModifierId
import co.topl.modifier.block.{Block, BlockHeader}
import co.topl.modifier.box.ProgramId
import co.topl.modifier.transaction.Transaction
import co.topl.network.Broadcast
import co.topl.network.NetworkController.ReceivableMessages.SendToNetwork
import co.topl.network.NodeViewSynchronizer.ReceivableMessages.{
  ChangedMempool,
  ChangedState,
  SemanticallySuccessfulModifier
}
import co.topl.network.message.{InvData, InvSpec, Message}
import co.topl.nodeView.CleanupWorker.RunCleanup
import co.topl.nodeView.MempoolAuditor.CleanupDone
import co.topl.nodeView.NodeViewHolder.ReceivableMessages.GetNodeViewChanges
import co.topl.nodeView.mempool.MemPoolReader
import co.topl.nodeView.state.StateReader
import co.topl.settings.{AppContext, AppSettings, NodeViewReady}
import co.topl.utils.Logging
import co.topl.utils.NetworkType.NetworkPrefix

import scala.concurrent.duration._
import scala.reflect.ClassTag

/**
 * Controls mempool cleanup workflow. Watches NodeView events and delegates
 * mempool cleanup task to [[CleanupWorker]] when needed.
 * Adapted from ErgoPlatform available at https://github.com/ergoplatform/ergo
 */
class MempoolAuditor[
  SR <: StateReader[ProgramId, Address]: ClassTag,
  MR <: MemPoolReader[Transaction.TX]: ClassTag
](nodeViewHolderRef: ActorRef, networkControllerRef: ActorRef, settings: AppSettings, appContext: AppContext)
    extends Actor
    with Logging {

  implicit val networkPrefix: NetworkPrefix = appContext.networkType.netPrefix

  override val supervisorStrategy: OneForOneStrategy =
    OneForOneStrategy(maxNrOfRetries = 5, withinTimeRange = 1.minute) {
      case _: ActorKilledException => Stop
      case _: DeathPactException   => Stop
      case e: ActorInitializationException =>
        log.warn(s"Cleanup worker failed during initialization with: $e")
        Stop
      case e: Exception =>
        log.warn(s"Cleanup worker failed with: $e")
        context become awaiting // turn ctx into awaiting mode if worker failed
        Restart
    }

  private var stateReaderOpt: Option[SR] = None
  private var poolReaderOpt: Option[MR] = None

  private val worker: ActorRef =
    context.actorOf(
      Props(new CleanupWorker(nodeViewHolderRef, settings, appContext))
        .withDispatcher(Dispatchers.DefaultBlockingDispatcherId)
    )

  override def preStart(): Unit = {
    context.system.eventStream.subscribe(self, classOf[SemanticallySuccessfulModifier[_]])
    context.system.eventStream.subscribe(self, classOf[NodeViewReady])
  }

  override def postRestart(reason: Throwable): Unit = {
    log.error(s"Mempool auditor actor restarted due to ${reason.getMessage}", reason)
    super.postRestart(reason)
  }

  override def postStop(): Unit = {
    logger.info("Mempool auditor stopped")
    super.postStop()
  }

  ////////////////////////////////////////////////////////////////////////////////////
  ////////////////////////////// ACTOR MESSAGE HANDLING //////////////////////////////

  override def receive: Receive =
    initialization orElse
    nonsense

  private def initialization: Receive = {
    /** wait to start processing until the NodeViewHolder is ready * */
    case NodeViewReady(_) =>
      log.info(s"${Console.YELLOW}MemPool Auditor transitioning to the operational state${Console.RESET}")
      context become awaiting
  }

  private def awaiting: Receive = {
    case SemanticallySuccessfulModifier(_: Block) | SemanticallySuccessfulModifier(_: BlockHeader) =>
      stateReaderOpt = None
      poolReaderOpt = None
      nodeViewHolderRef ! GetNodeViewChanges(history = false, state = true, mempool = true)

    case ChangedMempool(mp: MR) =>
      poolReaderOpt = Some(mp)
      stateReaderOpt.foreach(st => initiateCleanup(st, mp))

    case ChangedState(st: SR) =>
      stateReaderOpt = Some(st)
      poolReaderOpt.foreach(mp => initiateCleanup(st, mp))

    case ChangedState(_) | ChangedMempool(_) => // do nothing

    case _ => nonsense
  }

  private def working: Receive = {
    case CleanupDone(ids) =>
      log.info("Cleanup done. Switching to awaiting mode")
      rebroadcastTransactions(ids)
      stateReaderOpt = None
      poolReaderOpt = None
      context become awaiting

    case _ => nonsense
  }

  private def nonsense: Receive = { case nonsense: Any =>
    log.warn(s"Got unexpected input $nonsense from ${sender()}")
  }

  ////////////////////////////////////////////////////////////////////////////////////
  //////////////////////////////// METHOD DEFINITIONS ////////////////////////////////

  private def initiateCleanup(state: SR, mempool: MR): Unit = {
    log.info("Initiating cleanup. Switching to working mode")
    worker ! RunCleanup(state, mempool)
    context become working // ignore other triggers until work is done
  }

  private def rebroadcastTransactions(ids: Seq[ModifierId]): Unit = {
    log.debug("Rebroadcasting transactions")
    poolReaderOpt.foreach { pr =>
      pr.getAll(ids).foreach { tx =>
        log.info(s"Rebroadcasting $tx")
        val msg = Message(
          new InvSpec(settings.network.maxInvObjects),
          Right(InvData(Transaction.modifierTypeId, Seq(tx.id))),
          None
        )

        networkControllerRef ! SendToNetwork(msg, Broadcast)
      }

    }
  }
}

////////////////////////////////////////////////////////////////////////////////////
/////////////////////////////// COMPANION SINGLETON ////////////////////////////////

object MempoolAuditor {

  val actorName = "mempoolAuditor"

  case class CleanupDone(toBeBroadcast: Seq[ModifierId])

}

////////////////////////////////////////////////////////////////////////////////////
//////////////////////////////// ACTOR REF HELPER //////////////////////////////////

object MempoolAuditorRef {

  def props[
    SR <: StateReader[ProgramId, Address]: ClassTag,
    MR <: MemPoolReader[Transaction.TX]: ClassTag
  ](settings: AppSettings, appContext: AppContext, nodeViewHolderRef: ActorRef, networkControllerRef: ActorRef): Props =
    Props(new MempoolAuditor(nodeViewHolderRef, networkControllerRef, settings, appContext))
      .withDispatcher(Dispatchers.DefaultBlockingDispatcherId)

  def apply[
    SR <: StateReader[ProgramId, Address]: ClassTag,
    MR <: MemPoolReader[Transaction.TX]: ClassTag
  ](
    name:                 String,
    settings:             AppSettings,
    appContext:           AppContext,
    nodeViewHolderRef:    ActorRef,
    networkControllerRef: ActorRef
  )(implicit
    context: ActorRefFactory
  ): ActorRef =
    context.actorOf(props(settings, appContext, nodeViewHolderRef, networkControllerRef), name)

}
