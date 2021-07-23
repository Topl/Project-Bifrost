package co.topl.attestation.keyManagement.stakingKeys

import com.google.common.primitives.{Bytes, Ints, Longs}
import co.topl.crypto.kes.{Empty, KeyEvolvingSignature, Leaf, Node, Tree}
import co.topl.crypto.signatures.eddsa.ECVRF25519
import co.topl.crypto.hash.FastCryptographicHash
import io.circe.parser.parse
import io.circe.syntax._
import io.circe.{Decoder, HCursor, Json}
import org.bouncycastle.crypto.BufferedBlockCipher
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.generators.SCrypt
import org.bouncycastle.crypto.modes.SICBlockCipher
import org.bouncycastle.crypto.params.{KeyParameter, ParametersWithIV}
import co.topl.utils.encode.Base58

import java.io.{BufferedWriter, File, FileWriter}
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.util.{Failure, Success, Try}

/**
  * AMS 2021:
  * File loader for staking keys, updates KES key with secure erasure on disk and encrypts/decrypts staking keys
  * @param ledger_id ledger public address of the forging account
  * @param vrf_info encryption info for VRF keypair
  * @param kes_info encryption info for KES keypair
  * @param fileName this key configuration on disk
  * @param oldFileName previous key configuration on disk as backup
  */

case class StakingKeyFile(ledger_id:Array[Byte],
                          vrf_info:CipherInfo,
                          kes_info:CipherInfo,
                          fileName:String,
                          oldFileName:String) {
  import StakingKeyFile._

  /**
    * Decrypt the staking keys from vrf_info and kes_info cipher information
    * @param password pass phrase used in AES encryption scheme
    * @param kes instantiated Key Evolving Signature scheme
    * @param vrf instantiated Verifiable Random Function scheme
    * @return Some(StakingKeys) if password is correct, None if error in decryption
    */
  def getStakingKeys(password:String,kes:KeyEvolvingSignature,vrf:ECVRF25519):Option[StakingKeys] = Try{
    StakingKeys(ledger_id,decryptVrfSK(password,vrf).get,vrf_info.pubKey,decryptKesSK(password,kes).get,kes_info.pubKey)
  }.toOption

  private def decryptVrfSK(password: String, vrf:ECVRF25519): Option[Array[Byte]] = Try{
    val derivedKey = getDerivedKey(password, vrf_info.salt)
    val macTest = fch.hash(derivedKey.slice(16, 32) ++ vrf_info.cipherText)
    require(macTest sameElements vrf_info.mac, "Error: MAC does not match. Try again")
    val (decrypted, _) = getAESResult(derivedKey, vrf_info.iv, vrf_info.cipherText, encrypt = false)
    val pk_test = Array.fill(vrf.PUBLIC_KEY_SIZE){0x00.toByte}
    vrf.generatePublicKey(decrypted,0,pk_test,0)
    require(vrf_info.pubKey sameElements pk_test, "Error: PublicKey in file is invalid")
    decrypted
  }.toOption

  private def decryptKesSK(password: String, kes:KeyEvolvingSignature): Option[ForgingKey] = Try{
    val kes_sk_MK = {
      val derivedKey = getDerivedKey(password, kes_info.salt)
      val (decrypted, mac_check) = decryptAES(derivedKey, kes_info.iv, kes_info.cipherText)
      require(mac_check sameElements kes_info.mac, "Error: MAC does not match")
      val byteStream = new ByteStream(decrypted,None)
      val numBytes = byteStream.getInt
      val decryptedMK = serializer.fromBytes(
        new ByteStream(byteStream.get(numBytes),DeserializeForgingKey)
      ) match {case mk:ForgingKey => mk}
      require(kes_info.pubKey sameElements decryptedMK.getPublicKey(kes), "Error: PublicKey in file is invalid")
      decryptedMK
    }
    kes_sk_MK
  }.toOption

  /**
    * Json representation written to disk
    */

  lazy val json: Json = {
    val map0 = Map("oldFileName" -> oldFileName.asJson,"fileName" -> fileName.asJson)
    val map1 = {
      Map("publicKey_ledger" -> Base58.encode(ledger_id).asJson)
    }
    val map2 = {
      Map(
        "crypto_vrf" -> Map(
          "cipher" -> "aes-256-ctr".asJson,
          "cipherParams" -> Map(
            "iv" -> Base58.encode(vrf_info.iv).asJson
          ).asJson,
          "cipherText" -> Base58.encode(vrf_info.cipherText).asJson,
          "kdf" -> "scrypt".asJson,
          "kdfSalt" -> Base58.encode(vrf_info.salt).asJson,
          "mac" -> Base58.encode(vrf_info.mac).asJson
        ).asJson,
        "publicKey_vrf" -> Base58.encode(vrf_info.pubKey).asJson,
      )
    }
    val map3 = {
      Map(
        "crypto_kes" -> Map(
          "cipher" -> "aes-256-ctr".asJson,
          "cipherParams" -> Map(
            "iv" -> Base58.encode(kes_info.iv).asJson
          ).asJson,
          "cipherText" -> Base58.encode(kes_info.cipherText).asJson,
          "kdf" -> "scrypt".asJson,
          "kdfSalt" -> Base58.encode(kes_info.salt).asJson,
          "mac" -> Base58.encode(kes_info.mac).asJson
        ).asJson,
        "publicKey_kes" -> Base58.encode(kes_info.pubKey).asJson
      )
    }
    (map0++map1++map2++map3).asJson
  }
}

object StakingKeyFile {

  /**
    * Create a new set of staking keys and save it to the specified directory
    * @param ledger_id ledger commitment unique identifier
    * @param password pass phrase used in AES encryption scheme
    * @param defaultKeyDir file folder location for storage
    * @param vrf instantiated Verifiable Random Function scheme
    * @param kes instantiated Key Evolving Signature scheme
    * @param slot the current global slot of the protocol that sets the minimum valid slot for the staking keys
    * @return new staking key file with randomly generated public private key pairs
    */
  def newFromSeed(
                   ledger_id:Array[Byte],
                   password:String,
                   defaultKeyDir: String,
                   vrf:ECVRF25519,
                   kes:KeyEvolvingSignature,
                   slot:Long
                 ):StakingKeyFile = {
    val newKeys = StakingKeys(fch.hash(uuid)++fch.hash(uuid),ledger_id,vrf,kes,slot)
    val vrf_info = {
      val salt = fch.hash(uuid)
      val ivData = fch.hash(uuid).slice(0, 16)
      val derivedKey = getDerivedKey(password, salt)
      val (cipherText, mac) = getAESResult(derivedKey, ivData, newKeys.get.sk_vrf, encrypt = true)
      CipherInfo(newKeys.get.pk_vrf, cipherText, mac, salt, ivData)
    }
    val kes_info = {
      val salt = fch.hash(uuid)
      val ivData = fch.hash(uuid).slice(0, 16)
      val derivedKey = getDerivedKey(password, salt)
      val keyBytes:Array[Byte] = serializer.getBytes(newKeys.get.sk_kes)
      val (cipherText, mac) = encryptAES(derivedKey, ivData, keyBytes)
      CipherInfo(newKeys.get.pk_kes, cipherText, mac, salt, ivData)
    }
    val dateString = Instant.now().truncatedTo(ChronoUnit.MILLIS).toString.replace(":", "-")
    val fileName = s"$defaultKeyDir/$dateString-${Base58.encode(newKeys.get.fullPublicAddress)}.json"
    val newKeyFile = new StakingKeyFile(ledger_id,vrf_info,kes_info,fileName,"NEWKEY")
    val file = new File(fileName)
    file.getParentFile.mkdirs
    val w = new BufferedWriter(new FileWriter(file))
    w.write(newKeyFile.json.toString())
    w.close()
    newKeyFile
  }

  /**
    * Update procedure with secure erasure of old files
    * @param keyFile old staking key file to be updated
    * @param forgingKey forging key at a time step higher than that presently in keyFile
    * @param password pass phrase used in AES encryption scheme
    * @param defaultKeyDir file folder location for storage
    * @param salt random bytes used to as an input to derivedKey
    * @param derivedKey hardened encryption key input to AES scheme
    * @return
    */

  def updateStakingKeyFile(
              keyFile:StakingKeyFile,
              forgingKey: ForgingKey,
              password:String,
              defaultKeyDir: String,
              salt:Array[Byte] = fch.hash(uuid),
              derivedKey:Array[Byte] = Array()
            ):Option[StakingKeyFile] = Try {
    val ledger_id = keyFile.ledger_id
    val vrf_info = keyFile.vrf_info
    val kes_info = {
      val ivData = fch.hash(uuid).slice(0, 16)
      val (cipherText, mac) = if (derivedKey.isEmpty) {
        encryptAES(getDerivedKey(password, salt), ivData, serializer.getBytes(forgingKey))
      } else {
        encryptAES(derivedKey, ivData, serializer.getBytes(forgingKey))
      }
      CipherInfo(keyFile.kes_info.pubKey, cipherText, mac, salt, ivData)
    }
    val dateString = Instant.now().truncatedTo(ChronoUnit.MILLIS).toString.replace(":", "-")
    val fileName = s"$defaultKeyDir/" +
      s"$dateString-${Base58.encode(ledger_id ++ keyFile.vrf_info.pubKey ++ keyFile.kes_info.pubKey)}.json"
    val newKeyFile = StakingKeyFile(ledger_id,vrf_info,kes_info,fileName,keyFile.fileName)
    val w = new BufferedWriter(new FileWriter(fileName))
    w.write(newKeyFile.json.toString())
    w.close()
    deleteOldFile(keyFile.oldFileName)
    newKeyFile
  }.toOption

  /**
    * Parses specified key file into a staking key file
    * @param filename path of the json file
    * @return
    */

  def readFile(filename:String): StakingKeyFile = {
    val jsonString:String = {
      val src = scala.io.Source.fromFile(filename)
      val out = src.mkString
      src.close()
      out
    }
    parse(jsonString).right.get.as[StakingKeyFile] match {
      case Right(f: StakingKeyFile) => f
      case Left(e) => throw new Exception(s"Could not parse KeyFile: $e")
    }
  }

  /**
    * Restores the most recent staking key file in the specified directory
    * @param storageDir folder containing one or more json key files
    * @return
    */

  def restore(storageDir:String): Option[StakingKeyFile] = {
    def getListOfFiles(dir: String):List[File] = {
      val d = new File(dir)
      if (d.exists && d.isDirectory) {
        d.listFiles.filter(_.isFile).toList
      } else {
        List[File]()
      }
    }
    var recoveredKey:Option[StakingKeyFile] = None
    var files = getListOfFiles(s"$storageDir/")
    while (files.nonEmpty) {
      Try{readFile(files.head.getPath)} match {
        case Success(keyFile:StakingKeyFile) =>
          recoveredKey match {
            case None => recoveredKey = Some(keyFile)
            case _ => deleteOldFile(files.head.getPath)
          }
          files = files.tail
        case Failure(_) =>
          deleteOldFile(files.head.getPath)
          files = files.tail
      }
    }
    recoveredKey
  }

  private case object DeserializeForgingKey

  private class Serializer {

    val pk_length: Int = 32
    val sig_length: Int = 64
    val hash_length: Int = 32

    def getBytes(forgingKey:ForgingKey):Array[Byte] = sForgingKey(forgingKey)

    def fromBytes(input:ByteStream): Any = dForgingKey(input)

    private def sForgingKey(key: ForgingKey):Array[Byte] = {
      Bytes.concat(
        sTree(key.L),
        sTree(key.Si),
        Ints.toByteArray(key.sig.length),
        key.sig,
        key.pki,
        key.rp,
        Longs.toByteArray(key.offset)
      )
    }

    private def dForgingKey(stream:ByteStream):ForgingKey = {
      val out1len = stream.getInt
      val out1Bytes = new ByteStream(stream.get(out1len),stream.caseObject)
      val out1 = dTree(out1Bytes)
      val out2len = stream.getInt
      val out2Bytes = new ByteStream(stream.get(out2len),stream.caseObject)
      val out2 = dTree(out2Bytes)
      val out3len = stream.getInt
      val out3 = stream.get(out3len)
      val out4 = stream.get(pk_length)
      val out5 = stream.get(hash_length)
      val out6 = stream.getLong
      assert(stream.empty)
      ForgingKey(out1,out2,out3,out4,out5,out6)
    }

    private def sTree(tree:Tree[Array[Byte]]):Array[Byte] = {
      def treeToBytes(t:Tree[Array[Byte]]):Array[Byte] = {
        t match {
          case n:Node[Array[Byte]] =>
            n.l match {
              case Empty =>
                n.r match {
                  case ll:Leaf[Array[Byte]] =>
                    Ints.toByteArray(2) ++ n.v ++ Ints.toByteArray(0) ++ ll.v
                  case nn:Node[Array[Byte]] =>
                    Ints.toByteArray(2) ++ n.v ++ treeToBytes(nn)
                }
              case ll:Leaf[Array[Byte]] =>
                Ints.toByteArray(1) ++ n.v ++ Ints.toByteArray(0) ++ ll.v
              case nn:Node[Array[Byte]] =>
                Ints.toByteArray(1) ++ n.v ++ treeToBytes(nn)
            }
          case l:Leaf[Array[Byte]] =>
            Ints.toByteArray(0) ++ l.v
        }
      }
      val output = treeToBytes(tree)
      Ints.toByteArray(output.length) ++ output
    }

    private def dTree(stream:ByteStream):Tree[Array[Byte]] = {
      def buildTree:Tree[Array[Byte]] = {
        stream.getInt match {
          case 0 =>
            val bytes:Array[Byte] = stream.get(sig_length)
            Leaf(bytes)
          case 1 =>
            val bytes:Array[Byte] = stream.get(hash_length+sig_length)
            Node(bytes,buildTree,Empty)
          case 2 =>
            val bytes:Array[Byte] = stream.get(hash_length+sig_length)
            Node(bytes,Empty,buildTree)
        }
      }
      val out = buildTree
      assert(stream.empty)
      out
    }
  }

  private class ByteStream(var data:Array[Byte],co:Any) {
    def get(n:Int):Array[Byte] = {
      if (n>data.length) println("Error: ByteStream reached early end of stream")
      assert(n<=data.length)
      val out = data.take(n)
      data = data.drop(n)
      out
    }
    def getAll:Array[Byte] = {
      val out = data
      data = Array()
      out
    }
    def getInt: Int = {
      Ints.fromByteArray(get(4))
    }
    def getLong: Long = {
      Longs.fromByteArray(get(8))
    }
    def empty:Boolean = data.isEmpty
    def length:Int = data.length
    def caseObject:Any = co
  }

  private val serializer:Serializer = new Serializer

  private val fch = new FastCryptographicHash

  private def getDerivedKey(password: String, salt: Array[Byte]): Array[Byte] = {
    SCrypt.generate(password.getBytes(StandardCharsets.UTF_8), salt, scala.math.pow(2, 14).toInt, 8, 1, 32)
  }

  private def getAESResult(derivedKey: Array[Byte], ivData: Array[Byte], inputText: Array[Byte], encrypt: Boolean):
  (Array[Byte], Array[Byte]) = {
    val cipherParams = new ParametersWithIV(new KeyParameter(derivedKey), ivData)
    val aesCtr = new BufferedBlockCipher(new SICBlockCipher(new AESEngine))
    aesCtr.init(encrypt, cipherParams)
    val outputText = Array.fill(32)(1: Byte)
    aesCtr.processBytes(inputText, 0, inputText.length, outputText, 0)
    aesCtr.doFinal(outputText, 0)
    (outputText, fch.hash(derivedKey.slice(16, 32) ++ outputText))
  }

  private def encryptAES(derivedKey: Array[Byte], ivData: Array[Byte], inputText: Array[Byte]):
  (Array[Byte], Array[Byte])= {
    val cipherParams = new ParametersWithIV(new KeyParameter(derivedKey), ivData)
    val aesCtr = new BufferedBlockCipher(new SICBlockCipher(new AESEngine))
    aesCtr.init(true, cipherParams)
    val inBytes = Ints.toByteArray(inputText.length) ++ inputText
    val outputText = Array.fill(16*(inBytes.length/16+1)){0x00.toByte}
    val outLen:Int = aesCtr.processBytes(inBytes, 0, inBytes.length, outputText, 0)
    val outLen2:Int = aesCtr.doFinal(outputText, outLen)
    (outputText, fch.hash(derivedKey.slice(16, 32) ++ outputText))
  }

  private def decryptAES(derivedKey: Array[Byte], ivData: Array[Byte], inputText: Array[Byte]):
  (Array[Byte], Array[Byte])= {
    val cipherParams = new ParametersWithIV(new KeyParameter(derivedKey), ivData)
    val aesCtr = new BufferedBlockCipher(new SICBlockCipher(new AESEngine))
    aesCtr.init(false, cipherParams)
    val outputText = Array.fill(inputText.length){0x00.toByte}
    val outLen:Int = aesCtr.processBytes(inputText, 0, inputText.length, outputText, 0)
    val outLen2:Int = aesCtr.doFinal(outputText, outLen)
    (outputText, fch.hash(derivedKey.slice(16, 32) ++ inputText))
  }

  private def uuid: String = java.util.UUID.randomUUID.toString

  private def deleteOldFile(filename:String):Unit = {
    filename match {
      case "NEWKEY" =>
      case _ => new File(filename).delete()
    }
  }

  private implicit val decodeKeyFile: Decoder[StakingKeyFile] = (c: HCursor) => for {
    fileName <- c.downField("fileName").as[String]
    oldFileName <- c.downField("oldFileName").as[String]
    publicKey_ledger <-     c.downField("publicKey_ledger").as[String]
    pubKeyString_vrf <-     c.downField("publicKey_vrf").as[String]
    cipherTextString_vrf <-    c.downField("crypto_vrf").downField("cipherText").as[String]
    macString_vrf <-           c.downField("crypto_vrf").downField("mac").as[String]
    saltString_vrf <-          c.downField("crypto_vrf").downField("kdfSalt").as[String]
    ivString_vrf <-            c.downField("crypto_vrf").downField("cipherParams").downField("iv").as[String]
    pubKeyString_kes <-     c.downField("publicKey_kes").as[String]
    cipherTextString_kes <-    c.downField("crypto_kes").downField("cipherText").as[String]
    macString_kes <-           c.downField("crypto_kes").downField("mac").as[String]
    saltString_kes <-          c.downField("crypto_kes").downField("kdfSalt").as[String]
    ivString_kes <-            c.downField("crypto_kes").downField("cipherParams").downField("iv").as[String]
  } yield {
    val ledger_id = Base58.decode(publicKey_ledger).toOption.get
    val vrf_info = CipherInfo(
        Base58.decode(pubKeyString_vrf).toOption.get,
        Base58.decode(cipherTextString_vrf).toOption.get,
        Base58.decode(macString_vrf).toOption.get,
        Base58.decode(saltString_vrf).toOption.get,
        Base58.decode(ivString_vrf).toOption.get
      )
    val kes_info = CipherInfo(
        Base58.decode(pubKeyString_kes).toOption.get,
        Base58.decode(cipherTextString_kes).toOption.get,
        Base58.decode(macString_kes).toOption.get,
        Base58.decode(saltString_kes).toOption.get,
        Base58.decode(ivString_kes).toOption.get
      )
    new StakingKeyFile(ledger_id,vrf_info,kes_info,fileName,oldFileName)
  }
}