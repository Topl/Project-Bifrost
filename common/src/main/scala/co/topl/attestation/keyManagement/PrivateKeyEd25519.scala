package co.topl.attestation.keyManagement

import cats.implicits._
import co.topl.attestation.{PublicKeyPropositionEd25519, SignatureEd25519}
import co.topl.crypto.implicits._
import co.topl.crypto.signatures.Ed25519
import co.topl.crypto.{PrivateKey, PublicKey}
import co.topl.utils.serialization.{BifrostSerializer, Reader, Writer}

case class PrivateKeyEd25519(private val privateKey: PrivateKey, private val publicKey: PublicKey) extends Secret {

  private val privateKeyLength = privateKey.value.length
  private val publicKeyLength = publicKey.value.length

  require(privateKeyLength == Ed25519.KeyLength, s"$privateKeyLength == ${Ed25519.KeyLength}")
  require(publicKeyLength == Ed25519.KeyLength, s"$publicKeyLength == ${Ed25519.KeyLength}")

  override type S = PrivateKeyEd25519
  override type PK = PublicKeyPropositionEd25519
  override type PR = SignatureEd25519
  override type KF = KeyfileEd25519

  override lazy val serializer: BifrostSerializer[PrivateKeyEd25519] = PrivateKeyEd25519

  override lazy val publicImage: PublicKeyPropositionEd25519 = PublicKeyPropositionEd25519(publicKey)

  override def sign(message: Array[Byte]): SignatureEd25519 = SignatureEd25519(
    Ed25519.sign(privateKey, message)
  )

  override def equals(obj: Any): Boolean = obj match {
    case sk: PrivateKeyEd25519 => sk.privateKey === privateKey
    case _                     => false
  }
}

object PrivateKeyEd25519 extends BifrostSerializer[PrivateKeyEd25519] {

  implicit val secretGenerator: SecretGenerator[PrivateKeyEd25519] =
    SecretGenerator.instance[PrivateKeyEd25519] { seed: Array[Byte] =>
      val (sk, pk) = Ed25519.createKeyPair(seed)
      val secret: PrivateKeyEd25519 = PrivateKeyEd25519(sk, pk)
      secret -> secret.publicImage
    }

  override def serialize(obj: PrivateKeyEd25519, w: Writer): Unit = {
    /* privKeyBytes: Array[Byte] */
    w.putBytes(obj.privateKey.value)

    /* publicKeyBytes: Array[Byte] */
    w.putBytes(obj.publicKey.value)
  }

  override def parse(r: Reader): PrivateKeyEd25519 =
    PrivateKeyEd25519(PrivateKey(r.getBytes(Ed25519.KeyLength)), PublicKey(r.getBytes(Ed25519.KeyLength)))

}
