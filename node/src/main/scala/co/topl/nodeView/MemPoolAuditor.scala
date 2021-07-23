package co.topl.nodeView

import akka.actor.SupervisorStrategy.{Restart, Stop}
import akka.actor.{
  Actor,
  ActorInitializationException,
  ActorKilledException,
  ActorRef,
  DeathPactException,
  OneForOneStrategy,
  Props
}
import akka.util.Timeout
import co.topl.attestation.Address
import co.topl.modifier.ModifierId
import co.topl.modifier.block.{Block, BlockHeader}
import co.topl.modifier.box.ProgramId
import co.topl.modifier.transaction.Transaction
import co.topl.network.Broadcast
import co.topl.network.NetworkController.ReceivableMessages.SendToNetwork

import co.topl.network.message.{InvData, InvSpec, Message}
import co.topl.nodeView.CleanupWorker.RunCleanup
import co.topl.nodeView.MempoolAuditor.CleanupDone
import co.topl.nodeView.mempool.MemPoolReader
import co.topl.nodeView.state.StateReader
import co.topl.settings.{AppContext, AppSettings}
import co.topl.utils.NetworkType.NetworkPrefix
import co.topl.utils.{Logging, TimeProvider}

import scala.concurrent.Future
import scala.concurrent.duration._

/**
 * Controls mempool cleanup workflow. Watches NodeView events and delegates
 * mempool cleanup task to [[CleanupWorker]] when needed.
 * Adapted from ErgoPlatform available at https://github.com/ergoplatform/ergo
 */
class MempoolAuditor(
  nodeViewHolderRef:     akka.actor.typed.ActorRef[NodeViewHolder.ReceivableMessage],
  networkControllerRef:  ActorRef,
  settings:              AppSettings,
  appContext:            AppContext
)(implicit timeProvider: TimeProvider)
    extends Actor
    with Logging {

  import context.dispatcher

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

  private val worker: ActorRef =
    context.actorOf(
      Props(new CleanupWorker(nodeViewHolderRef, settings))
    )

  override def preStart(): Unit = {
    context.system.eventStream.subscribe(self, classOf[NodeViewHolder.Events.SemanticallySuccessfulModifier[_]])
    context.system.eventStream.subscribe(self, classOf[NodeViewHolder.Events.ChangedState.type])
    context.system.eventStream.subscribe(self, classOf[NodeViewHolder.Events.ChangedMempool.type])
    log.info(s"${Console.YELLOW}MemPool Auditor transitioning to the operational state${Console.RESET}")
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
    awaiting orElse nonsense

  private def withNodeView[T](f: ReadableNodeView => T): Future[T] = {
    import akka.actor.typed.scaladsl.AskPattern._
    import akka.actor.typed.scaladsl.adapter._

    import scala.concurrent.duration._
    implicit val timeout: Timeout = Timeout(10.seconds)
    implicit val typedSystem: akka.actor.typed.ActorSystem[_] = context.system.toTyped
    nodeViewHolderRef.askWithStatus[T](NodeViewHolder.ReceivableMessages.Read(f, _))
  }

  private def awaiting: Receive = {
    case NodeViewHolder.Events.SemanticallySuccessfulModifier(_: Block) |
        NodeViewHolder.Events.SemanticallySuccessfulModifier(_: BlockHeader) =>
      withNodeView(view => initiateCleanup(view.state, view.memPool))

    case NodeViewHolder.Events.ChangedMempool =>
      withNodeView(view => initiateCleanup(view.state, view.memPool))

    case NodeViewHolder.Events.ChangedState =>
      withNodeView(view => initiateCleanup(view.state, view.memPool))

    case _ => nonsense
  }

  private def working: Receive = {
    case CleanupDone(ids) =>
      log.info("Cleanup done. Switching to awaiting mode")
      rebroadcastTransactions(ids)
      context become awaiting

    case _ => nonsense
  }

  private def nonsense: Receive = { case nonsense: Any =>
    log.warn(s"Got unexpected input $nonsense from ${sender()}")
  }

  ////////////////////////////////////////////////////////////////////////////////////
  //////////////////////////////// METHOD DEFINITIONS ////////////////////////////////

  private def initiateCleanup(state: StateReader[ProgramId, Address], mempool: MemPoolReader[Transaction.TX]): Unit = {
    log.info("Initiating cleanup. Switching to working mode")
    worker ! RunCleanup(state, mempool)
    context become working // ignore other triggers until work is done
  }

  private def rebroadcastTransactions(ids: Seq[ModifierId]): Unit = {
    log.debug("Rebroadcasting transactions")
    withNodeView(view => view.memPool.getAll(ids))
      .foreach(transactions =>
        transactions.foreach { tx =>
          log.info(s"Rebroadcasting $tx")
          val msg = Message(
            new InvSpec(settings.network.maxInvObjects),
            Right(InvData(Transaction.modifierTypeId, Seq(tx.id))),
            None
          )

          networkControllerRef ! SendToNetwork(msg, Broadcast)
        }
      )
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

  def props(
    settings:              AppSettings,
    appContext:            AppContext,
    nodeViewHolderRef:     akka.actor.typed.ActorRef[NodeViewHolder.ReceivableMessage],
    networkControllerRef:  ActorRef
  )(implicit timeProvider: TimeProvider): Props =
    Props(new MempoolAuditor(nodeViewHolderRef, networkControllerRef, settings, appContext))

}
