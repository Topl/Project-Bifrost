package co.topl.api.transaction

import akka.util.ByteString
import co.topl.api.RPCMockState
import co.topl.attestation.Address
import co.topl.utils.encode.Base58
import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax._
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class ArbitTransferRPCSpec extends AnyWordSpec with Matchers with RPCMockState with EitherValues {

  var address: Address = _
  var tx = ""

  override def beforeAll(): Unit = {
    super.beforeAll()
    address = keyRing.addresses.head
  }

  "ArbitTransfer RPC" should {
    "Create new arbit transfer raw transaction" in {
      val requestBody = ByteString(s"""
           |{
           |   "jsonrpc": "2.0",
           |   "id": "2",
           |   "method": "topl_rawArbitTransfer",
           |   "params": [{
           |     "propositionType": "PublicKeyEd25519",
           |     "recipients": [["$address", "1"]],
           |     "sender": ["$address"],
           |     "changeAddress": "$address",
           |     "consolidationAddress": "$address",
           |     "minting": "false",
           |     "fee": "1",
           |     "data": ""
           |   }]
           |}
        """.stripMargin)

      httpPOST(requestBody) ~> route ~> check {
        val res = parse(responseAs[String]).value

        val sigTx = for {
          rawTx   <- res.hcursor.downField("result").get[Json]("rawTx")
          message <- res.hcursor.downField("result").get[String]("messageToSign")
        } yield {
          val sig = keyRing.generateAttestation(address)(Base58.decode(message).get)
          val signatures: Json = Map(
            "signatures" -> sig.asJson
          ).asJson
          rawTx.deepMerge(signatures)
        }

        tx = sigTx.value.toString

        (res \\ "error").isEmpty shouldBe true
        (res \\ "result").head.asObject.isDefined shouldBe true
      }
    }

    "Broadcast signed ArbitTransfer transaction" in {
      val requestBody = ByteString(s"""
           |{
           |   "jsonrpc": "2.0",
           |   "id": "2",
           |   "method": "topl_broadcastTx",
           |   "params": [{
           |     "tx": $tx
           |   }]
           |}
           |""".stripMargin)

      httpPOST(requestBody) ~> route ~> check {
        val res = parse(responseAs[String]).value
        (res \\ "error").isEmpty shouldBe true
        (res \\ "result").head.asObject.isDefined shouldBe true
      }
    }
  }
}
