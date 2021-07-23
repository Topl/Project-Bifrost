package co.topl.nodeView

import akka.Done
import akka.actor.typed.eventstream.EventStream
import akka.actor.typed.receptionist.{Receptionist, ServiceKey}
import akka.actor.typed.scaladsl._
import akka.actor.typed.{ActorRef, ActorSystem, _}
import akka.pattern.StatusReply
import akka.util.Timeout
import cats.Show
import cats.data.{EitherT, Writer}
import co.topl.consensus.LocallyGeneratedBlock
import co.topl.modifier.ModifierId
import co.topl.modifier.NodeViewModifier.ModifierTypeId
import co.topl.modifier.block.{Block, PersistentNodeViewModifier}
import co.topl.modifier.transaction.Transaction
import co.topl.modifier.transaction.Transaction.TX
import co.topl.network.message.BifrostSyncInfo
import co.topl.nodeView.history.GenericHistory.ProgressInfo
import co.topl.nodeView.history.{GenericHistory, History}
import co.topl.nodeView.state.{MinimalState, State}
import co.topl.settings.AppSettings
import co.topl.utils.NetworkType.NetworkPrefix
import co.topl.utils.TimeProvider
import co.topl.utils.actors.SortedCache

import scala.concurrent.{Future, Promise}
import scala.util.Try

/**
 * A Typed actor which holds onto block history, box state, and a transaction mempool.  The outside world can
 * issue mutation instructions to it, or it reader functions can be provided to the actor to read data from the view.
 */
object NodeViewHolder {

  final val ActorName = "node-view-holder"
  final val serviceKey: ServiceKey[ReceivableMessage] = ServiceKey(ActorName)

  case class UpdateInformation(
    history:                 GenericHistory[Block, BifrostSyncInfo, History],
    state:                   MinimalState[Block, State],
    failedMod:               Option[Block],
    alternativeProgressInfo: Option[ProgressInfo[Block]],
    suffix:                  IndexedSeq[Block]
  )

  sealed abstract class ReceivableMessage

  object ReceivableMessages {

    /**
     * A self-message that's sent to the actor once async initialization is completed
     * @param nodeView the initialized NodeView
     */
    private[NodeViewHolder] case class Initialized(nodeView: NodeView) extends ReceivableMessage

    /**
     * A self-message indicating that initialization failed
     */
    private[NodeViewHolder] case class InitializationFailed(reason: Throwable) extends ReceivableMessage

    /**
     * The main public "read" interface for interacting with a read-only node view.  It accepts a function which is
     * run on a readable node view and returns some sort of data belonging to the domain of the caller.
     * @param f A function to extract data from the node view
     * @param replyTo The actor that asked for the data
     * @tparam T The caller's domain-specific response type
     */
    case class Read[T](f: ReadableNodeView => T, replyTo: ActorRef[StatusReply[T]]) extends ReceivableMessage {

      private[NodeViewHolder] def run(readableNodeView: ReadableNodeView): Unit =
        replyTo.tell(
          Try(f(readableNodeView)).fold[StatusReply[T]](e => StatusReply.error(e), StatusReply.success)
        )
    }

    /**
     * The main public "write" interface for block data.
     */
    case class WriteBlocks(blocks: Iterable[Block]) extends ReceivableMessage

    /**
     * The private interface to write a single block.
     * @param block A block that is valid in the current node view
     */
    private[NodeViewHolder] case class WriteBlock(block: Block) extends ReceivableMessage

    /**
     * Public message to write transactions
     */
    case class WriteTransactions(transactions: Iterable[Transaction.TX]) extends ReceivableMessage

    /**
     * Remove transactions from the mempool
     */
    case class EliminateTransactions(transactionIds: Iterable[ModifierId]) extends ReceivableMessage

    /**
     * A message specifically used by unit tests to get the mutable internal actor state
     */
    private[nodeView] case class GetWritableNodeView(replyTo: ActorRef[NodeView]) extends ReceivableMessage

    /**
     * A message specifically used by unit tests to mutate the internal actor state
     */
    private[nodeView] case class ModifyNodeView(f: NodeView => NodeView, replyTo: ActorRef[Done])
        extends ReceivableMessage
  }

  sealed abstract class Event
  sealed abstract class ChangeEvent extends Event
  sealed abstract class OutcomeEvent extends Event

  object Events {

    case object ChangedHistory extends ChangeEvent

    case object ChangedMempool extends ChangeEvent

    case object ChangedState extends ChangeEvent
    case class NewOpenSurface(newSurface: Seq[ModifierId]) extends Event

    /** @param immediateFailure a flag indicating whether a transaction was invalid by the moment it was received. */
    case class FailedTransaction(transactionId: ModifierId, error: Throwable, immediateFailure: Boolean)
        extends OutcomeEvent

    case class SuccessfulTransaction[TX <: Transaction.TX](transaction: TX) extends OutcomeEvent

    case class StartingPersistentModifierApplication[PMOD <: PersistentNodeViewModifier](modifier: PMOD) extends Event

    case class SyntacticallyFailedModification[PMOD <: PersistentNodeViewModifier](modifier: PMOD, error: Throwable)
        extends OutcomeEvent

    case class SemanticallyFailedModification[PMOD <: PersistentNodeViewModifier](modifier: PMOD, error: Throwable)
        extends OutcomeEvent

    case class SyntacticallySuccessfulModifier[PMOD <: PersistentNodeViewModifier](modifier: PMOD) extends OutcomeEvent

    case class SemanticallySuccessfulModifier[PMOD <: PersistentNodeViewModifier](modifier: PMOD) extends OutcomeEvent

    case class DownloadRequest(modifierTypeId: ModifierTypeId, modifierId: ModifierId) extends Event

    case object RollbackFailed extends Event
  }

  def apply(
    appSettings:            AppSettings,
    initialState:           () => Future[NodeView]
  )(implicit networkPrefix: NetworkPrefix, timeProvider: TimeProvider): Behavior[ReceivableMessage] =
    Behaviors.setup { context =>
      context.pipeToSelf(initialState())(
        _.fold(ReceivableMessages.InitializationFailed, ReceivableMessages.Initialized)
      )
      uninitialized(appSettings.network.maxModifiersCacheSize)
    }

  final private val UninitializedStashSize = 150

  implicit private val blockOrdering: Ordering[Block] = (a, b) => a.height.compareTo(b.height)

  implicit private val blockShow: Show[Block] = block => s"blockId=${block.id}"

  /**
   * The starting state of a NodeViewHolder actor.  It does not have a NodeView yet, so something in the background should
   * be fetching a NodeView and forwarding it to this uninitialized state.
   * @return A Behavior that is uninitialized
   */
  private def uninitialized(cacheSize: Int)(implicit
    networkPrefix:                     NetworkPrefix,
    timeProvider:                      TimeProvider
  ): Behavior[ReceivableMessage] =
    Behaviors.withStash(UninitializedStashSize)(stash =>
      Behaviors.receivePartial {
        case (context, ReceivableMessages.Initialized(nodeView)) =>
          implicit val system: ActorSystem[_] = context.system
          val cache =
            context.spawn(SortedCache(cacheSize, itemPopLimit = cacheSize), "NodeViewModifiersCache")

          Receptionist(system).ref.tell(Receptionist.Register(serviceKey, context.self))
          system.eventStream.tell(
            EventStream.Subscribe[LocallyGeneratedBlock](
              context.messageAdapter(locallyGeneratedModifier =>
                ReceivableMessages.WriteBlocks(List(locallyGeneratedModifier.block))
              )
            )
          )

          popBlock(cache, nodeView)(context)
          context.log.info("Initialization complete")
          stash.unstashAll(initialized(nodeView, cache))
        case (_, ReceivableMessages.InitializationFailed(reason)) =>
          throw reason
        case (_, message) =>
          stash.stash(message)
          Behaviors.same
      }
    )

  /**
   * The state where the NodeView is ready for use
   */
  private def initialized(
    nodeView:               NodeView,
    cache:                  ActorRef[SortedCache.ReceivableMessage[Block]]
  )(implicit networkPrefix: NetworkPrefix, timeProvider: TimeProvider): Behavior[ReceivableMessage] =
    Behaviors
      .receivePartial[ReceivableMessage] {
        case (_, r: ReceivableMessages.Read[_]) =>
          r.run(nodeView.readableNodeView)
          Behaviors.same

        case (_, ReceivableMessages.WriteBlocks(blocks)) =>
          cache.tell(SortedCache.ReceivableMessages.Insert(blocks))
          Behaviors.same

        case (context, ReceivableMessages.WriteBlock(block)) =>
          implicit def system: ActorSystem[_] = context.system
          // TODO: Exception handling
          // TODO: Should we hold onto the block in the cache?
          // TODO: Ban-list bad blocks?
          val newNodeView = eventStreamWriterHandler(nodeView.withBlock(block))
          popBlock(cache, nodeView)(context)
          initialized(newNodeView, cache)

        case (context, ReceivableMessages.WriteTransactions(transactions)) =>
          implicit def system: ActorSystem[_] = context.system
          val newNodeView = eventStreamWriterHandler(nodeView.withTransactions(transactions))
          initialized(newNodeView, cache)

        case (context, ReceivableMessages.EliminateTransactions(transactionIds)) =>
          implicit def system: ActorSystem[_] = context.system
          val newNodeView = eventStreamWriterHandler(nodeView.withoutTransactions(transactionIds.toSeq))
          initialized(newNodeView, cache)

        case (_, ReceivableMessages.GetWritableNodeView(replyTo)) =>
          replyTo.tell(nodeView)
          Behaviors.same

        case (_, ReceivableMessages.ModifyNodeView(f, replyTo)) =>
          replyTo.tell(Done)
          initialized(f(nodeView), cache)
      }
      .receiveSignal { case (_, PostStop) =>
        nodeView.close()
        Behaviors.same
      }

  private def eventStreamWriterHandler[T](writer: Writer[List[Any], T])(implicit system: ActorSystem[_]): T = {
    val (changes, t) = writer.run
    changes.map(EventStream.Publish(_)).foreach(system.eventStream.tell)
    t
  }

  /**
   * Ask the cache actor for the next viable block to apply.  The cache will _eventually_ reply with the block
   */
  private def popBlock(cache: ActorRef[SortedCache.ReceivableMessage[Block]], nodeView: NodeView)(implicit
    context:                  ActorContext[ReceivableMessage]
  ): Unit =
    cache.tell(
      SortedCache.ReceivableMessages.Pop(
        nodeView.history.extendsKnownTine,
        context.messageAdapter[Block](ReceivableMessages.WriteBlock)
      )
    )
}

trait NodeViewReader {
  def withNodeView[T](f: ReadableNodeView => T): EitherT[Future, NodeViewHolderInterface.ReadFailure, T]
}

/**
 * A generic interface for interacting with a NodeView
 */
trait NodeViewHolderInterface extends NodeViewReader {
  def applyBlocks(blocks:   Iterable[Block]): EitherT[Future, NodeViewHolderInterface.ApplyFailure, Done]
  def applyTransactions(tx: Transaction.TX): EitherT[Future, NodeViewHolderInterface.ApplyFailure, Done]

  def unapplyTransactions(
    transactionIds: Iterable[ModifierId]
  ): EitherT[Future, NodeViewHolderInterface.UnapplyFailure, Done]
  def onReady(): Future[Done]
}

object NodeViewHolderInterface {
  case class ReadFailure(reason: Throwable)
  case class ApplyFailure(reason: Throwable)
  case class UnapplyFailure(reason: Throwable)
}

/**
 * A NodeViewHolderInterface that waits for a NodeViewHolder#serviceKey is available in the Receptionist
 * @param system An ActorSystem in which a NodeViewHolder actor will be initialized
 */
class DelayedNodeViewHolderInterface(implicit system: ActorSystem[_]) extends NodeViewHolderInterface {
  import akka.actor.typed.scaladsl.AskPattern._
  import system.executionContext

  private val refPromise: Promise[NodeViewHolderInterface] = {
    import scala.concurrent.duration._
    implicit val timeout: Timeout = Timeout(2.seconds)
    Promise().completeWith(
      system.receptionist
        .ask[Receptionist.Listing](Receptionist.Subscribe(NodeViewHolder.serviceKey, _))
        .map(_.serviceInstances(NodeViewHolder.serviceKey).head)
        .map(new ActorNodeViewHolderInterface(_))
    )
  }

  override def withNodeView[T](f: ReadableNodeView => T): EitherT[Future, NodeViewHolderInterface.ReadFailure, T] =
    EitherT.liftF(refPromise.future).flatMap(_.withNodeView(f))

  override def applyBlocks(blocks: Iterable[Block]): EitherT[Future, NodeViewHolderInterface.ApplyFailure, Done] =
    EitherT.liftF(refPromise.future).flatMap(_.applyBlocks(blocks))

  override def applyTransactions(tx: TX): EitherT[Future, NodeViewHolderInterface.ApplyFailure, Done] =
    EitherT.liftF(refPromise.future).flatMap(_.applyTransactions(tx))

  override def unapplyTransactions(
    transactionIds: Iterable[ModifierId]
  ): EitherT[Future, NodeViewHolderInterface.UnapplyFailure, Done] =
    EitherT.liftF(refPromise.future).flatMap(_.unapplyTransactions(transactionIds))

  override def onReady(): Future[Done] =
    refPromise.future.flatMap(_.onReady())

}

/**
 * A NodeViewHolderInterface that communicates with a NodeViewHolder actor
 */
class ActorNodeViewHolderInterface(actorRef: ActorRef[NodeViewHolder.ReceivableMessage])(implicit
  system:                                    ActorSystem[_],
  timeout:                                   Timeout
) extends NodeViewHolderInterface {
  import akka.actor.typed.scaladsl.AskPattern._
  import cats.implicits._
  import system.executionContext

  override def withNodeView[T](f: ReadableNodeView => T): EitherT[Future, NodeViewHolderInterface.ReadFailure, T] =
    EitherT(
      actorRef
        .askWithStatus[T](NodeViewHolder.ReceivableMessages.Read(f, _))
        .map(Right(_))
        .recover { case e => Left(NodeViewHolderInterface.ReadFailure(e)) }
    )

  override def applyTransactions(tx: Transaction.TX): EitherT[Future, NodeViewHolderInterface.ApplyFailure, Done] =
    EitherT.pure[Future, NodeViewHolderInterface.ApplyFailure] {
      actorRef
        .tell(NodeViewHolder.ReceivableMessages.WriteTransactions(List(tx)))
      Done
    }

  override def applyBlocks(blocks: Iterable[Block]): EitherT[Future, NodeViewHolderInterface.ApplyFailure, Done] =
    EitherT.pure[Future, NodeViewHolderInterface.ApplyFailure] {
      actorRef
        .tell(NodeViewHolder.ReceivableMessages.WriteBlocks(blocks))
      Done
    }

  override def unapplyTransactions(
    transactionIds: Iterable[ModifierId]
  ): EitherT[Future, NodeViewHolderInterface.UnapplyFailure, Done] =
    EitherT.pure[Future, NodeViewHolderInterface.UnapplyFailure] {
      actorRef
        .tell(NodeViewHolder.ReceivableMessages.EliminateTransactions(transactionIds))
      Done
    }

  override def onReady(): Future[Done] = {
    import scala.concurrent.duration._
    implicit val timeout: Timeout = Timeout(10.minutes)
    actorRef
      .askWithStatus[Done](NodeViewHolder.ReceivableMessages.Read(_ => Done, _))
  }
}
