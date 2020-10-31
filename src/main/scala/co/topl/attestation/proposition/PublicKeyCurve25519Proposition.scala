package co.topl.attestation.proposition

import co.topl.attestation.AddressEncoder.NetworkPrefix
import co.topl.attestation.Evidence.{ EvidenceContent, EvidenceTypePrefix }
import co.topl.attestation.secrets.PrivateKeyCurve25519
import co.topl.attestation.{ Address, Evidence, EvidenceProducer }
import io.circe.syntax.EncoderOps
import io.circe.{ Decoder, Encoder, KeyDecoder, KeyEncoder }
import scorex.crypto.hash.Blake2b256
import scorex.crypto.signatures.{ Curve25519, PublicKey }

import scala.util.{ Failure, Success }

case class PublicKeyCurve25519Proposition ( private[proposition] val pubKeyBytes: PublicKey )
  extends KnowledgeProposition[PrivateKeyCurve25519] {

  require(pubKeyBytes.length == Curve25519.KeyLength,
          s"Incorrect pubKey length, ${Curve25519.KeyLength} expected, ${pubKeyBytes.length} found")

  def address (implicit networkPrefix: NetworkPrefix): Address = Address.from(this)

}

object PublicKeyCurve25519Proposition {
  // type prefix used for address creation
  val typePrefix: EvidenceTypePrefix = 1: Byte

  def apply(str: String): PublicKeyCurve25519Proposition =
    Proposition.fromString(str) match {
      case Success(pk) => pk
      case Failure(ex) => throw ex
    }

  implicit val evidenceOfKnowledge: EvidenceProducer[PublicKeyCurve25519Proposition] =
    EvidenceProducer.instance[PublicKeyCurve25519Proposition] {
      prop: PublicKeyCurve25519Proposition => Evidence(typePrefix, EvidenceContent @@ Blake2b256(prop.bytes))
    }

  // see circe documentation for custom encoder / decoders
  // https://circe.github.io/circe/codecs/custom-codecs.html
  implicit val jsonEncoder: Encoder[PublicKeyCurve25519Proposition] = (prop: PublicKeyCurve25519Proposition) => prop.toString.asJson
  implicit val jsonKeyEncoder: KeyEncoder[PublicKeyCurve25519Proposition] = (prop: PublicKeyCurve25519Proposition) => prop.toString
  implicit val jsonDecoder: Decoder[PublicKeyCurve25519Proposition] = Decoder.decodeString.map(apply)
  implicit val jsonKeyDecoder: KeyDecoder[PublicKeyCurve25519Proposition] = (str: String) => Some(apply(str))
}
