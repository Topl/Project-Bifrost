package crypto

import java.nio.charset.StandardCharsets

import attestation.{Address, PrivateKeyCurve25519}
import attestation.AddressEncoder.NetworkPrefix
import keymanager.{Keyfile, KeyfileCompanion}
import io.circe.parser.parse
import io.circe.syntax._
import io.circe.{Decoder, Encoder, HCursor}
import org.bouncycastle.crypto.BufferedBlockCipher
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.generators.SCrypt
import org.bouncycastle.crypto.modes.SICBlockCipher
import org.bouncycastle.crypto.params.{KeyParameter, ParametersWithIV}
import scorex.crypto.hash.Blake2b256
import scorex.crypto.signatures.{Curve25519, PrivateKey, PublicKey}
import scorex.util.Random.randomBytes
import scorex.util.encode.Base58

import scala.util.Try

/**
 * Created by cykoz on 6/22/2017.
 */
/**
 * Encrypted key file
 * @param address address associated with keyfile
 * @param cipherText encrypted information
 * @param mac message authentication code
 * @param salt random data used to encrypt key
 * @param iv initialization vector (like nonce)
 */
case class KeyfileCurve25519(
  address:    Address,
  cipherText: Array[Byte],
  mac:        Array[Byte],
  salt:       Array[Byte],
  iv:         Array[Byte]
) extends Keyfile[PrivateKeyCurve25519]

object KeyfileCurve25519 extends KeyfileCompanion[PrivateKeyCurve25519, KeyfileCurve25519] {

  /**
   * Create a keyfile from the provided seed and save it to disk
   * @param secretKey private key for the keyfile
   * @param password string used to encrypt the private key when saved to disk
   * @param networkPrefix a single byte used to identify a network
   *  @return
   */
  def encryptSecret(secretKey: PrivateKeyCurve25519, password: String)(implicit
    networkPrefix:             NetworkPrefix
  ): KeyfileCurve25519 = {
    // get random bytes to obfuscate the cipher
    val salt = randomBytes(32)
    val ivData = randomBytes(16)

    // calculate the deterministic key used to create the cipher
    val derivedKey = getDerivedKey(password, salt)

    // encrypt private key
    val (cipherText, mac) = getAESResult(derivedKey, ivData, secretKey.bytes, encrypt = true)

    // generate address from the secret key
    val address = Address.from(secretKey.publicImage)

    // create a new instance of the keyfile case class
    new KeyfileCurve25519(address, cipherText, mac, salt, ivData)
  }

  /**
   * Attemps to decrypt file - given keyfile and password returns PrivateKey
   * @param encryptedKeyFile - the key file to decrypt
   * @param password - password for the given key file
   * @param networkPrefix - a single byte used to identify a network
   *  @return
   */
  def decryptSecret(encryptedKeyFile: KeyfileCurve25519, password: String)(implicit
    networkPrefix:                    NetworkPrefix
  ): Try[PrivateKeyCurve25519] = Try {
    val derivedKey = KeyfileCurve25519.getDerivedKey(password, encryptedKeyFile.salt)
    val calcMAC = KeyfileCurve25519.getMAC(derivedKey, encryptedKeyFile.cipherText)
    require(calcMAC sameElements encryptedKeyFile.mac, "MAC does not match. Try again")

    KeyfileCurve25519.getAESResult(
      derivedKey,
      encryptedKeyFile.iv,
      encryptedKeyFile.cipherText,
      encrypt = false
    ) match {
      case (cipherBytes, _) =>
        cipherBytes.grouped(Curve25519.KeyLength).toSeq match {
          case Seq(skBytes, pkBytes) =>
            // recreate the private key
            val privateKey = new PrivateKeyCurve25519(PrivateKey @@ skBytes, PublicKey @@ pkBytes)
            val derivedAddress = Address.from(privateKey.publicImage)
            // check that the address given in the keyfile matches the public key
            require(encryptedKeyFile.address == derivedAddress, "PublicKey in file is invalid")
            privateKey
        }
    }
  }

  /**
   * Reads given file and returns the KeyFile
   * @param filename - the file name to be read
   * @return KeyFile associated with the file
   */
  def readFile(filename: String)(implicit networkPrefix: NetworkPrefix): KeyfileCurve25519 = {
    // read data from disk
    val src = scala.io.Source.fromFile(filename)

    // attempt to retrieve the required keyfile type from the data that was just read
    val keyfile = parse(src.mkString) match {
      case Right(json) =>
        json.as[KeyfileCurve25519] match {
          case Right(kf: KeyfileCurve25519) => kf
          case Left(e)                      => throw new Exception(s"Keyfile cannot be instantiated from JSON: $e")
        }
      case Left(e) => throw new Exception(s"Could not parse KeyFile: $e")
    }

    // close the stream and return the keyfile
    src.close()
    keyfile
  }

  /**
   * Grabs deterministic key given a password and salt
   * @param password password to calculate deterministic key
   * @param salt salt used to calculate determinstic key
   * @return
   */
  private def getDerivedKey(password: String, salt: Array[Byte]): Array[Byte] = {
    val passwordBytes = password.getBytes(StandardCharsets.UTF_8)
    SCrypt.generate(passwordBytes, salt, scala.math.pow(2, 18).toInt, 8, 1, 32)
  }

  /**
   * Calculate mac for a given key
   * @param derivedKey the key as array of bytes
   * @param cipherText cipherText for the key to calculate mac
   * @return
   */
  private def getMAC(derivedKey: Array[Byte], cipherText: Array[Byte]): Array[Byte] =
    Blake2b256(derivedKey.slice(16, 32) ++ cipherText)

  /**
   * Generates cipherText and MAC from AES (block cipher)
   * @param derivedKey - determinstic key to encrypt
   * @param ivData - data used to encrypt key
   * @param inputText - bytes used to encrypt key
   * @param encrypt - if set to true, cipher is initialised for encryption, if false for decryption
   * @return
   */
  private def getAESResult(
    derivedKey: Array[Byte],
    ivData:     Array[Byte],
    inputText:  Array[Byte],
    encrypt:    Boolean
  ): (Array[Byte], Array[Byte]) = {
    val cipherParams = new ParametersWithIV(new KeyParameter(derivedKey), ivData)
    val aesCtr = new BufferedBlockCipher(new SICBlockCipher(new AESEngine))
    aesCtr.init(encrypt, cipherParams)

    val outputText = Array.fill(inputText.length)(1: Byte)
    aesCtr.processBytes(inputText, 0, inputText.length, outputText, 0)
    aesCtr.doFinal(outputText, 0)

    val mac = getMAC(derivedKey, outputText)

    (outputText, mac)
  }

  implicit val jsonEncoder: Encoder[KeyfileCurve25519] = { kf: KeyfileCurve25519 =>
    Map(
      "crypto" -> Map(
        "cipher"       -> "aes-128-ctr".asJson,
        "cipherParams" -> Map("iv" -> Base58.encode(kf.iv).asJson).asJson,
        "cipherText"   -> Base58.encode(kf.cipherText).asJson,
        "kdf"          -> "scrypt".asJson,
        "kdfSalt"      -> Base58.encode(kf.salt).asJson,
        "mac"          -> Base58.encode(kf.mac).asJson
      ).asJson,
      "address" -> kf.address.asJson
    ).asJson
  }

  implicit def jsonDecoder(implicit networkPrefix: NetworkPrefix): Decoder[KeyfileCurve25519] = (c: HCursor) =>
    for {
      address          <- c.downField("address").as[Address]
      cipherTextString <- c.downField("crypto").downField("cipherText").as[String]
      macString        <- c.downField("crypto").downField("mac").as[String]
      saltString       <- c.downField("crypto").downField("kdfSalt").as[String]
      ivString         <- c.downField("crypto").downField("cipherParams").downField("iv").as[String]
    } yield {
      val cipherText = Base58.decode(cipherTextString).get
      val mac = Base58.decode(macString).get
      val salt = Base58.decode(saltString).get
      val iv = Base58.decode(ivString).get

      implicit val netPrefix: NetworkPrefix = address.networkPrefix
      new KeyfileCurve25519(address, cipherText, mac, salt, iv)
    }
}
