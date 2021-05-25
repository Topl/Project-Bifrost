package co.topl.api.transaction

import akka.util.ByteString
import co.topl.api.RPCMockState
import co.topl.attestation.Address
import co.topl.modifier.box.AssetCode
import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import scorex.util.encode.Base58

class AssetRPCSpec extends AnyWordSpec with Matchers with RPCMockState {

  var address: Address = _
  var recipients: String = _
  var assetCode: AssetCode = _
  var tx = ""

  override def beforeAll(): Unit = {
    super.beforeAll()

    address = keyRing.addresses.head
    recipients = assetToSeqGen.sample.get.asJson.toString()
    assetCode = AssetCode(1: Byte, address, "test")
  }

  "AssetTransfer RPC" should {
    "Create new assets raw transaction" in {
      val requestBody = ByteString(s"""
           |{
           |   "jsonrpc": "2.0",
           |   "id": "2",
           |   "method": "topl_rawAssetTransfer",
           |   "params": [{
           |     "propositionType": "PublicKeyCurve25519",
           |     "recipients":
           |    [
           |  [
           |    "$address",
           |      {
           |        "quantity" : "1",
           |        "assetCode" : "${assetCode.toString}",
           |        "metadata" : "ApdGzs6uwKAhuKJQswBWoVAFjNA5B8enBKfxVbzlcQ8EnpxicpRcE9B9Bgn2LGv02kYUSA1h1181ZYeECvr",
           |        "type" : "Asset",
           |        "securityRoot" : "11111111111111111111111111111111"
           |      }]],
           |     "sender": ["$address"],
           |     "changeAddress": "$address",
           |     "consolidationAddress": "$address",
           |     "fee": "1",
           |     "minting": true,
           |     "data": ""
           |   }]
           |}
        """.stripMargin)

      httpPOST(requestBody) ~> route ~> check {
        val res = parse(responseAs[String]) match { case Right(re) => re; case Left(ex) => throw ex }

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

        tx = sigTx.getOrElse("").toString

        (res \\ "error").isEmpty shouldBe true
        (res \\ "result").head.asObject.isDefined shouldBe true
      }
    }

    "Broadcast signed AssetTransfer transaction" in {
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
        val res = parse(responseAs[String]) match { case Right(re) => re; case Left(ex) => throw ex }
        (res \\ "error").isEmpty shouldBe true
        (res \\ "result").head.asObject.isDefined shouldBe true
      }
    }
  }
}
