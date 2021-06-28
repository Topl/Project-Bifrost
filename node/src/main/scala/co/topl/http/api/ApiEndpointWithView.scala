package co.topl.http.api

import akka.actor.ActorRef
import akka.pattern.ask
import co.topl.attestation.Address
import co.topl.modifier.block.Block
import co.topl.modifier.box.ProgramId
import co.topl.modifier.transaction.Transaction
import co.topl.network.NodeViewSynchronizer.ReceivableMessages.{ChangedHistory, ChangedMempool, ChangedState}
import co.topl.network.message.BifrostSyncInfo
import co.topl.nodeView.NodeViewHolder.ReceivableMessages.GetNodeViewChanges
import co.topl.nodeView.history.HistoryReader
import co.topl.nodeView.mempool.MemPoolReader
import co.topl.nodeView.state.StateReader
import io.circe.Json

import scala.concurrent.{ExecutionContext, Future}

trait ApiEndpointWithView extends ApiEndpoint {

  val nodeViewHolderRef: ActorRef
  protected def blockingExecutionContext: ExecutionContext

  type HR = HistoryReader[Block, BifrostSyncInfo]
  type SR = StateReader[ProgramId, Address]
  type MR = MemPoolReader[Transaction.TX]

  protected def asyncHistory(f: HR => Json)(implicit ec: ExecutionContext): Future[Json] =
    (nodeViewHolderRef ? GetNodeViewChanges(history = true, state = false, mempool = false))
      .mapTo[ChangedHistory[HR]]
      .flatMap(c => Future(f(c.reader))(blockingExecutionContext))

  protected def asyncState(f: SR => Json)(implicit ec: ExecutionContext): Future[Json] =
    (nodeViewHolderRef ? GetNodeViewChanges(history = false, state = true, mempool = false))
      .mapTo[ChangedState[SR]]
      .flatMap(c => Future(f(c.reader))(blockingExecutionContext))

  protected def asyncMempool(f: MR => Json)(implicit ec: ExecutionContext): Future[Json] =
    (nodeViewHolderRef ? GetNodeViewChanges(history = false, state = false, mempool = true))
      .mapTo[ChangedMempool[MR]]
      .flatMap(c => Future(f(c.reader))(blockingExecutionContext))

  /** Helper function to ensure this node has the appropriate state to create a request raw transaction */
  protected def checkAddress(keys: Seq[Address], sr: SR): Unit = {
    if (!sr.hasTBR)
      throw new Exception("TokenBoxRegistry not defined for node")

    //YT NOTE - if nodeKeys not defined in settings file then node watches for all keys in a state update
    if (sr.nodeKeys.isDefined && !keys.forall(key => sr.nodeKeys.contains(key)))
      throw new Exception("Node not set to watch for specified public key")
  }
}
