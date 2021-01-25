package example

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.{HttpEntity, HttpMethods, HttpRequest, MediaTypes}
import akka.http.scaladsl.server.Route
import akka.util.{ByteString, Timeout}
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import http.{GjallarhornBifrostApiRoute, GjallarhornOnlyApiRoute, HttpService}
import akka.http.scaladsl.testkit.ScalatestRouteTest
import attestation.Address
import attestation.AddressEncoder.NetworkPrefix
import crypto.AssetCode
import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax.EncoderOps
import keymanager.KeyManager.{GenerateKeyFile, GetAllKeyfiles}
import keymanager.{Bip39, KeyManagerRef}
import requests.{ApiRoute, Requests}
import wallet.WalletManager

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.reflect.io.Path
import scala.util.{Failure, Success, Try}

/**
  * Must be running bifrost with --local and --seed test
  * ex: "run --local --seed test -f"
  */
class GjallarhornRPCSpec extends AsyncFlatSpec
  with Matchers
  with GjallarhornGenerators
  with ScalatestRouteTest {

//  implicit val materializer: ActorMaterializer = ActorMaterializer()

  implicit val timeout: Timeout = Timeout(10.seconds)
  /**
    * Make sure running bifrost in local network!
    */
  implicit val networkPrefix: NetworkPrefix = 48.toByte

  override def createActorSystem(): ActorSystem = ActorSystem("gjallarhornTest", config)

  val keyFileDir: String = settings.application.keyFileDir
  val path: Path = Path(keyFileDir)
  Try(path.deleteRecursively())
  Try(path.createDirectory())
  val keyManagerRef: ActorRef = KeyManagerRef("keyManager", settings.application)

  val pk1: Address = Await.result((keyManagerRef ? GenerateKeyFile("password", Some("test")))
    .mapTo[Try[Address]], 10.seconds) match {
    case Success(pubKey) => pubKey
    case Failure(ex) => throw new Error(s"An error occurred while creating a new keyfile. $ex")
  }

  val pk2: Address = Await.result((keyManagerRef ? GenerateKeyFile("password2", None))
    .mapTo[Try[Address]], 10.seconds) match {
    case Success(pubKey) => pubKey
    case Failure(ex) => throw new Error(s"An error occurred while creating a new keyfile. $ex")
  }

  val walletManagerRef: ActorRef = system.actorOf(
    Props(new WalletManager(keyManagerRef)), name = WalletManager.actorName)

  val amount = 10

  val requests: Requests = new Requests(settings.application, keyManagerRef)
  val bifrostApiRoute: ApiRoute = GjallarhornBifrostApiRoute(settings, keyManagerRef, walletManagerRef, requests)
  val gjalOnlyApiRoute: ApiRoute = GjallarhornOnlyApiRoute(settings, keyManagerRef, walletManagerRef, requests)
  val route: Route = HttpService(
    Seq(bifrostApiRoute, gjalOnlyApiRoute), settings.rpcApi).compositeRoute


  def httpPOST(jsonRequest: ByteString): HttpRequest = {
    HttpRequest(
      HttpMethods.POST,
      uri = "/",
      entity = HttpEntity(MediaTypes.`application/json`, jsonRequest)
    ).withHeaders(RawHeader("x-api-key", "test_key"))
  }

  var prototypeTx: Json = Map("txType" -> "AssetCreation").asJson
  var msgToSign = ""

  it should "successfully connect to Bifrost" in {
    val connectRequest = ByteString(
      s"""
         |{
         |   "jsonrpc": "2.0",
         |   "id": "2",
         |   "method": "onlineWallet_connectToBifrost",
         |   "params": [{
         |      "chainProvider": "${settings.application.chainProvider}"
         |   }]
         |}
         """.stripMargin)

    httpPOST(connectRequest) ~> route ~> check {
      val responseString = responseAs[String].replace("\\", "")
      parse(responseString.replace("\"{", "{").replace("}\"", "}")) match {
        case Left(f) => throw f
        case Right(res: Json) =>
          (res \\ "error").isEmpty shouldBe true
          (res \\ "result").head.asObject.isDefined shouldBe true
          ((res \\ "result").head \\ "connectedToBifrost").head.asBoolean.get shouldBe true
      }
    }
  }

  val assetCode: AssetCode = AssetCode(1.toByte, pk1, "test")
  it should "succesfully create an asset" in {
    val createAssetRequest = ByteString(
      s"""
         |{
         |   "jsonrpc": "2.0",
         |   "id": "2",
         |   "method": "wallet_createRawTransaction",
         |   "params": [{
         |        "txType": "AssetTransfer",
         |        "propositionType": "PublicKeyCurve25519",
         |        "recipients": [["$pk1", $amount]],
         |        "issuer": "$pk1",
         |        "shortName": "test",
         |        "sender": ["$pk1"],
         |        "changeAddress": "$pk1",
         |        "minting": true,
         |        "fee": 1,
         |        "online": false
         |     }]
         |}
       """.stripMargin)

    httpPOST(createAssetRequest) ~> route ~> check {
      val responseString = responseAs[String].replace("\\", "")
      parse(responseString.replace("\"{", "{").replace("}\"", "}")) match {
        case Left(f) => throw f
        case Right(res: Json) =>
          (res \\ "error").isEmpty shouldBe true
          prototypeTx = (res \\ "rawTx").head
          msgToSign = (res \\ "messageToSign").head.asString.get
          ((res \\ "result").head \\ "rawTx").head.asObject.isDefined shouldBe true
      }
    }
  }

  var signedTx: Json = Json.Null

  it should "successfully sign a transaction" in {
    val signTxRequest = ByteString(
      s"""
         |{
         |   "jsonrpc": "2.0",
         |   "id": "2",
         |   "method": "wallet_signTx",
         |   "params": [{
         |      "signingKeys": ["$pk1"],
         |      "rawTx": $prototypeTx,
         |      "messageToSign": "$msgToSign"
         |   }]
         |}
         """.stripMargin)

    httpPOST(signTxRequest) ~> route ~> check {
      val responseString = responseAs[String].replace("\\", "")
      parse(responseString.replace("\"{", "{").replace("}\"", "}")) match {
        case Left(f) => throw f
        case Right(res: Json) =>
          (res \\ "error").isEmpty shouldBe true
          signedTx = ((res \\ "result").head \\ "tx").head
          (res \\ "result").head.asObject.isDefined shouldBe true
      }
    }
  }

  val emptyTx: Null = null

  it should "successfully generate a signature" in {
    val signRequest = ByteString(
      s"""
         |{
         |   "jsonrpc": "2.0",
         |   "id": "2",
         |   "method": "wallet_signTx",
         |   "params": [{
         |      "signingKeys": ["$pk2"],
         |      "rawTx": $emptyTx,
         |      "messageToSign": "$msgToSign"
         |   }]
         |}
         """.stripMargin)

    httpPOST(signRequest) ~> route ~> check {
      val responseString = responseAs[String].replace("\\", "")
      parse(responseString.replace("\"{", "{").replace("}\"", "}")) match {
        case Left(f) => throw f
        case Right(res: Json) =>
          assert((res \\ "error").isEmpty)
          assert((res \\ "result").head.asObject.isDefined)
          assert(((res \\ "result").head \\ "signatures").head.asObject.isDefined)
      }
    }
  }

  it should "successfully broadcast a tx" in {
    val rqstString =
      s"""
         |{
         |   "jsonrpc": "2.0",
         |   "id": "2",
         |   "method": "onlineWallet_broadcastTx",
         |   "params": [{
         |      "method": "topl_broadcastTx",
         |      "params": [{
         |        "tx": $signedTx
         |      }]
         |   }]
         |}
         """.stripMargin
    val rqst = ByteString(rqstString)
    httpPOST(rqst) ~> route ~> check {
      val responseString = responseAs[String].replace("\\", "")
      parse(responseString.replace("\"{", "{").replace("}\"", "}")) match {
        case Left(f) => throw f
        case Right(res: Json) =>
          (res \\ "error").isEmpty shouldBe true
          (res \\ "result").head.asObject.isDefined shouldBe true
      }
    }
  }

  it should "succesfully create online arbit tx" in {
    Thread.sleep(10000)
    val createAssetRequest = ByteString(
      s"""
         |{
         |   "jsonrpc": "2.0",
         |   "id": "2",
         |   "method": "wallet_createRawTransaction",
         |     "params": [{
         |        "txType": "ArbitTransfer",
         |        "propositionType": "PublicKeyCurve25519",
         |        "recipients": [["$pk2", $amount]],
         |        "sender": ["$pk1"],
         |        "changeAddress": "$pk1",
         |        "fee": 1,
         |        "data": "",
         |        "online": true
         |     }]
         |}
       """.stripMargin)

    httpPOST(createAssetRequest) ~> route ~> check {
      val responseString = responseAs[String].replace("\\", "")
      parse(responseString.replace("\"{", "{").replace("}\"", "}")) match {
        case Left(f) => throw f
        case Right(res: Json) =>
          (res \\ "error").isEmpty shouldBe true
          (res \\ "result").head.asObject.isDefined shouldBe true
      }
    }
  }

  it should "successfully create raw poly tx" in {
    val createPolyRequest = ByteString(
      s"""
         |{
         |   "jsonrpc": "2.0",
         |   "id": "2",
         |   "method": "wallet_createRawTransaction",
         |   "params": [{
         |        "txType": "PolyTransfer",
         |        "propositionType": "PublicKeyCurve25519",
         |        "sender": ["$pk1"],
         |        "recipients": [["$pk2", $amount]],
         |        "changeAddress": "$pk1",
         |        "fee": 1,
         |        "data": "",
         |        "online": false
         |     }]
         |}
       """.stripMargin)

    httpPOST(createPolyRequest) ~> route ~> check {
      val responseString = responseAs[String].replace("\\", "")
      parse(responseString.replace("\"{", "{").replace("}\"", "}")) match {
        case Left(f) => throw f
        case Right(res: Json) =>
          (res \\ "error").isEmpty shouldBe true
          println("with bifrost: " + (res \\ "result").head)
          (res \\ "result").head.asObject.isDefined shouldBe true
      }
    }
  }

  it should "successfully send online poly tx" in {
    Thread.sleep(10000)
    val createPolyRequest = ByteString(
      s"""
         |{
         |   "jsonrpc": "2.0",
         |   "id": "2",
         |   "method": "wallet_createRawTransaction",
         |   "params": [{
         |        "txType": "PolyTransfer",
         |        "propositionType": "PublicKeyCurve25519",
         |        "sender": ["$pk1"],
         |        "recipients": [["$pk2", $amount]],
         |        "changeAddress": "$pk1",
         |        "fee": 1,
         |        "data": "",
         |        "online": true
         |     }]
         |}
       """.stripMargin)

    httpPOST(createPolyRequest) ~> route ~> check {
      val responseString = responseAs[String].replace("\\", "")
      parse(responseString.replace("\"{", "{").replace("}\"", "}")) match {
        case Left(f) => throw f
        case Right(res: Json) =>
          (res \\ "error").isEmpty shouldBe true
          (res \\ "result").head.asObject.isDefined shouldBe true
      }
    }
  }

  it should "get a successful JSON response from balance request" in {
    Thread.sleep(10000)
    val requestBody = ByteString(
      s"""
         |{
         |   "jsonrpc": "2.0",
         |   "id": "1",
         |   "method": "onlineWallet_balances",
         |   "params": [{}]
         |}
      """.stripMargin)

      httpPOST(requestBody) ~> route ~> check {
        val responseString = responseAs[String].replace("\\", "")
        parse(responseString.replace("\"{", "{").replace("}\"", "}")) match {
          case Left(f) => throw f
          case Right(res: Json) =>
            (res \\ "error").isEmpty shouldBe true

            println("balance: " + (res \\ "result").head)

            //pk1 should have fewer polys now
            (((res \\ "result").head \\ pk1.toString).head \\ "PolyBox").head.asNumber.get.toLong match {
              case Some(number) => assert(number < 1000000)
              case None => throw new Error ("balance is not a long")
            }

            //pk1 should have $amount of new asset
            (((res \\ "result").head \\ pk1.toString).head \\ assetCode.toString).head.asNumber.get.toLong match {
              case Some(number) => assert(number == amount)
              case None => throw new Error ("balance is not a long")
            }

            //pk2 should have $amount poly
            (((res \\ "result").head \\ pk2.toString).head \\ "PolyBox").head.asNumber.get.toLong match {
              case Some(number) => assert(number == amount)
              case None => throw new Error ("balance is not a long")
            }

            //pk2 should have $amount arbit
            (((res \\ "result").head \\ pk2.toString).head \\ "ArbitBox").head.asNumber.get.toLong match {
              case Some(number) => assert(number == amount)
              case None => throw new Error ("balance is not a long")
            }

            (res \\ "result").head.asObject.isDefined shouldBe true
        }
      }
    }

  it should "successfully get wallet boxes" in {
    val mnemonicPhraseRequest = ByteString(
      s"""
         |{
         |   "jsonrpc": "2.0",
         |   "id": "2",
         |   "method": "onlineWallet_getWalletBoxes",
         |   "params": [{}]
         |}
         """.stripMargin)

    httpPOST(mnemonicPhraseRequest) ~> route ~> check {
      val responseString = responseAs[String].replace("\\", "")
      parse(responseString.replace("\"\"", "\"")) match {
        case Left(f) => throw f
        case Right(res: Json) =>
          (res \\ "error").isEmpty shouldBe true
          val phrase = (res \\ "result").head
          assert (phrase != null)
      }
    }
  }

  var newAddr: Address = pk2

  it should "successfuly generate a new key and send poly" in {
    val phraseTranslator = Bip39.apply("en")
    val seed = phraseTranslator.uuidSeedPhrase(java.util.UUID.randomUUID.toString)._1
    newAddr = Await.result((keyManagerRef ? GenerateKeyFile("password3", Some(seed)))
      .mapTo[Try[Address]], 12.seconds) match {
        case Success(pubKey) => pubKey
        case Failure(exception) => throw new Error("error creating key file: " + exception)
      }
    val createPolyRequest = ByteString(
      s"""
         |{
         |   "jsonrpc": "2.0",
         |   "id": "2",
         |   "method": "wallet_createRawTransaction",
         |     "params": [{
         |        "txType": "PolyTransfer",
         |        "propositionType": "PublicKeyCurve25519",
         |        "sender": ["$pk1"],
         |        "recipients": [["$newAddr", 15]],
         |        "changeAddress": "$pk1",
         |        "fee": 1,
         |        "data": "",
         |        "online": true
         |     }]
         |}
       """.stripMargin)

    httpPOST(createPolyRequest) ~> route ~> check {
      val responseString = responseAs[String].replace("\\", "")
      parse(responseString.replace("\"{", "{").replace("}\"", "}")) match {
        case Left(f) => throw f
        case Right(res: Json) =>
          (res \\ "error").isEmpty shouldBe true
          (res \\ "result").head.asObject.isDefined shouldBe true
      }
    }
  }

  it should "successfully update balance for new key" in {
    Thread.sleep(10000)
    val requestBody = ByteString(
      s"""
         |{
         |   "jsonrpc": "2.0",
         |   "id": "1",
         |   "method": "onlineWallet_balances",
         |   "params": [{}]
         |}
      """.stripMargin)

    httpPOST(requestBody) ~> route ~> check {
      val responseString = responseAs[String].replace("\\", "")
      parse(responseString.replace("\"{", "{").replace("}\"", "}")) match {
        case Left(f) => throw f
        case Right(res: Json) =>
          (res \\ "error").isEmpty shouldBe true
          (res \\ "result").head.asObject.isDefined shouldBe true
          (((res \\ "result").head \\ newAddr.toString).head \\ "PolyBox").head shouldBe 15.asJson
      }
    }
  }

  var rawPolyTx: Json = Json.Null
  var msgToSignPoly: String = ""
  it should "succesfully create a raw poly tx without bifrost" in {
    val createAssetRequest = ByteString(
      s"""
         |{
         |   "jsonrpc": "2.0",
         |   "id": "2",
         |   "method": "wallet_createRawTransaction",
         |   "params": [{
         |        "txType": "PolyTransfer",
         |        "propositionType": "PublicKeyCurve25519",
         |        "sender": ["$pk1"],
         |        "recipients": [["$pk1", $amount]],
         |        "changeAddress": "$pk1",
         |        "fee": 1,
         |        "online": false
         |   }]
         |}
       """.stripMargin)

    httpPOST(createAssetRequest) ~> route ~> check {
      val responseString = responseAs[String].replace("\\", "")
      parse(responseString.replace("\"{", "{").replace("}\"", "}")) match {
        case Left(f) => throw f
        case Right(res: Json) =>
          assert((res \\ "error").isEmpty)
          rawPolyTx = ((res \\ "result").head \\ "rawTx").head
          msgToSignPoly = ((res \\ "result").head \\ "messageToSign").head.asString.get
          println("without bifrost: " + (res \\ "result").head)
          assert(((res \\ "result").head \\ "rawTx").head.asObject.isDefined)
      }
    }
  }

  var signedPolyTx: Json = Json.Null

  it should "successfully sign a transaction created by gjal" in {
    val signTxRequest = ByteString(
      s"""
         |{
         |   "jsonrpc": "2.0",
         |   "id": "2",
         |   "method": "wallet_signTx",
         |   "params": [{
         |      "signingKeys": ["$pk1"],
         |      "rawTx": $rawPolyTx,
         |      "messageToSign": "$msgToSignPoly"
         |   }]
         |}
         """.stripMargin)

    httpPOST(signTxRequest) ~> route ~> check {
      val responseString = responseAs[String].replace("\\", "")
      parse(responseString.replace("\"{", "{").replace("}\"", "}")) match {
        case Left(f) => throw f
        case Right(res: Json) =>
          (res \\ "error").isEmpty shouldBe true
          signedPolyTx = ((res \\ "result").head \\ "tx").head
          (res \\ "result").head.asObject.isDefined shouldBe true
      }
    }
  }

  it should "successfully broadcast a tx generated by gjal" in {
    val rqstString =
      s"""
         |{
         |   "jsonrpc": "2.0",
         |   "id": "2",
         |   "method": "onlineWallet_broadcastTx",
         |   "params": [{
         |      "method": "topl_broadcastTx",
         |      "params": [{
         |        "tx": $signedPolyTx
         |      }]
         |   }]
         |}
         """.stripMargin
    val rqst = ByteString(rqstString)
    httpPOST(rqst) ~> route ~> check {
      val responseString = responseAs[String].replace("\\", "")
      parse(responseString.replace("\"{", "{").replace("}\"", "}")) match {
        case Left(f) => throw f
        case Right(res: Json) =>
          (res \\ "error").isEmpty shouldBe true
          (res \\ "result").head.asObject.isDefined shouldBe true
      }
    }
  }

 it should "successfully disconnect from Bifrost" in {
    val disconnectRequest = ByteString(
      s"""
         |{
         |   "jsonrpc": "2.0",
         |   "id": "2",
         |   "method": "onlineWallet_disconnectFromBifrost",
         |   "params": [{}]
         |}
         """.stripMargin)

    httpPOST(disconnectRequest) ~> route ~> check {
      val responseString = responseAs[String].replace("\\", "")
      parse(responseString.replace("\"{", "{").replace("}\"", "}")) match {
        case Left(f) => throw f
        case Right(res: Json) =>
          (res \\ "error").isEmpty shouldBe true
          (res \\ "result").head.asObject.isDefined shouldBe true
          ((res \\ "result").head \\ "status").head.asString.get === "Disconnected!" shouldBe true
      }
    }
  }

  it should "successfully get connection status" in {
    val mnemonicPhraseRequest = ByteString(
      s"""
         |{
         |   "jsonrpc": "2.0",
         |   "id": "2",
         |   "method": "onlineWallet_getConnection",
         |   "params": [{}]
         |}
         """.stripMargin)

    httpPOST(mnemonicPhraseRequest) ~> route ~> check {
      val responseString = responseAs[String].replace("\\", "")
      parse(responseString.replace("\"\"", "\"")) match {
        case Left(f) => throw f
        case Right(res: Json) =>
          (res \\ "error").isEmpty shouldBe true
          (res \\ "result").head.asObject.isDefined shouldBe true
          ((res \\ "result").head \\ "connectedToBifrost").head.asBoolean.get shouldBe false
      }
    }
  }

  it should "successfully get network prefix" in {
    val networkTypeRequest = ByteString(
      s"""
         |{
         |   "jsonrpc": "2.0",
         |   "id": "2",
         |   "method": "wallet_networkType",
         |   "params": [{}]
         |}
         """.stripMargin)

    httpPOST(networkTypeRequest) ~> route ~> check {
      val responseString = responseAs[String].replace("\\", "")
      parse(responseString.replace("\"{", "{").replace("}\"", "}")) match {
        case Left(f) => throw f
        case Right(res: Json) =>
          (res \\ "error").isEmpty shouldBe true
          val network = ((res \\ "result").head \\ "networkPrefix").head
          assert(network.toString() === networkPrefix.toString)
      }
    }
  }

  it should "successfully change the network" in {
    val networkTypeRequest = ByteString(
      s"""
         |{
         |   "jsonrpc": "2.0",
         |   "id": "2",
         |   "method": "wallet_changeNetwork",
         |   "params": [{
         |      "newNetwork": "toplnet"
         |   }]
         |}
         """.stripMargin)

    httpPOST(networkTypeRequest) ~> route ~> check {
      val responseString = responseAs[String].replace("\\", "")
      parse(responseString.replace("\"{", "{").replace("}\"", "}")) match {
        case Left(f) => throw f
        case Right(res: Json) =>
          (res \\ "error").isEmpty shouldBe true
          val network = ((res \\ "result").head \\ "newNetworkPrefix").head
          assert(network.toString() === "1")
      }
    }
  }

  it should "still have keys after disconnecting from bifrost and changing network back to local" in {
    val networkTypeRequest = ByteString(
      s"""
         |{
         |   "jsonrpc": "2.0",
         |   "id": "2",
         |   "method": "wallet_changeNetwork",
         |   "params": [{
         |      "newNetwork": "local"
         |   }]
         |}
         """.stripMargin)

    httpPOST(networkTypeRequest) ~> route ~> check {
      val responseString = responseAs[String].replace("\\", "")
      parse(responseString.replace("\"{", "{").replace("}\"", "}")) match {
        case Left(f) => throw f
        case Right(res: Json) =>
          (res \\ "error").isEmpty shouldBe true
          val network = ((res \\ "result").head \\ "newNetworkPrefix").head
          val keyfiles: Map[Address, String] = Await.result((keyManagerRef ? GetAllKeyfiles)
            .mapTo[Map[Address,String]], 10.seconds)
          keyfiles.keySet.size shouldBe 3
          assert(network.toString() === "48")
      }
    }
  }

}