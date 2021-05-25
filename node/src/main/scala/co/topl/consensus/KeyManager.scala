package co.topl.consensus

import akka.Done
import akka.actor._
import akka.util.Timeout
import cats.data.EitherT
import cats.implicits._
import co.topl.attestation.AddressCodec.implicits.StringOps
import co.topl.attestation.keyManagement.{KeyRing, KeyfileCurve25519, KeyfileCurve25519Companion, KeyfileEd25519, KeyfileEd25519Companion, PrivateKeyCurve25519, PrivateKeyEd25519}
import co.topl.attestation.{Address, AddressCodec, PublicKeyPropositionCurve25519, PublicKeyPropositionEd25519, SignatureCurve25519, SignatureEd25519}
import co.topl.catsakka.AskException
import co.topl.consensus.KeyManager.{AttemptForgingKeyView, ForgerStartupKeyView}
import co.topl.settings.{AppContext, AppSettings}
import co.topl.utils.Logging
import co.topl.utils.NetworkType._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Success, Try}

/** Actor that manages the keyRing and reward address */
class KeyManager(
  settings:    AppSettings,
  appContext:  AppContext
)(implicit ec: ExecutionContext, np: NetworkPrefix)
    extends Actor
    with Logging {

  import KeyManager.ReceivableMessages._

  ////////////////////////////////////////////////////////////////////////////////////
  ////////////////////////////// ACTOR MESSAGE HANDLING //////////////////////////////

  override def receive: Receive = {
    val keyRing = createKeyRing()
    val rewardsAddress = tryGetRewardsAddressFromSettings()

    receive(keyRing, rewardsAddress)
  }

  /**
   * Receives messages with the given key ring and reward address set as context data.
   * @param keyRing the current key ring state
   * @param rewardAddress the address to give forging rewards to
   * @return a Receive partial function
   */
  def receive(keyRing: KeyRing[PrivateKeyEd25519, KeyfileEd25519], rewardAddress: Option[Address]): Receive = {
    case CreateKey(password)                 => sender() ! keyRing.DiskOps.generateKeyFile(password)
    case UnlockKey(addr, password)           => sender() ! keyRing.DiskOps.unlockKeyFile(addr, password)
    case LockKey(addr)                       => sender() ! keyRing.removeFromKeyring(addr)
    case ImportKey(password, mnemonic, lang) => sender() ! keyRing.importPhrase(password, mnemonic, lang)
    case ListKeys                            => sender() ! keyRing.addresses
    case UpdateRewardsAddress(address)       => sender() ! updateRewardsAddress(keyRing, address)
    case GetRewardsAddress                   => sender() ! rewardAddress.fold("none")(_.toString)
    case GetAttemptForgingKeyView            => sender() ! getAttemptForgingKeyView(keyRing, rewardAddress)
    case GenerateInitialAddresses            => sender() ! generateInitialAddresses(keyRing, rewardAddress)
  }

  ////////////////////////////////////////////////////////////////////////////////////
  //////////////////////////////// METHOD DEFINITIONS ////////////////////////////////

  /** Creates a new key ring. */
  def createKeyRing(): KeyRing[PrivateKeyEd25519, KeyfileEd25519] = {
    implicit val keyfileCurve25519Companion: KeyfileEd25519Companion.type = KeyfileEd25519Companion

    val keyFileDir = settings.application.keyFileDir
      .ensuring(_.isDefined, "A keyfile directory must be specified")
      .get

    KeyRing.empty[PrivateKeyEd25519, KeyfileEd25519](Some(keyFileDir))
  }

  /**
   * Generates the initial addresses in the node for a private or local test network.
   * @param keyRing the key ring to generate addresses in
   * @param rewardAddress the current reward address
   * @return a try which results in a ForgerView of the current addresses and rewards address
   */
  private def generateInitialAddresses(
    keyRing:       KeyRing[PrivateKeyEd25519, KeyfileEd25519],
    rewardAddress: Option[Address]
  ): Try[ForgerStartupKeyView] =
    // If the keyring is not already populated and this is a private/local testnet, generate the keys
    // this is for when you have started up a private network and are attempting to resume it using
    // the same seed you used previously to continue forging
    if (keyRing.addresses.isEmpty && Seq(PrivateTestnet, LocalTestnet).contains(appContext.networkType)) {
      settings.forging.privateTestnet match {
        case Some(sfp) =>
          val (numAccts, seed) = (sfp.numTestnetAccts, sfp.genesisSeed)

          keyRing
            .generateNewKeyPairs(numAccts, seed)
            .map(keys => keys.map(_.publicImage.address))
            .map { addresses =>
              val newRewardAddress = if (rewardAddress.isEmpty) Some(addresses.head) else rewardAddress

              context.become(receive(keyRing, newRewardAddress))

              ForgerStartupKeyView(addresses, newRewardAddress)
            }
        case _ =>
          log.warn("No private testnet settings found!")
          Success(ForgerStartupKeyView(keyRing.addresses, rewardAddress))
      }
    } else {
      Success(ForgerStartupKeyView(keyRing.addresses, rewardAddress))
    }

  /** Gets a read-only view of the key ring to use for forging. */
  private def getAttemptForgingKeyView(
    keyRing:       KeyRing[PrivateKeyEd25519, KeyfileEd25519],
    rewardAddress: Option[Address]
  ): AttemptForgingKeyView =
    AttemptForgingKeyView(
      keyRing.addresses,
      rewardAddress,
      (address: Address) => (message: Array[Byte]) => keyRing.signWithAddress(address)(message),
      (address: Address) => keyRing.lookupPublicKey(address)
    )

  /** Tries to get a configured rewards address from the forging settings. */
  private def tryGetRewardsAddressFromSettings(): Option[Address] =
    settings.forging.rewardsAddress.flatMap {
      _.decodeAddress.toEither match {
        case Left(ex) =>
          log.warn(s"${Console.YELLOW}Unable to set rewards address due to $ex ${Console.RESET}")
          None

        case Right(addr) => Some(addr)
      }
    }

  /** Updates the rewards address from the API */
  private def updateRewardsAddress(
    keyRing: KeyRing[PrivateKeyEd25519, KeyfileEd25519],
    address: Address
  ): String = {
    val newRewardAddress = Some(address)
    context.become(receive(keyRing, newRewardAddress))
    newRewardAddress.fold("none")(_.toString)
  }
}

////////////////////////////////////////////////////////////////////////////////////
/////////////////////////////// COMPANION SINGLETON ////////////////////////////////

object KeyManager {

  val actorName = "keyManager"

  case class ForgerStartupKeyView(addresses: Set[Address], rewardAddr: Option[Address])

  case class AttemptForgingKeyView(
    addresses:    Set[Address],
    rewardAddr:   Option[Address],
    sign:         Address => Array[Byte] => Try[SignatureEd25519],
    getPublicKey: Address => Try[PublicKeyPropositionEd25519]
  )

  object ReceivableMessages {
    case class UnlockKey(addr: String, password: String)

    case class LockKey(addr: Address)

    case object ListKeys

    case class CreateKey(password: String)

    case class ImportKey(password: String, mnemonic: String, lang: String)

    case object GetRewardsAddress

    case class UpdateRewardsAddress(address: Address)

    case object GetForgerStartupKeyView

    case object GetAttemptForgingKeyView

    case object GenerateInitialAddresses
  }

}

////////////////////////////////////////////////////////////////////////////////////
//////////////////////////////// ACTOR REF HELPER //////////////////////////////////

object KeyManagerRef {

  def props(settings: AppSettings, appContext: AppContext)(implicit ec: ExecutionContext, np: NetworkPrefix): Props =
    Props(
      new KeyManager(settings, appContext)
    )

  def apply(name: String, settings: AppSettings, appContext: AppContext)(implicit
    system:       ActorSystem,
    ec:           ExecutionContext
  ): ActorRef =
    system.actorOf(props(settings, appContext)(ec, appContext.networkType.netPrefix), name)

}

sealed trait UnlockKeyFailure
case class UnlockKeyFailureException(throwable: Throwable) extends UnlockKeyFailure
sealed trait LockKeyFailure
case class LockKeyFailureException(throwable: Throwable) extends LockKeyFailure
sealed trait CreateKeyFailure
case class CreateKeyFailureException(throwable: Throwable) extends CreateKeyFailure
sealed trait ImportKeyFailure
case class ImportKeyFailureException(throwable: Throwable) extends ImportKeyFailure
sealed trait GetRewardsAddressFailure
case class GetRewardsAddressFailureException(throwable: Throwable) extends GetRewardsAddressFailure
sealed trait UpdateRewardsAddressFailure
case class UpdateRewardsAddressFailureException(throwable: Throwable) extends UpdateRewardsAddressFailure
sealed trait ListOpenKeyfilesFailure
case class ListOpenKeyfilesFailureException(throwable: Throwable) extends ListOpenKeyfilesFailure

trait KeyManagerInterface {
  def unlockKey(address:  String, password: String): EitherT[Future, UnlockKeyFailure, Address]
  def lockKey(address:    Address): EitherT[Future, LockKeyFailure, Done.type]
  def createKey(password: String): EitherT[Future, CreateKeyFailure, Address]
  def importKey(password: String, mnemonic: String, lang: String): EitherT[Future, ImportKeyFailure, Address]
  def getRewardsAddress(): EitherT[Future, GetRewardsAddressFailure, String]
  def updateRewardsAddress(address: Address): EitherT[Future, UpdateRewardsAddressFailure, Done.type]
  def listOpenKeyfiles(): EitherT[Future, ListOpenKeyfilesFailure, Set[Address]]
}

class ActorKeyManagerInterface(actorRef: ActorRef)(implicit ec: ExecutionContext, timeout: Timeout)
    extends KeyManagerInterface {
  import co.topl.catsakka.CatsActor._

  override def unlockKey(address: String, password: String): EitherT[Future, UnlockKeyFailure, Address] =
    actorRef
      .askEither[Try[Address]](KeyManager.ReceivableMessages.UnlockKey(address, password))
      .leftMap { case AskException(throwable) => UnlockKeyFailureException(throwable) }
      .subflatMap(_.toEither.leftMap(UnlockKeyFailureException))

  override def lockKey(address: Address): EitherT[Future, LockKeyFailure, Done.type] =
    actorRef
      .askEither[Try[_]](KeyManager.ReceivableMessages.LockKey(address))
      .leftMap { case AskException(throwable) => LockKeyFailureException(throwable) }
      .subflatMap(_.toEither.leftMap(LockKeyFailureException))
      .map(_ => Done)
      .leftMap(e => e: LockKeyFailure)

  override def createKey(password: String): EitherT[Future, CreateKeyFailure, Address] =
    actorRef
      .askEither[Try[Address]](KeyManager.ReceivableMessages.CreateKey(password))
      .leftMap { case AskException(throwable) => CreateKeyFailureException(throwable) }
      .subflatMap(_.toEither.leftMap(CreateKeyFailureException))
      .leftMap(e => e: CreateKeyFailure)

  override def importKey(
    password: String,
    mnemonic: String,
    lang:     String
  ): EitherT[Future, ImportKeyFailure, Address] =
    actorRef
      .askEither[Try[Address]](KeyManager.ReceivableMessages.ImportKey(password, mnemonic = mnemonic, lang = lang))
      .leftMap { case AskException(throwable) => ImportKeyFailureException(throwable) }
      .subflatMap(_.toEither.leftMap(ImportKeyFailureException))
      .leftMap(e => e: ImportKeyFailure)

  override def getRewardsAddress(): EitherT[Future, GetRewardsAddressFailure, String] =
    actorRef
      .askEither[String](KeyManager.ReceivableMessages.GetRewardsAddress)
      .leftMap { case AskException(throwable) => GetRewardsAddressFailureException(throwable) }
      .leftMap(e => e: GetRewardsAddressFailure)

  override def updateRewardsAddress(address: Address): EitherT[Future, UpdateRewardsAddressFailure, Done.type] =
    actorRef
      .askEither[String](KeyManager.ReceivableMessages.UpdateRewardsAddress(address))
      .leftMap { case AskException(throwable) => UpdateRewardsAddressFailureException(throwable) }
      .leftMap(e => e: UpdateRewardsAddressFailure)
      .map(_ => Done)

  override def listOpenKeyfiles(): EitherT[Future, ListOpenKeyfilesFailure, Set[Address]] =
    actorRef
      .askEither[Set[Address]](KeyManager.ReceivableMessages.ListKeys)
      .leftMap { case AskException(throwable) => ListOpenKeyfilesFailureException(throwable) }
      .leftMap(e => e: ListOpenKeyfilesFailure)
}
