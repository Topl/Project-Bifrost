package keymanager

import attestation.AddressEncoder.NetworkPrefix
import attestation.{Address, Secret, SecretGenerator}
import co.topl.crypto.hash.blake2b256
import co.topl.utils.SecureRandom.randomBytes
import com.google.common.primitives.Ints
import settings.NetworkType
import utils.Logging

import java.io.File
import scala.collection.mutable.{Map => MMap}
import scala.util.{Failure, Success, Try}

/**
 * The keys class is equivalent to the KeyRing in Bifrost.
 * It holds the current keys in the wallet application and handles functions on the keys such as:
 * locking/unlocking, generating a new key, etc.
 * @param defaultKeyDir the key directory to save the keyfiles to
 * @param secrets the set of secrets for the keys
 * @param keyfileOps the type of KeyfileCompanion (as of now there is only one: [[crypto.KeyfileCurve25519]])
 * @param networkPrefix the current network prefix
 * @param sg the SecretGenerator for the secrets
 * @tparam S the type of Secret for these keys
 * @tparam KF the type of Keyfile ([[crypto.KeyfileCurve25519]])
 */
class Keys[
  S <: Secret,
  KF <: Keyfile[S]
](defaultKeyDir: File, private var secrets: Set[S], private val keyfileOps: KeyfileCompanion[S, KF])(implicit
  networkPrefix: NetworkPrefix,
  sg:            SecretGenerator[S]
) extends Logging {

  type PR = S#PR

  /**
   * Retrieves a list of public images for the secrets currently held in the keyring
   *
   * @return - list of addresses corresponding to unlocked key files.
   */
  def addresses: Set[Address] = secrets.map(_.publicImage.address)

  /**
   * Generates a signature using the secret key associated with an Address
   * @param addr the address to sign with
   * @param messageToSign the message to sign
   * @return if the given keyfile is currently unlocked, returns signature associated with its secret key else fails.
   */
  def signWithAddress(addr: Address, messageToSign: Array[Byte]): Try[PR] =
    secrets.find(_.publicImage.address == addr) match {
      case Some(sk) => Try(sk.sign(messageToSign))
      case _        => throw new Error("Unable to find secret for the given address")
    }

  /**
   * Looks up the public key associated with an address
   * @param addr address for key file to lookup
   * @return if the keyfile is unlocked, returns its PublicKeyProposition. Else fails.
   */
  def lookupPublicKey(addr: Address): Try[S#PK] =
    secrets.find(_.publicImage.address == addr) match {
      case Some(sk) => Success(sk.publicImage)
      case _        => throw new Error("Unable to find secret for the given address")
    }

  /**
   * Returns the private key for the given address
   * @param addressString the address as a string
   * @param password the password used to grab the secret key
   * @return the secret key
   */
  private def getPrivateKey(addressString: String, password: String): S = {
    val keyfile = checkValid(addressString: String)

    keyfileOps.decryptSecret(keyfile, password) match {
      case Success(sk) => sk
      case Failure(e)  => throw new Exception(s"Wrong password: $e")
    }
  }

  /**
   * Given an address and password, unlocks the associated key file.
   * @param addressString Base58 encoded address to unlock
   * @param password      password for the public key associated with the given address.
   */
  def unlockKeyFile(addressString: String, password: String): Try[Unit] = Try {
    val privKey = getPrivateKey(addressString, password)

    // ensure no duplicate by comparing privKey strings
    if (!secrets.contains(privKey)) secrets += privKey
    else log.warn(s"$addressString is already unlocked")
  }

  /**
   * Given an address, locks a key file.
   * @param addressString Base58 encoded address that corresponds to the key to lock
   * @return Successful if given a valid address, else returns a Failure
   */
  def lockKeyFile(addressString: String): Try[Unit] = Try {
    val keyfile = checkValid(addressString: String)

    // ensure no duplicate by comparing privKey strings
    val addresses: Set[Address] = secrets.map(sk => sk.publicImage.address)
    if (!addresses.contains(keyfile.address)) log.warn(s"$addressString is already locked")
    else secrets -= (secrets find (p => p.publicImage.address == keyfile.address)).get
  }

  /**
   * Generates a key file
   * @param password - password to use to encrypt generated key.
   * @param seedOpt - optional seed used to generate key file, default set to None
   * @return if successful, returns an address. Else fails.
   */
  def generateKeyFile(password: String, seedOpt: Option[String] = None): Try[Address] =
    // generate a new random key pair and save to disk
    generateNewKeyPairs(1, seedOpt).map { sk =>
      exportKeyfile(sk.head.publicImage.address, password)
      sk.head.publicImage.address
    }

  /**
   * Generates new secrets
   * @param num - number of keys to be generated.
   * @param seedOpt - optional seed to create keys.
   * @return if successful, returns set of secret keys. Else fails.
   */
  def generateNewKeyPairs(num: Int = 1, seedOpt: Option[String] = None): Try[Set[S]] =
    Try {
      if (num >= 1) {
        val newSecrets = seedOpt match {
          case Some(seed) => (1 to num).map(i => sg.generateSecret(Ints.toByteArray(i) ++ seed.getBytes())._1).toSet
          case _          => (1 to num).map(_ => sg.generateSecret(randomBytes(128))._1).toSet
        }

        secrets ++= newSecrets
        newSecrets
      } else throw new Error("Number of requested keys must be greater than or equal to 1")
    }

  /**
   * Generates key file given a mnemonic phrase
   * @param password - password to encrypt imported key.
   * @param mnemonic - mnemonic phrase used to generate key.
   * @param lang - language used to create BIP object to generate key.
   * @return - if successful, returns address of generated key file. Else fails
   */
  def importPhrase(password: String, mnemonic: String, lang: String)(implicit sg: SecretGenerator[S]): Try[Address] =
    Try {
      // create the BIP object used to verify the chosen language
      val bip = Bip39(lang)

      // ensure the phrase is valid
      if (!bip.phraseCheckSum(mnemonic)) throw new Error(s""" "$mnemonic" is not a valid mnemonic phrase!""")

      // calculate the new keyfile and return
      val seed = bip.hexToUuid(bip.phraseToHex(mnemonic))
      val sk = sg.generateSecret(blake2b256.hash(seed.getBytes).value)

      // add secret to the keyring
      secrets += sk._1

      exportKeyfile(sk._2.address, password)

      // return the public image of the key that was added
      sk._2.address
    }

  /**
   * Returns the directory for the current network
   * @return the file for the current key directory and current network
   */
  def getNetworkDir: File = {
    val networkName: String = NetworkType.fromPrefix(networkPrefix) match {
      case Some(network) => network.verboseName
      case None          => throw new Error("The network prefix does not match any of the network types!")
    }
    new File(s"${defaultKeyDir.getAbsolutePath}/$networkName")
  }

  /**
   * Exports a key file (saves key file to disk)
   * @param address - address for keyfile to export
   * @param password - password for keyfile to export
   * @return Success if address is unlocked and secret is found, else fails.
   */
  def exportKeyfile(address: Address, password: String): Try[Unit] = Try {
    secretByAddress(address) match {
      case Some(sk) =>
        val networkDir = getNetworkDir
        if (!networkDir.exists()) {
          networkDir.mkdirs()
        }
        keyfileOps.saveToDisk(networkDir.getAbsolutePath, password, sk)
      case _ => Failure(new Error("Unable to find a matching secret in the key ring"))
    }
  }

  /**
   * Finds a secret given its address
   * @param addr the address to grab secret for
   * @return if the key file is unlocked, returns the secret key. Else returns None
   */
  private def secretByAddress(addr: Address): Option[S] =
    secrets.find(_.publicImage.address == addr)

  /**
   * Returns a map of addresses to "locked" or "unlocked"
   * @return map of address to "locked" or "unlocked"
   */
  def listKeyFilesAndStatus: Map[Address, String] = {
    val unlocked: Set[Address] = secrets.map(_.publicImage.address)
    val map: MMap[Address, String] = MMap.empty
    listKeyFiles
      .map(_.address)
      .foreach(addr =>
        if (unlocked.contains(addr)) map.put(addr, "unlocked")
        else map.put(addr, "locked")
      )
    map.toMap
  }

  /**
   * Return a list of KeyFile instances for all keys in the key file directory for the current network
   * @return List[KF]
   */
  private def listKeyFiles: List[KF] =
    Keys.getListOfFiles(getNetworkDir).map(file => keyfileOps.readFile(file.getPath))

  /**
   * Checks if given address string is valid and contained in the key file directory
   * @param address Base58 encoded address to query
   * @return the keyfile for the given address
   */
  private def checkValid(address: String): KF = {
    val keyfile = listKeyFiles.filter {
      _.address == Address(networkPrefix)(address)
    }

    require(keyfile.size == 1, s"Cannot find a unique matching keyfile in $defaultKeyDir")
    keyfile.head
  }
}

/** Factory for [[Keys]] instances. */
object Keys {

  def apply[
    S <: Secret: SecretGenerator,
    KF <: Keyfile[S]
  ](path: String, keyfileCompanion: KeyfileCompanion[S, KF])(implicit networkPrefix: NetworkPrefix): Keys[S, KF] = {
    val dir = new File(path)
    dir.mkdirs()
    new Keys(dir, Set(), keyfileCompanion)
  }

  def getListOfFiles(dir: File): List[File] =
    if (dir.exists && dir.isDirectory) dir.listFiles.filter(_.isFile).toList
    else List[File]()
}
