package co.topl.mempool

import akka.actor.typed._
import akka.actor.typed.eventstream.EventStream
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.Behaviors
import akka.util.Timeout
import co.topl.modifier.block.Block
import co.topl.modifier.transaction.Transaction
import co.topl.network.message.BifrostSyncInfo
import co.topl.network.utils.NetworkTimeProvider
import co.topl.nodeView.NodeViewHolder
import co.topl.nodeView.history.HistoryReader
import co.topl.nodeView.mempool.MemPoolReader
import co.topl.utils.{NodeGenerators, TimeProvider}
import org.scalatest.matchers.must.Matchers
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper
import org.scalatest.propspec.AnyPropSpec
import org.scalatest.{BeforeAndAfterAll, DoNotDiscover}

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

@DoNotDiscover
class MempoolSpec extends AnyPropSpec with Matchers with NodeGenerators with BeforeAndAfterAll {

  implicit private val actorSystem: ActorSystem[_] = ActorSystem(Behaviors.empty, settings.network.agentName)

  implicit private val timeProvider: TimeProvider = new NetworkTimeProvider(settings.ntp)

  private val nodeViewHolderRef =
    actorSystem.systemActorOf(
      NodeViewHolder(
        settings,
        () => ???
      ),
      NodeViewHolder.ActorName
    )

  implicit val timeout: Timeout = Timeout(10.seconds)

  private def getHistory: HistoryReader[Block, BifrostSyncInfo] = Await.result(
    nodeViewHolderRef.askWithStatus[HistoryReader[Block, BifrostSyncInfo]](
      NodeViewHolder.ReceivableMessages.Read(_.history, _)
    ),
    10.seconds
  )

  private def getMempool: MemPoolReader[Transaction.TX] = Await.result(
    nodeViewHolderRef
      .askWithStatus[MemPoolReader[Transaction.TX]](NodeViewHolder.ReceivableMessages.Read(_.memPool, _)),
    10.seconds
  )

  property(
    "Repeated transactions already in history should be discarded " +
    "when received by the node view"
  ) {
    val txs = getHistory.bestBlock.transactions

    txs.foreach(tx => actorSystem.eventStream.tell(EventStream.Publish(???)))
    txs.foreach(tx => getMempool.contains(tx) shouldBe false)
  }

  override protected def afterAll(): Unit =
    actorSystem.terminate()
}
