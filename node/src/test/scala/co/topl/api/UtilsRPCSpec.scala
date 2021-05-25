package co.topl.api

import akka.util.ByteString
import co.topl.akkahttprpc.InvalidParametersError
import co.topl.attestation.Address
import co.topl.crypto.hash.blake2b256
import co.topl.crypto.hash.implicits._
import co.topl.modifier.box.AssetCode
import co.topl.rpc.ToplRpcErrors
import co.topl.utils.codecs.CryptoCodec.implicits._
import co.topl.utils.encode.Base58
import io.circe.Json
import io.circe.parser.parse
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.util.{Failure, Success}

class UtilsRPCSpec extends AnyWordSpec with Matchers with RPCMockState with EitherValues {

  val seedLength: Int = 10
  var address: Address = _

  override def beforeAll(): Unit = {
    super.beforeAll()
    address = keyRing.addresses.head
  }

  "Utils RPC" should {
    "Generate random seed" in {
      val requestBody = ByteString(s"""
           |{
           |   "jsonrpc": "2.0",
           |   "id": "1",
           |   "method": "util_seed",
           |   "params": [{}]
           |}
        """.stripMargin)

      httpPOST(requestBody) ~> route ~> check {
        val res: Json = parse(responseAs[String]).value

        val seedString: String = res.hcursor.downField("result").get[String]("seed").value

        res.hcursor.downField("error").values shouldBe None

        Base58.decode(seedString) match {
          case Success(seed) => seed.length shouldEqual 32
          case Failure(_)    => fail("Could not Base 58 decode seed output")
        }
      }
    }

    "Generate random of given length" in {
      val requestBody = ByteString(s"""
           |{
           |   "jsonrpc": "2.0",
           |   "id": "1",
           |   "method": "util_seedOfLength",
           |   "params": [{
           |      "length": $seedLength
           |   }]
           |}
      """.stripMargin)

      httpPOST(requestBody) ~> route ~> check {
        val res: Json = parse(responseAs[String]).value

        val seedString: String = res.hcursor.downField("result").get[String]("seed").value

        res.hcursor.downField("error").values shouldBe None

        Base58.decode(seedString) match {
          case Success(seed) => seed.length shouldEqual seedLength
          case Failure(_)    => fail("Could not Base 58 decode seed output")
        }
      }
    }

    "Return blake2b hash of given message" in {
      val requestBody = ByteString(s"""
           |{
           |   "jsonrpc": "2.0",
           |   "id": "1",
           |   "method": "util_hashBlake2b256",
           |   "params": [{
           |      "message": "Hello World"
           |   }]
           |}
      """.stripMargin)

      httpPOST(requestBody) ~> route ~> check {
        val res: Json = parse(responseAs[String]).value
        val hash = res.hcursor.downField("result").get[String]("hash").value

        hash shouldEqual Base58.encode(blake2b256.hash("Hello World".getBytes))
        res.hcursor.downField("error").values shouldBe None
      }
    }

    "Generate AssetCode with given issuer and shortName" in {
      val requestBody = ByteString(s"""
           |{
           |   "jsonrpc": "2.0",
           |   "id": "1",
           |   "method": "util_generateAssetCode",
           |   "params": [{
           |      "version": 1,
           |      "issuer": "$address",
           |      "shortName": "testcode"
           |   }]
           |}
      """.stripMargin)

      httpPOST(requestBody) ~> route ~> check {
        val res: Json = parse(responseAs[String]).value
        val oldAssetCode: AssetCode = AssetCode(1: Byte, address, "testcode")

        val genAssetCode: AssetCode = res.hcursor.downField("result").get[AssetCode]("assetCode").value

        res.hcursor.downField("error").values shouldBe None
        oldAssetCode.toString shouldEqual genAssetCode.toString
      }
    }

    "Return the same address and network if the given address and network type are valid and matching" in {
      val requestBody = ByteString(s"""
           |{
           |   "jsonrpc": "2.0",
           |   "id": "1",
           |   "method": "util_checkValidAddress",
           |   "params": [{
           |      "network": "private",
           |      "address": "$address"
           |   }]
           |}
      """.stripMargin)

      httpPOST(requestBody) ~> route ~> check {
        val res: Json = parse(responseAs[String]).value
        val resAddress = res.hcursor.downField("result").get[Address]("address").value
        val network = res.hcursor.downField("result").get[String]("network").value

        res.hcursor.downField("error").values shouldBe None
        network shouldEqual "private"
        resAddress shouldEqual address
      }
    }

    "Returns the address and the current network type, which should be private for tests, if only address is given" in {
      val requestBody = ByteString(s"""
           |{
           |   "jsonrpc": "2.0",
           |   "id": "1",
           |   "method": "util_checkValidAddress",
           |   "params": [{
           |      "address": "$address"
           |   }]
           |}
      """.stripMargin)

      httpPOST(requestBody) ~> route ~> check {
        val res: Json = parse(responseAs[String]).value
        val network = res.hcursor.downField("result").get[String]("network").value
        val resAddress = res.hcursor.downField("result").get[Address]("address").value

        res.hcursor.downField("error").values shouldBe None
        network shouldEqual "private"
        resAddress shouldEqual address
      }
    }

    "Complain that the network type doesn't match if the received address and network type are not matching" in {
      val requestBody = ByteString(s"""
           |{
           |   "jsonrpc": "2.0",
           |   "id": "1",
           |   "method": "util_checkValidAddress",
           |   "params": [{
           |      "network": "toplnet",
           |      "address": "$address"
           |   }]
           |}
      """.stripMargin)

      httpPOST(requestBody) ~> route ~> check {
        val res: Json = parse(responseAs[String]).value
        val code = res.hcursor.downField("error").get[Int]("code").value
        val message = res.hcursor.downField("error").get[String]("message").value

        code shouldEqual InvalidParametersError.Code
        message shouldEqual InvalidParametersError.Message
      }
    }

    "Reject request if the network type doesn't exist" in {
      val requestBody = ByteString(s"""
           |{
           |   "jsonrpc": "2.0",
           |   "id": "1",
           |   "method": "util_checkValidAddress",
           |   "params": [{
           |      "network": "nonexistentnetwork",
           |      "address": "$address"
           |   }]
           |}
      """.stripMargin)

      httpPOST(requestBody) ~> route ~> check {
        val res: Json = parse(responseAs[String]).value
        val code = res.hcursor.downField("error").get[Int]("code").value
        val message = res.hcursor.downField("error").get[String]("message").value

        code shouldEqual ToplRpcErrors.InvalidNetworkSpecified.code
        message shouldEqual ToplRpcErrors.InvalidNetworkSpecified.message
      }
    }
  }
}
