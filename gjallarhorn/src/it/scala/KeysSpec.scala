import attestation.AddressEncoder.NetworkPrefix
import attestation.{Address, PrivateKeyCurve25519, PublicKeyPropositionCurve25519}
import co.topl.crypto.hash.blake2b256
import co.topl.crypto.hash.implicits.toHashResultOps
import co.topl.crypto.hash.digest.Digest32
import crypto.KeyfileCurve25519
import keymanager.Keys
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.File
import scala.reflect.io.Path
import scala.util.{Failure, Success, Try}

/**
 * Must be running bifrost on local network
 */
class KeysSpec extends AsyncFlatSpec with Matchers {

  /** Make sure running bifrost in local network! */
  implicit val networkPrefix: NetworkPrefix = 48.toByte

  //set up Keys
  val keyFileDir = "keyfiles/keyManagerTest"
  val keyManager: Keys[PrivateKeyCurve25519, KeyfileCurve25519] = Keys(keyFileDir, KeyfileCurve25519)

  val randomBytes1: Digest32 = blake2b256.hash(java.util.UUID.randomUUID.toString.getBytes).getOrThrow()
  val randomBytes2: Digest32 = blake2b256.hash(java.util.UUID.randomUUID.toString.getBytes).getOrThrow()

  //Create keys for testing
  var privateKeys: Set[PrivateKeyCurve25519] = keyManager.generateNewKeyPairs(2, Some("keystest")) match {
    case Success(secrets) => secrets
    case Failure(ex)      => throw new Error(s"Unable to generate new keys: $ex")
  }
  val sk1: PrivateKeyCurve25519 = privateKeys.head
  val addr1: Address = sk1.publicImage.address
  val sk2: PrivateKeyCurve25519 = privateKeys.tail.head
  val addr2: Address = sk2.publicImage.address
  var addresses: Set[Address] = Set(addr1, addr2)

  val sk3: PrivateKeyCurve25519 = keyManager.generateNewKeyPairs(1, Some("keystest")) match {
    case Success(secrets) => secrets.head
    case Failure(ex)      => throw new Error(s"Unable to generate new keys: $ex")
  }

  //Filepath to write keypairs
  val path: Path = Path(keyFileDir)
  Try(path.deleteRecursively())
  Try(path.createDirectory())
  val password = "password"

  //generate seed
  val seedString: String = java.util.UUID.randomUUID.toString
  val seed1: Digest32 = blake2b256.hash(seedString.getBytes).getOrThrow()

  //------------------------------------------------------------------------------------
  //Signed messages
  //Should have same input to check determinism
  val messageBytes: Digest32 = blake2b256.hash("sameEntropic".getBytes).getOrThrow()
  val messageToSign: Digest32 = blake2b256.hash(java.util.UUID.randomUUID.toString.getBytes).getOrThrow()

  it should "Match its signature to the expected sender private key" in {
    //Entropic input to input for pub/priv keypair
    val proof = sk1.sign(messageToSign.value)
    assert(proof.isValid(sk1.publicImage, messageToSign.value))
  }

  it should "Yield an invalid signature if signed with incorrect foreign key" in {
    val proof = sk1.sign(messageToSign.value)
    assert(!proof.isValid(sk2.publicImage, messageToSign.value))
  }

  it should "Sign and verify as expected when msg is deterministic" in {
    val proof = sk2.sign(messageBytes.value)
    //Utilize same input. Proving hashing function AND keygen methods are deterministic
    assert(proof.isValid(sk2.publicImage, messageBytes.value))
  }
  //------------------------------------------------------------------------------------
  //Key Generation

  //Entropic input used to make pub/priv keypair
  it should "Come as a private key and public key pair" in {
    assert(sk1 != null)
    assert(addr1 != null)
    assert(sk1.isInstanceOf[PrivateKeyCurve25519])
    assert(sk1.publicImage.isInstanceOf[PublicKeyPropositionCurve25519])
    assert(addr1.isInstanceOf[Address])
  }
  it should "Be deterministic" in {
    //Used the same entropic input
    assert(addr1.equals(sk3.publicImage.address))
  }

  it should "Have sufficient randomness in entropic keypair gen" in {
    //Valid pretest to ensure sufficiently random input so no shared keys
    assert(randomBytes1 != randomBytes2)
    assert(sk1 != null && addr1 != null && sk1.isInstanceOf[PrivateKeyCurve25519] && addr1.isInstanceOf[Address])
  }

  //------------------------------------------------------------------------------------
  //KeyManager

  var newAddress: Address = sk1.publicImage.address
  val password2: String = "password2"

  it should "Have 2 keyfiles before key generation and 3 after" in {
    assert(keyManager.addresses.size === 2)
    keyManager.generateKeyFile(password2) match {
      case Success(addr) =>
        newAddress = addr
        addresses += newAddress
      case Failure(ex) => throw new Error(s"An error occurred while creating a new keyfile. $ex")
    }
    assert(keyManager.addresses.count(addr => addr == newAddress) == 1)
    assert(keyManager.addresses.size === 3)
  }

  it should "successfully lock a key file" in {
    keyManager.lockKeyFile(newAddress.toString).get
    assert(keyManager.addresses.size == 2)
  }

  it should "successfully unlock a key file" in {
    keyManager.unlockKeyFile(newAddress.toString, password2)
    assert(keyManager.addresses.size == 3)
  }

  //------------------------------------------------------------------------------------
  //KeyFile

  it should "successfully export keyfiles" in {
    privateKeys.foreach(sk => keyManager.exportKeyfile(sk.publicImage.address, "password"))
    val exportedKeys: Set[Address] = keyManager.listKeyFilesAndStatus.keySet
    assert(exportedKeys.size == 3)
  }

  it should "grab list of files and convert to KeyFile" in {
    val exportedKeys: List[KeyfileCurve25519] = Keys
      .getListOfFiles(new File(s"$keyFileDir/local"))
      .map { file =>
        KeyfileCurve25519.readFile(file.getPath)
      }

    addresses.foreach(address => assert(exportedKeys.map(_.address).contains(address)))
    assert(exportedKeys.size == 3)
  }

  val seedPhrase: String = "stand earth guess employ goose aisle great next embark weapon wonder aisle monitor " +
    "surface omit guilt model rule"

  it should "import keys correctly" in {
    keyManager.importPhrase("foo", seedPhrase, "en")
    assert(keyManager.addresses.size == 4)
  }

  //------------------------------------------------------------------------------------

}
