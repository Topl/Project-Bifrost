package co.topl.crypto.signatures

import co.topl.crypto
import co.topl.crypto.{PrivateKey, PublicKey}
import co.topl.crypto.hash.sha256
import co.topl.crypto.signatures.eddsa.BouncyCastleEd25519

import java.security.SecureRandom

object Ed25519 extends EllipticCurveSignatureScheme {
  val ec = new BouncyCastleEd25519
  override val SignatureLength: Int = ec.SIGNATURE_SIZE
  override val KeyLength: Int = ec.SECRET_KEY_SIZE

  override def createKeyPair(seed: Array[Byte]): (crypto.PrivateKey, crypto.PublicKey) = {
    val sk: Array[Byte] = new Array[Byte](ec.SECRET_KEY_SIZE)
    val pk: Array[Byte] = new Array[Byte](ec.PUBLIC_KEY_SIZE)
    val hashedSeed = sha256.hash(seed)
    val random = SecureRandom.getInstance("SHA1PRNG")

    random.setSeed(hashedSeed.value)

    ec.generatePrivateKey(random, sk)
    ec.generatePublicKey(sk, 0, pk, 0)
    (PrivateKey(sk), PublicKey(pk))
  }

  override def sign(privateKey: crypto.PrivateKey, message: MessageToSign): Signature = {
    require(privateKey.value.length == ec.SECRET_KEY_SIZE)
    val sig = new Array[Byte](ec.SIGNATURE_SIZE)
    ec.sign(privateKey.value, 0, message, 0, message.length, sig, 0)
    Signature(sig)
  }

  override def verify(signature: Signature, message: MessageToSign, publicKey: crypto.PublicKey): Boolean =
    signature.value.length == ec.SIGNATURE_SIZE &&
    publicKey.value.length == ec.PUBLIC_KEY_SIZE &&
    ec.verify(signature.value, 0, publicKey.value, 0, message, 0, message.length)
}
