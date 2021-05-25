package attestation

import co.topl.crypto.implicits._
import co.topl.crypto.{PrivateKey, PublicKey}
import co.topl.crypto.signatures.Curve25519
import cats.implicits._
import crypto.KeyfileCurve25519
import utils.serialization.{GjalSerializer, Reader, Writer}

/**
 * A Secret corresponding to a PublicKeyCurve25519 proposition.
 * @param privKey - array of bytes that form the private key
 * @param publicKey - array of bytes that form the public key
 */
case class PrivateKeyCurve25519(private val privKey: PrivateKey, private val publicKey: PublicKey) extends Secret {

  private val privateKeyLength = privKey.value.length
  private val publicKeyLength = publicKey.value.length

  require(privateKeyLength == Curve25519.KeyLength, s"$privateKeyLength == ${Curve25519.KeyLength}")
  require(publicKeyLength == Curve25519.KeyLength, s"$publicKeyLength == ${Curve25519.KeyLength}")

  override type S = PrivateKeyCurve25519
  override type PK = PublicKeyPropositionCurve25519
  override type PR = SignatureCurve25519
  override type KF = KeyfileCurve25519

  override lazy val serializer: GjalSerializer[PrivateKeyCurve25519] = PrivateKeyCurve25519

  override lazy val publicImage: PublicKeyPropositionCurve25519 = PublicKeyPropositionCurve25519(publicKey)

  override def sign(message: Array[Byte]): SignatureCurve25519 = SignatureCurve25519(
    Curve25519.sign(privKey, message)
  )

  override def equals(obj: Any): Boolean = obj match {
    case sk: PrivateKeyCurve25519 => sk.privKey === privKey
    case _                        => false
  }
}

object PrivateKeyCurve25519 extends GjalSerializer[PrivateKeyCurve25519] {

  implicit val secretGenerator: SecretGenerator[PrivateKeyCurve25519] =
    SecretGenerator.instance[PrivateKeyCurve25519] { seed: Array[Byte] =>
      val (sk, pk) = Curve25519.createKeyPair(seed)
      val secret: PrivateKeyCurve25519 = PrivateKeyCurve25519(sk, pk)
      secret -> secret.publicImage
    }

  override def serialize(obj: PrivateKeyCurve25519, w: Writer): Unit = {
    /* privKey: Array[Byte] */
    w.putBytes(obj.privKey.value)

    /* publicKey: Array[Byte] */
    w.putBytes(obj.publicKey.value)
  }

  override def parse(r: Reader): PrivateKeyCurve25519 =
    PrivateKeyCurve25519(PrivateKey(r.getBytes(Curve25519.KeyLength)), PublicKey(r.getBytes(Curve25519.KeyLength)))

}
