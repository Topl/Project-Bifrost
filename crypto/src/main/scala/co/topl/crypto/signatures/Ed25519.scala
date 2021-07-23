package co.topl.crypto.signatures

import co.topl.crypto.hash.sha256
import co.topl.crypto.{PrivateKey, PublicKey}

import java.security.SecureRandom

class Ed25519 extends eddsa.Ed25519 with EllipticCurveSignatureScheme {
  override val SignatureLength: Int = SIGNATURE_SIZE
  override val KeyLength: Int = SECRET_KEY_SIZE

  override def createKeyPair(seed: Array[Byte]): (PrivateKey, PublicKey) = {
    val sk: Array[Byte] = new Array[Byte](SECRET_KEY_SIZE)
    val pk: Array[Byte] = new Array[Byte](PUBLIC_KEY_SIZE)
    val hashedSeed = sha256.hash(seed)
    val random = SecureRandom.getInstance("SHA1PRNG")

    random.setSeed(hashedSeed.value)

    generatePrivateKey(random, sk)
    generatePublicKey(sk, 0, pk, 0)
    (PrivateKey(sk), PublicKey(pk))
  }

  override def sign(privateKey: PrivateKey, message: MessageToSign): Signature = {
    require(privateKey.value.length == SECRET_KEY_SIZE)
    val sig = new Array[Byte](SIGNATURE_SIZE)
    sign(privateKey.value, 0, message, 0, message.length, sig, 0)
    Signature(sig)
  }

  override def verify(signature: Signature, message: MessageToSign, publicKey: PublicKey): Boolean =
    signature.value.length == SIGNATURE_SIZE &&
    publicKey.value.length == PUBLIC_KEY_SIZE &&
    verify(signature.value, 0, publicKey.value, 0, message, 0, message.length)
}

object Ed25519 {
  val SignatureLength: Int = 64
  val KeyLength: Int = 32
}
