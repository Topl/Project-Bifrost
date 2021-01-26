package example

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.http.scaladsl.model.{HttpEntity, HttpMethods, HttpRequest, MediaTypes}
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.util.{ByteString, Timeout}
import attestation.Address
import attestation.AddressEncoder.NetworkPrefix
import http.{GjallarhornOnlyApiRoute, HttpService, KeyManagementApiRoute}
import io.circe.Json
import io.circe.parser.parse
import keymanager.KeyManager.{GenerateKeyFile, GetAllKeyfiles}
import keymanager.KeyManagerRef
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import requests.{ApiRoute, Requests}
import wallet.WalletManager

import scala.concurrent.Await
import scala.util.{Failure, Success, Try}
import scala.concurrent.duration._
import scala.reflect.io.Path

class KeyManagementRPCSpec extends AsyncFlatSpec
  with Matchers
  with ScalatestRouteTest
  with GjallarhornGenerators {

  //Make sure running bifrost in local network!
  implicit val networkPrefix: NetworkPrefix = 48.toByte
  implicit val timeout: Timeout = 10.seconds

  override def createActorSystem(): ActorSystem = ActorSystem("keyManagementTest", keysConfig)

  val keyFileDir: String = keyManagementSettings.application.keyFileDir
  val path: Path = Path(keyFileDir)
  Try(path.deleteRecursively())
  Try(path.createDirectory())
  val keyManagerRef: ActorRef = KeyManagerRef("KeyManager", keyManagementSettings.application)

  val walletManagerRef: ActorRef = system.actorOf(
    Props(new WalletManager(keyManagerRef)), name = WalletManager.actorName)

  val requests: Requests = new Requests(keyManagementSettings.application, keyManagerRef)
  val apiRoute: ApiRoute = KeyManagementApiRoute(keyManagementSettings, keyManagerRef)
  val gjalOnlyApiRoute: ApiRoute =
    GjallarhornOnlyApiRoute(keyManagementSettings, keyManagerRef, walletManagerRef, requests)
  val route: Route = HttpService(Seq(apiRoute, gjalOnlyApiRoute), keyManagementSettings.rpcApi).compositeRoute

  val pk1: Address = Await.result((keyManagerRef ? GenerateKeyFile("password", Some("test")))
    .mapTo[Try[Address]], 10.seconds) match {
    case Success(addr) => addr
    case Failure(ex) => throw new Error(s"An error occurred while creating a new keyfile. $ex")
  }

  val pk2: Address = Await.result((keyManagerRef ? GenerateKeyFile("password2", None))
    .mapTo[Try[Address]], 10.seconds) match {
    case Success(addr) => addr
    case Failure(ex) => throw new Error(s"An error occurred while creating a new keyfile. $ex")
  }


  def httpPOST(jsonRequest: ByteString): HttpRequest = {
    HttpRequest(
      HttpMethods.POST,
      uri = "/",
      entity = HttpEntity(MediaTypes.`application/json`, jsonRequest)
    ).withHeaders(RawHeader("x-api-key", "test_key"))
  }

  it should "successfully get open keyfiles" in {
    val openKeyfilesRequest = ByteString(
      s"""
         |{
         |   "jsonrpc": "2.0",
         |   "id": "2",
         |   "method": "wallet_listOpenKeyfiles",
         |   "params": [{}]
         |}
         """.stripMargin)

    httpPOST(openKeyfilesRequest) ~> route ~> check {
      val responseString = responseAs[String].replace("\\", "")
      parse(responseString.replace("\"{", "{").replace("}\"", "}")) match {
        case Left(f) => throw f
        case Right(res: Json) =>
          (res \\ "error").isEmpty shouldBe true
          val openKeys: Set[String] = (res \\ "result").head.asArray.get.map(k => k.asString.get).toSet
          openKeys.size shouldBe 2
          openKeys.contains(pk1.toString) shouldBe true
      }
    }
  }

  var generatedKeyAddr: String = ""

  it should "successfully generate a keyfile" in {
    val generateKeyfileRequest = ByteString(
      s"""
         |{
         |   "jsonrpc": "2.0",
         |   "id": "2",
         |   "method": "wallet_generateKeyfile",
         |   "params": [{
         |      "password": "foo"
         |   }]
         |}
         """.stripMargin)

    httpPOST(generateKeyfileRequest) ~> route ~> check {
      parse(responseAs[String]) match {
        case Left(f) => throw f
        case Right(res: Json) =>
          val result: Json = (res \\ "result").head
          generatedKeyAddr = (result \\ "address").head.asString.get
          (res \\ "error").isEmpty shouldBe true
          result.asObject.isDefined shouldBe true
      }
    }
  }

  val seedPhrase: String = "stand earth guess employ goose aisle great next embark weapon wonder aisle " +
    "monitor surface omit guilt model rule"

  var importedKeyAddr: String = ""

  it should "successfully import a keyfile through mnemonic phrase" in {
    val importKeyfileRequest = ByteString(
      s"""
         |{
         |   "jsonrpc": "2.0",
         |   "id": "2",
         |   "method": "wallet_importKeyfile",
         |   "params": [{
         |      "password": "password",
         |      "seedPhrase": "$seedPhrase",
         |      "seedPhraseLang": "en"
         |   }]
         |}
         """.stripMargin)

    httpPOST(importKeyfileRequest) ~> route ~> check {
      parse(responseAs[String]) match {
        case Left(f) => throw f
        case Right(res: Json) =>
          val result: Json = (res \\ "result").head
          (res \\ "error").isEmpty shouldBe true
          importedKeyAddr = (result \\ "address").head.asString.get
          result.asObject.isDefined shouldBe true

      }
    }
  }

  it should "successfully lock a Keyfile" in {
    val lockKeyRequest = ByteString(
      s"""
         |{
         |   "jsonrpc": "2.0",
         |   "id": "2",
         |   "method": "wallet_lockKeyfile",
         |   "params": [{
         |      "address": "$generatedKeyAddr"
         |   }]
         |}
         """.stripMargin)

    httpPOST(lockKeyRequest) ~> route ~> check {
      val responseString = responseAs[String].replace("\\", "")
      parse(responseString.replace("\"{", "{").replace("}\"", "}")) match {
        case Left(f) => throw f
        case Right(res: Json) =>
          (res \\ "error").isEmpty shouldBe true
          (res \\ "result").head.asObject.isDefined shouldBe true
      }
    }
  }

  it should "successfully get all keyfiles" in {
    Thread.sleep(10000)
    val allKeyfilesRequest = ByteString(
      s"""
         |{
         |   "jsonrpc": "2.0",
         |   "id": "2",
         |   "method": "wallet_listAllKeyfiles",
         |   "params": [{}]
         |}
         """.stripMargin)

    httpPOST(allKeyfilesRequest) ~> route ~> check {
      val responseString = responseAs[String].replace("\\", "")
      parse(responseString.replace("\"{", "{").replace("}\"", "}")) match {
        case Left(f) => throw f
        case Right(res: Json) =>
          (res \\ "error").isEmpty shouldBe true
          val result = (res \\ "result").head
          (result \\ generatedKeyAddr).head.asString.get == "locked" shouldBe true
          (result \\ importedKeyAddr).head.asString.get == "unlocked" shouldBe true
      }
    }
  }

  it should "successfully unlock a Keyfile" in {
    val unlockKeyRequest = ByteString(
      s"""
         |{
         |   "jsonrpc": "2.0",
         |   "id": "2",
         |   "method": "wallet_unlockKeyfile",
         |   "params": [{
         |      "address": "$generatedKeyAddr",
         |      "password": "foo"
         |   }]
         |}
         """.stripMargin)

    httpPOST(unlockKeyRequest) ~> route ~> check {
      val responseString = responseAs[String].replace("\\", "")
      parse(responseString.replace("\"{", "{").replace("}\"", "}")) match {
        case Left(f) => throw f
        case Right(res: Json) =>
          (res \\ "error").isEmpty shouldBe true
          (res \\ "result").head.asObject.isDefined shouldBe true
      }
    }
  }

  it should "successfully get mnemonic phrase" in {
    val mnemonicPhraseRequest = ByteString(
      s"""
         |{
         |   "jsonrpc": "2.0",
         |   "id": "2",
         |   "method": "wallet_generateMnemonic",
         |   "params": [{
         |      "language": "en"
         |   }]
         |}
         """.stripMargin)

    httpPOST(mnemonicPhraseRequest) ~> route ~> check {
      val responseString = responseAs[String].replace("\\", "")
      parse(responseString.replace("\"{", "{").replace("}\"", "}")) match {
        case Left(f) => throw f
        case Right(res: Json) =>
          (res \\ "error").isEmpty shouldBe true
          val phrase = ((res \\ "result").head \\ "mnemonicPhrase").head
          assert(phrase != null)
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

  it should "successfully generate key file with new network prefix" in {
    val generateKeyfileRequest = ByteString(
      s"""
         |{
         |   "jsonrpc": "2.0",
         |   "id": "2",
         |   "method": "wallet_generateKeyfile",
         |   "params": [{
         |      "password": "foo"
         |   }]
         |}
         """.stripMargin)

    httpPOST(generateKeyfileRequest) ~> route ~> check {
      parse(responseAs[String]) match {
        case Left(f) => throw f
        case Right(res: Json) =>
          val result: Json = (res \\ "result").head
          (result \\ "address").head.asString.get.charAt(0) == '9' shouldBe true
          (res \\ "error").isEmpty shouldBe true
          result.asObject.isDefined shouldBe true
      }
    }
  }

  it should "successfully get open keyfiles after network change" in {
    val openKeyfilesRequest = ByteString(
      s"""
         |{
         |   "jsonrpc": "2.0",
         |   "id": "2",
         |   "method": "wallet_listOpenKeyfiles",
         |   "params": [{}]
         |}
         """.stripMargin)

    httpPOST(openKeyfilesRequest) ~> route ~> check {
      val responseString = responseAs[String].replace("\\", "")
      parse(responseString.replace("\"{", "{").replace("}\"", "}")) match {
        case Left(f) => throw f
        case Right(res: Json) =>
          (res \\ "error").isEmpty shouldBe true
          val openKeys: Set[String] = (res \\ "result").head.asArray.get.map(k => k.asString.get).toSet
          openKeys.size == 1 shouldBe true
      }
    }
  }

  it should "successfully modify the keyfile directory" in {
    val networkTypeRequest = ByteString(
      s"""
         |{
         |   "jsonrpc": "2.0",
         |   "id": "2",
         |   "method": "wallet_changeKeyfileDir",
         |   "params": [{
         |      "directory": "keyfiles/newFolder"
         |   }]
         |}
         """.stripMargin)

    httpPOST(networkTypeRequest) ~> route ~> check {
      val responseString = responseAs[String].replace("\\", "")
      parse(responseString.replace("\"{", "{").replace("}\"", "}")) match {
        case Left(f) => throw f
        case Right(res: Json) =>
          (res \\ "error").isEmpty shouldBe true
          val dir = ((res \\ "result").head \\ "newDirectory").head
          val keyfiles: Map[Address, String] = Await.result((keyManagerRef ? GetAllKeyfiles)
            .mapTo[Map[Address,String]], 10.seconds)
          keyfiles.keySet.size shouldBe 0
          assert(dir.asString.get == "keyfiles/newFolder")
      }
    }
  }

}