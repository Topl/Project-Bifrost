package co.topl.api

import akka.util.ByteString
import co.topl.modifier.block.Block
import co.topl.modifier.transaction.Transaction.TX
import co.topl.nodeView.TestableNodeViewHolder
import co.topl.nodeView.history.History
import io.circe.Json
import io.circe.parser.parse
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class NodeViewRPCSpec extends AnyWordSpec with Matchers with RPCMockState {

  var txs: Seq[TX] = _
  var txId: String = _
  var block: Block = _

  override def beforeAll(): Unit = {
    super.beforeAll()

    txs = bifrostTransactionSeqGen.sample.get
    txId = txs.head.id.toString
    block = blockCurve25519Gen.sample.get.copy(transactions = txs)

    import akka.actor.typed.scaladsl.adapter._
    TestableNodeViewHolder.setNodeView(
      nodeViewHolderRef,
      current =>
        current.copy(history = current.history match {
          case h: History =>
            h.storage.update(block, isBest = true)
            h
        })
    )(system.toTyped)

    view().mempool.putWithoutCheck(txs, block.timestamp)
  }

  "NodeView RPC" should {
    "Get first 100 transactions in mempool" in {
      val requestBody = ByteString(s"""
           |{
           |   "jsonrpc": "2.0",
           |   "id": "1",
           |   "method": "topl_mempool",
           |   "params": [{}]
           |}
          """.stripMargin)

      httpPOST(requestBody) ~> route ~> check {
        val res: Json = parse(responseAs[String]) match { case Right(re) => re; case Left(ex) => throw ex }
        val txIds = (res \\ "result").head.asArray.get.flatMap(txJson => (txJson \\ "txId").head.asString)
        txs.foreach(tx => txIds.contains(tx.id.toString))
      }
    }

    "Get transaction from the mempool by id" in {
      val requestBody = ByteString(s"""
           |{
           |   "jsonrpc": "2.0",
           |   "id": "1",
           |   "method": "topl_transactionFromMempool",
           |   "params": [{
           |      "transactionId": "$txId"
           |   }]
           |}
           |
          """.stripMargin)

      view().mempool.putWithoutCheck(Seq(txs.head), block.timestamp)

      httpPOST(requestBody) ~> route ~> check {
        val res: Json = parse(responseAs[String]) match { case Right(re) => re; case Left(ex) => throw ex }
        ((res \\ "result").head \\ "txId").head.asString.get shouldEqual txId
      }
      view().mempool.remove(txs.head)
    }

    "Get a confirmed transaction by id" in {

      val requestBody = ByteString(s"""
           |{
           |   "jsonrpc": "2.0",
           |   "id": "1",
           |   "method": "topl_transactionById",
           |   "params": [{
           |      "transactionId": "$txId"
           |   }]
           |}
           |
          """.stripMargin)

      httpPOST(requestBody) ~> route ~> check {
        val res: Json = parse(responseAs[String]) match { case Right(re) => re; case Left(ex) => throw ex }
        (res \\ "error").isEmpty shouldBe true
        (res \\ "result").isInstanceOf[List[Json]] shouldBe true
        ((res \\ "result").head \\ "txId").head.asString.get shouldEqual txId
      }
    }

    "Get block by id" in {
      val requestBody = ByteString(s"""
           |{
           |   "jsonrpc": "2.0",
           |
           |   "id": "1",
           |   "method": "topl_blockById",
           |   "params": [{
           |      "blockId": "${block.id}"
           |   }]
           |}
           |
          """.stripMargin)

      httpPOST(requestBody) ~> route ~> check {
        val res: Json = parse(responseAs[String]) match { case Right(re) => re; case Left(ex) => throw ex }
        (res \\ "error").isEmpty shouldBe true
        (res \\ "result").isInstanceOf[List[Json]] shouldBe true
      }
    }
  }
}
