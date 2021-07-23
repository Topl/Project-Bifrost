package co.topl.attestation.keyManagement

import cats.data.Validated.{Invalid, Valid}
import co.topl.attestation.Address
import co.topl.attestation.AddressCodec.implicits._
import co.topl.utils.IdiomaticScalaTransition.implicits.toValidatedOps
import co.topl.utils.NetworkType.NetworkPrefix
import co.topl.utils.SecureRandom.randomBytes
import co.topl.utils.StringDataTypes.{Base58Data, Latin1Data}
import com.google.common.primitives.Ints

import java.io.File
import scala.collection.immutable.ListMap
import scala.util.{Failure, Success, Try}

class KeyRing[
  S <: Secret,
  KF <: Keyfile[S]
](
  keyDirectory:        Option[File],
  private var secrets: Set[S] = Set()
)(implicit
  networkPrefix:    NetworkPrefix,
  secretGenerator:  SecretGenerator[S],
  keyfileCompanion: KeyfileCompanion[S, KF]
) {

  // PK is a type defined in Secret. There's more information in this video: https://youtu.be/63syJfNoDPI
  type PK = S#PK
  type PR = S#PR

  // bring encryptSecret and decryptSecret into scope from the companion
  import keyfileCompanion._

  /**
   * Retrieves a list of public images for the secrets currently held in the keyring
   *
   * @return - the public keys as ProofOfKnowledgePropositions
   */
  def addresses: Set[Address] = secrets.map(_.publicImage.address)

  /** Generate a signature using the secret key associated with an Address */
  def signWithAddress(addr: Address)(messageToSign: Array[Byte]): Try[PR] =
    secrets.find(_.publicImage.address == addr) match {
      case Some(sk) => Try(sk.sign(messageToSign))
      case _        => throw new Exception("Unable to find secret for the given address")
    }

  /** Lookup the public key associated with an address */
  def lookupPublicKey(addr: Address): Try[PK] =
    secrets.find(_.publicImage.address == addr) match {
      case Some(sk) => Success(sk.publicImage)
      case _        => throw new Exception("Unable to find secret for the given address")
    }

  /**
   * Generate an attestation map using the given address and message to sign
   * @param addr address to lookup the proposition associated with the proof that is needed
   * @param messageToSign the message that should be committed to
   * @return a map that can be inserted into a transaction
   */
  def generateAttestation(addr: Address)(messageToSign: Array[Byte]): ListMap[PK, PR] =
    (lookupPublicKey(addr), signWithAddress(addr)(messageToSign)) match {
      case (Success(pk), Success(sig)) => ListMap(pk -> sig)
      case (_, Failure(e))             => throw e
      case (Failure(e), _)             => throw e // this assumes the failure is due to not finding the address
    }

  def generateAttestation(addresses: Set[Address])(messageToSign: Array[Byte]): ListMap[PK, PR] =
    addresses.map(addr => generateAttestation(addr)(messageToSign)).reduce(_ ++ _)

  /**
   * Generates a new keypair and updates the key ring with the new secret
   * @param num
   * @param seedOpt
   * @return
   */
  def generateNewKeyPairs(num: Int = 1, seedOpt: Option[String] = None): Try[Set[S]] =
    Try {
      if (num >= 1) {
        val newSecrets = seedOpt match {
          case Some(seed) =>
            (1 to num).map(i => secretGenerator.generateSecret(Ints.toByteArray(i) ++ seed.getBytes())._1).toSet
          case _ => (1 to num).map(_ => secretGenerator.generateSecret(randomBytes(128))._1).toSet
        }

        secrets ++= newSecrets
        newSecrets
      } else throw new Error("Number of requested keys must be greater than or equal to 1")
    }

  /**
   * Attempts to import a keyfile into the key ring
   * @param keyfile encrypted keyfile that will be added
   * @param password Latin-1 encoded password for decrypting the keyfile
   * @return the address of the key pair for this network
   */
  def importKeyPairSafe(keyfile: KF, password: Latin1Data): Try[Address] = {
    require(
      keyfile.address.networkPrefix == networkPrefix,
      s"Invalid key file for chosen network. " +
      s"Provided key has network prefix ${keyfile.address.networkPrefix} but required prefix is $networkPrefix"
    )

    decryptSecretSafe(keyfile, password).map { s =>
      secrets ++= Set(s)
      s.publicImage.address
    }
  }

  /**
   * Attempts to import a keyfile into the key ring
   * @param keyfile encrypted keyfile that will be added
   * @param password Latin-1 encoded password for decrypting the keyfile
   * @return the address of the key pair for this network
   */
  def importKeyPair(keyfile: KF, password: String): Try[Address] = Latin1Data.validated(password) match {
    case Valid(str)      => importKeyPairSafe(keyfile, str)
    case Invalid(errors) => Failure(new Error(s"Invalid Latin-1 password: $errors"))
  }

  /**
   * Removes an address from the keyring so that it is not available for signing any longer
   * @param address network specific address to be removed
   * @return a try indicating whether the key ring was successfully mutated or not
   */
  def removeFromKeyring(address: Address): Try[Unit] = Try {
    secretByAddress(address) match {
      case Some(sk) => secrets -= (secrets find (p => p == sk)).get
      case None     => throw new Exception("Unable to find address in keyring")
    }
  }

  /**
   * @param password
   * @param mnemonic
   * @param lang
   * @return
   */
  def importPhrase(password: String, mnemonic: String, lang: String)(implicit sg: SecretGenerator[S]): Try[Address] =
    Failure(new Exception("Not yet implemented"))

//  // JAA - 20210301 - Disabling for now so I can decouple the crypto package from Bifrost
//    Try {
//      // create the BIP object used to verify the chosen language
//      val bip = Bip39(lang)
//
//      // ensure the phrase is valid
//      if (!bip.phraseCheckSum(mnemonic)) throw new Error("Not a valid input phrase!")
//
//      // calculate the new keyfile and return
//      val seed = bip.hexToUuid(bip.phraseToHex(mnemonic))
//      val sk = sg.generateSecret(Blake2b256(seed))
//
//      // add secret to the keyring
//      secrets += sk._1
//
//      // return the public image of the key that was added
//      sk._2.address
//    }

  /** Find a secret given it's public image */
  private def secretByAddress(addr: Address): Option[S] =
    secrets.find(_.publicImage.address == addr)

  object DiskOps {

    /**
     * generate a new random key pair and save to disk
     *
     * @param password
     */
    def generateKeyFile(password: Latin1Data): Try[Address] =
      generateNewKeyPairs().map { sk =>
        exportKeyfileToDisk(sk.head.publicImage.address, password)
        sk.head.publicImage.address
      }

    /**
     * Given am address and password, unlock the associated key file.
     *
     * @param address  Base58 encoded address of the key to unlock
     * @param password - password for the given public key.
     */
    def unlockKeyFile(address: Base58Data, password: Latin1Data): Try[Address] = {
      val keyfile = checkValid(address, password)
      importKeyPairSafe(keyfile, password)
    }

    /**
     * @param address
     * @param password
     * @return
     */
    private def exportKeyfileToDisk(address: Address, password: Latin1Data): Try[Unit] =
      (for {
        secret <- secretByAddress(address)
        keyDir <- keyDirectory.map(_.getAbsolutePath)
        _      <- saveToDiskSafe(keyDir, password, secret).toOption
      } yield ()) match {
        case Some(_) => Success(())
        case None    => Failure(new Exception("Failed to export key to disk"))
      }

    /** Return a list of KeuFile instances for all keys in the key file directory */
    private def listKeyFiles(): Option[List[KF]] = for {
      keyDir       <- keyDirectory
      listContents <- KeyRing.getListOfFiles(keyDir)
    } yield listContents.map(file => readFile(file.getPath))

    /**
     * Check if given publicKey string is valid and contained in the key file directory
     *
     * @param address Base58 encoded public key to query
     * @param password        password used to decrypt the keyfile
     * @return the relevant PrivateKey25519 to be processed
     */
    private def checkValid(address: Base58Data, password: Latin1Data): KF =
      listKeyFiles()
        .map {
          _.filter {
            _.address == address.decodeAddress.getOrThrow()
          }
        } match {
        case Some(listOfKeyfiles) =>
          require(listOfKeyfiles.size == 1, s"Cannot find a unique matching keyfile in $keyDirectory")
          listOfKeyfiles.head

        case None => throw new Exception("Unable to find valid keyfile matching the given address")
      }
  }
}

object KeyRing {

  def empty[
    S <: Secret,
    KF <: Keyfile[S]
  ](path:             Option[String] = None)(implicit
    networkPrefix:    NetworkPrefix,
    secretGenerator:  SecretGenerator[S],
    keyfileCompanion: KeyfileCompanion[S, KF]
  ): KeyRing[S, KF] =
    path match {
      case Some(value) =>
        val dir = new File(value)
        dir.mkdirs()
        new KeyRing(Some(dir), Set())(networkPrefix, secretGenerator, keyfileCompanion)

      case None => new KeyRing(None, Set())
    }

  def getListOfFiles(dir: File): Option[List[File]] =
    if (dir.exists && dir.isDirectory) {
      val contents = dir.listFiles.filter(_.isFile)
      Some(contents.toList)
    } else None
}
