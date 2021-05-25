package keymanager

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.util.Timeout
import attestation.{Address, PrivateKeyCurve25519}
import co.topl.utils.encode.Base58
import crypto.KeyfileCurve25519
import http.GjallarhornOfflineApiRoute.updateConfigFile
import io.circe.Json
import io.circe.syntax._
import settings.{ApplicationSettings, NetworkType}
import utils.Logging
import wallet.WalletManager
import wallet.WalletManager.NewKey

import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

/**
 * Manages the keys and handles requests related to the keys.
 * @param settings - application settings
 */
class KeyManager(settings: ApplicationSettings) extends Actor with Logging {

  import KeyManager._

  /**
   * The current sets of keys to manage
   */
  private var keyRing: Keys[PrivateKeyCurve25519, KeyfileCurve25519] =
    Keys(settings.keyFileDir, KeyfileCurve25519)(PrivateKeyCurve25519.secretGenerator, networkPrefix = networkPrefix)

  implicit val timeout: Timeout = 10.seconds

  override def receive: Receive = {
    case GenerateKeyFile(password, seedOpt) => shareNewKey(keyRing.generateKeyFile(password, seedOpt), sender())

    case ImportKeyfile(password: String, mnemonic: String, lang: String) =>
      shareNewKey(keyRing.importPhrase(password, mnemonic, lang), sender())

    case UnlockKeyFile(addressString, password) => sender() ! keyRing.unlockKeyFile(addressString, password)

    case LockKeyFile(addressString) => sender() ! keyRing.lockKeyFile(addressString)

    case GetOpenKeyfiles => sender() ! keyRing.addresses

    case GetAllKeyfiles => sender() ! keyRing.listKeyFilesAndStatus

    case SignTx(tx: Json, keys: IndexedSeq[Address], msg: String) =>
      val newTx = signTx(tx, keys, msg)
      sender() ! Map("tx" -> newTx).asJson

    case GenerateSignatures(keys: IndexedSeq[Address], msg: String) =>
      sender() ! Map("signatures" -> createSignatures(keys, msg)).asJson

    case ChangeNetwork(networkName: String) => sender() ! switchNetwork(networkName)

    case GetKeyfileDir => sender() ! Map("keyfileDirectory" -> keyRing.getNetworkDir.getAbsolutePath).asJson

    case ChangeKeyfileDir(dir: String) =>
      updateConfigFile("keyFileDir", settings.keyFileDir, dir)
      settings.keyFileDir = dir

      //initialize keyRing with updated key file directory
      keyRing = Keys(settings.keyFileDir, KeyfileCurve25519)(
        PrivateKeyCurve25519.secretGenerator,
        networkPrefix = networkPrefix
      )
      sender() ! Map("newDirectory" -> settings.keyFileDir).asJson
  }

  /**
   * Tells the WalletManager about a new key
   * @param newAddress - the address of the new key (if successfully created)
   * @param sender the actor ref that that requested to generate a new key and to send new address back to
   */
  private def shareNewKey(newAddress: Try[Address], sender: ActorRef): Unit = {
    newAddress match {
      case Success(addr) =>
        context.actorSelection("../" + WalletManager.actorName).resolveOne().onComplete {
          case Success(walletActor) => walletActor ! NewKey(addr)
          case Failure(exception)   => log.info("offline mode")
        }
      case Failure(ex) => log.error("unable to generate key file!")
    }
    sender ! newAddress
  }

  /**
   * Creates signatures for given keys using the given message to sign (msg)
   * @param keys the keys to generate signatures for
   * @param msg the message to sign
   * @return mapping of PublicKeyProposition to Signature
   */
  private def createSignatures(keys: IndexedSeq[Address], msg: String): Map[PrivateKeyCurve25519#PK, Json] =
    keys.map { address =>
      Base58.decode(msg) match {
        case Success(msgToSign) =>
          keyRing.signWithAddress(address, msgToSign) match {
            case Success(signedTx) =>
              val sig = signedTx.asJson
              keyRing.lookupPublicKey(address) match {
                case Success(pubKey)    => pubKey -> sig
                case Failure(exception) => throw exception
              }
            case Failure(exception) => throw exception
          }
        case Failure(exception) => throw exception
      }
    }.toMap

  /**
   * Signs transaction
   * @param tx raw transaction to be signed
   * @param keys keys to sign tx with
   * @param msg message to sign
   * @return Json of fully signed transaction
   */
  private def signTx(tx: Json, keys: IndexedSeq[Address], msg: String): Json =
    (for {
      currentSignatures <- (tx \\ "signatures").head.as[Map[PrivateKeyCurve25519#PK, Json]]
    } yield {
      val newSignatures: Map[PrivateKeyCurve25519#PK, Json] = createSignatures(keys, msg)
      tx.deepMerge(
        Map(
          "signatures" -> (currentSignatures ++ newSignatures).asJson
        ).asJson
      )
    }) match {
      case Right(value) => value
      case Left(ex)     => throw new Exception(s"error parsing json: $ex")
    }

  /**
   * Switches the current network to new network
   * @param networkName name of new network to switch to
   * @return if the given name is a valid network name, returns mapping of "newNetworkPrefix" to the network prefix
   */
  private def switchNetwork(networkName: String): Try[Json] = Try {
    NetworkType.fromString(networkName) match {
      case Some(network) =>
        if (network.netPrefix != networkPrefix) {
          //lock all keyfiles on current network
          keyRing.addresses.foreach(addr => keyRing.lockKeyFile(addr.toString))
          //change network and initialize keyRing with new network
          networkPrefix = network.netPrefix
          keyRing = Keys(settings.keyFileDir, KeyfileCurve25519)(
            PrivateKeyCurve25519.secretGenerator,
            networkPrefix = networkPrefix
          )
          log.info(s"${Console.MAGENTA}Network changed to: ${network.verboseName} ${Console.RESET}")
        }
        Map("newNetworkPrefix" -> networkPrefix).asJson
      case None => throw new Exception(s"The network name: $networkName was not a valid network type!")
    }
  }
}

object KeyManager {
  case class GenerateKeyFile(password: String, seedOpt: Option[String])
  case class ImportKeyfile(password: String, mnemonic: String, lang: String)
  case class UnlockKeyFile(addressString: String, password: String)
  case class LockKeyFile(addressString: String)
  case object GetOpenKeyfiles
  case object GetAllKeyfiles
  case class SignTx(transaction: Json, signingKeys: IndexedSeq[Address], messageToSign: String)
  case class GenerateSignatures(signingKeys: IndexedSeq[Address], messageToSign: String)
  case class ChangeNetwork(networkName: String)
  case object GetKeyfileDir
  case class ChangeKeyfileDir(dir: String)
}

object KeyManagerRef {

  def props(settings: ApplicationSettings)(implicit ec: ExecutionContext): Props =
    Props(new KeyManager(settings))

  def apply(name: String, settings: ApplicationSettings)(implicit system: ActorSystem, ec: ExecutionContext): ActorRef =
    system.actorOf(Props(new KeyManager(settings)), name = name)
}
