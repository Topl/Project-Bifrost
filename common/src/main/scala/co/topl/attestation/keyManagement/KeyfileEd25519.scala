package co.topl.attestation.keyManagement

import cats.implicits._
import co.topl.attestation.Address
import co.topl.crypto.{PrivateKey, PublicKey}
import co.topl.crypto.hash.blake2b256
import co.topl.crypto.signatures.Ed25519
import co.topl.utils.codecs.implicits._
import co.topl.utils.Extensions.StringOps
import co.topl.utils.NetworkType.NetworkPrefix
import co.topl.utils.SecureRandom.randomBytes
import co.topl.utils.StringDataTypes.implicits._
import co.topl.utils.StringDataTypes.{Base58Data, Latin1Data}
import co.topl.utils.encode.Base58
import io.circe.parser.parse
import io.circe.syntax._
import io.circe.{Decoder, Encoder, HCursor}
import org.bouncycastle.crypto.BufferedBlockCipher
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.generators.SCrypt
import org.bouncycastle.crypto.modes.SICBlockCipher
import org.bouncycastle.crypto.params.{KeyParameter, ParametersWithIV}

import scala.util.Try

case class KeyfileEd25519(
  address:    Address,
  cipherText: Array[Byte],
  mac:        Array[Byte],
  salt:       Array[Byte],
  iv:         Array[Byte]
) extends Keyfile[PrivateKeyEd25519]

object KeyfileEd25519 {

  implicit val jsonEncoder: Encoder[KeyfileEd25519] = { kf: KeyfileEd25519 =>
    Map(
      "crypto" -> Map(
        "cipher"       -> "aes-256-ctr".asJson,
        "cipherParams" -> Map("iv" -> kf.iv.encodeAsBase58).asJson,
        "cipherText"   -> kf.cipherText.encodeAsBase58.asJson,
        "kdf"          -> "scrypt".asJson,
        "kdfSalt"      -> kf.salt.encodeAsBase58.asJson,
        "mac"          -> kf.mac.encodeAsBase58.asJson
      ).asJson,
      "address" -> kf.address.asJson
    ).asJson
  }

  implicit def jsonDecoder(implicit networkPrefix: NetworkPrefix): Decoder[KeyfileEd25519] = (c: HCursor) =>
    for {
      address    <- c.downField("address").as[Address]
      cipherText <- c.downField("crypto").downField("cipherText").as[Base58Data]
      mac        <- c.downField("crypto").downField("mac").as[Base58Data]
      salt       <- c.downField("crypto").downField("kdfSalt").as[Base58Data]
      iv         <- c.downField("crypto").downField("cipherParams").downField("iv").as[Base58Data]
    } yield {
      implicit val netPrefix: NetworkPrefix = address.networkPrefix
      new KeyfileEd25519(address, cipherText.value, mac.value, salt.value, iv.value)
    }
}

object KeyfileEd25519Companion extends KeyfileCompanion[PrivateKeyEd25519, KeyfileEd25519] {

  /**
   * Create a keyfile from the provided seed and save it to disk
   *
   * @param password string used to encrypt the private key when saved to disk
   * @return
   */
  override def encryptSecretSafe(secretKey: PrivateKeyEd25519, password: Latin1Data)(implicit
    networkPrefix:                          NetworkPrefix
  ): KeyfileEd25519 = {
    // get random bytes to obfuscate the cipher
    val salt = randomBytes(32)
    val ivData = randomBytes(16)

    // calculate the deterministic key used to create the cipher
    val derivedKey = getDerivedKey(password.show, salt)

    // encrypt private key
    val (cipherText, mac) = getAESResult(derivedKey, ivData, secretKey.bytes, encrypt = true)

    // generate address from the secret key
    val address = Address.from(secretKey.publicImage)

    // create a new instance of the keyfile case class
    new KeyfileEd25519(address, cipherText, mac, salt, ivData)
  }

  override def decryptSecretSafe(encryptedKeyFile: KeyfileEd25519, password: Latin1Data)(implicit
    networkPrefix:                                 NetworkPrefix
  ): Try[PrivateKeyEd25519] = Try {
    val derivedKey = getDerivedKey(password.show, encryptedKeyFile.salt)
    val calcMAC = getMAC(derivedKey, encryptedKeyFile.cipherText)

    require(calcMAC sameElements encryptedKeyFile.mac, "MAC does not match. Try again")

    getAESResult(
      derivedKey,
      encryptedKeyFile.iv,
      encryptedKeyFile.cipherText,
      encrypt = false
    ) match {
      case (cipherBytes, _) =>
        cipherBytes.grouped(Ed25519.KeyLength).toSeq match {
          case Seq(skBytes, pkBytes) =>
            // recreate the private key
            val privateKey = new PrivateKeyEd25519(PrivateKey(skBytes), PublicKey(pkBytes))
            val derivedAddress = Address.from(privateKey.publicImage)
            // check that the address given in the keyfile matches the public key
            require(
              encryptedKeyFile.address == derivedAddress,
              "Derived address does not match that listed in the keyfile"
            )
            privateKey
        }
    }
  }

  /**
   * @param filename
   * @return
   */
  def readFile(filename: String)(implicit networkPrefix: NetworkPrefix): KeyfileEd25519 = {
    // read data from disk
    val src = scala.io.Source.fromFile(filename)

    // attempt to retrieve the required keyfile type from the data that was just read
    val keyfile = parse(src.mkString) match {
      case Left(ex) => throw ex
      case Right(json) =>
        json.as[KeyfileEd25519] match {
          case Left(ex)  => throw new Exception(s"Could not parse KeyFile: $ex")
          case Right(kf) => kf
        }
    }

    // close the stream and return the keyfile
    src.close()
    keyfile
  }

  /**
   * @param password
   * @param salt
   * @return
   */
  private def getDerivedKey(password: String, salt: Array[Byte]): Array[Byte] = {
    val passwordBytes = password.getValidLatin1Bytes.getOrElse(throw new Exception("String is not valid Latin-1"))
    SCrypt.generate(passwordBytes, salt, scala.math.pow(2, 18).toInt, 8, 1, 32)
  }

  /**
   * @param derivedKey
   * @param cipherText
   * @return
   */
  private def getMAC(derivedKey: Array[Byte], cipherText: Array[Byte]): Array[Byte] =
    blake2b256.hash(derivedKey.slice(16, 32) ++ cipherText).value

  /**
   * @param derivedKey
   * @param ivData
   * @param inputText
   * @param encrypt
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

}
