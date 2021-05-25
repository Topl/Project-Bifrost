package co.topl.crypto.signatures

import co.topl.crypto.{PrivateKey, PublicKey, SharedSecret}

import java.security.SecureRandom

/* Forked from https://github.com/input-output-hk/scrypto */

trait SigningFunctions {

  val SignatureLength: Int
  val KeyLength: Int

  def createKeyPair(seed: Array[Byte]): (PrivateKey, PublicKey)

  def createKeyPair: (PrivateKey, PublicKey) = {
    val seed = new Array[Byte](KeyLength)
    new SecureRandom().nextBytes(seed) // modifies seed
    createKeyPair(seed)
  }

  def sign(privateKey: PrivateKey, message: MessageToSign): Signature

  def verify(signature: Signature, message: MessageToSign, publicKey: PublicKey): Boolean

  def createSharedSecret(privateKey: PrivateKey, publicKey: PublicKey): SharedSecret
}
