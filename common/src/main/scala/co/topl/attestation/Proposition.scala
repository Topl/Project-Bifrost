package co.topl.attestation

import co.topl.attestation.Evidence.{EvidenceContent, EvidenceTypePrefix}
import co.topl.attestation.keyManagement.{PrivateKeyCurve25519, PrivateKeyEd25519, Secret}
import co.topl.attestation.serialization.PropositionSerializer
import co.topl.crypto.PublicKey
import co.topl.crypto.hash.blake2b256
import co.topl.crypto.hash.implicits._
import co.topl.crypto.signatures.Curve25519
import co.topl.utils.NetworkType.NetworkPrefix
import co.topl.utils.codecs.AsBytes.implicits._
import co.topl.utils.encode.Base58
import co.topl.utils.serialization.{BifrostSerializer, BytesSerializable}
import co.topl.utils.{Identifiable, Identifier}
import com.google.common.primitives.Ints
import io.circe.syntax.EncoderOps
import io.circe.{Decoder, Encoder, KeyDecoder, KeyEncoder}

import scala.collection.SortedSet
import scala.util.{Failure, Success, Try}

// Propositions are challenges that must be satisfied by the prover.
// In most cases, propositions are used by transactions issuers (spenders) to prove the right
// to use a UTXO in a transaction.
sealed trait Proposition extends BytesSerializable {

  override type M = Proposition
  override def serializer: BifrostSerializer[Proposition] = PropositionSerializer

  def address(implicit networkPrefix: NetworkPrefix): Address

  override def toString: String = Base58.encode(bytes)

  override def equals(obj: Any): Boolean = obj match {
    case prop: Proposition => prop.bytes sameElements bytes
    case _                 => false
  }

  override def hashCode(): Int = Ints.fromByteArray(bytes)
}

object Proposition {

  def fromString(str: String): Try[_ <: Proposition] =
    Base58.decode(str).flatMap(bytes => PropositionSerializer.parseBytes(bytes))

  implicit def jsonKeyEncoder[P <: Proposition]: KeyEncoder[P] = (prop: P) => prop.toString
  implicit val jsonKeyDecoder: KeyDecoder[Proposition] = (str: String) => fromString(str).toOption
}

// Knowledge propositions require the prover to supply a proof attesting to their knowledge
// of secret information.
sealed trait KnowledgeProposition[S <: Secret] extends Proposition

/* ----------------- */ /* ----------------- */ /* ----------------- */ /* ----------------- */ /* ----------------- */

case class PublicKeyPropositionCurve25519(private[attestation] val pubKeyBytes: PublicKey)
    extends KnowledgeProposition[PrivateKeyCurve25519] {

  require(
    pubKeyBytes.value.length == Curve25519.KeyLength,
    s"Incorrect pubKey length, ${Curve25519.KeyLength} expected, ${pubKeyBytes.value.length} found"
  )

  def address(implicit networkPrefix: NetworkPrefix): Address = Address.from(this)

}

object PublicKeyPropositionCurve25519 {
  // type prefix used for address creation
  val typePrefix: EvidenceTypePrefix = 1: Byte
  val typeString: String = "PublicKeyCurve25519"

  def apply(str: String): PublicKeyPropositionCurve25519 =
    Proposition.fromString(str) match {
      case Success(pk: PublicKeyPropositionCurve25519) => pk
      case Success(_)                                  => throw new Error("Invalid proposition generation")
      case Failure(ex)                                 => throw ex
    }

  implicit val ord: Ordering[PublicKeyPropositionCurve25519] = Ordering.by(_.toString)

  implicit val evProducer: EvidenceProducer[PublicKeyPropositionCurve25519] =
    EvidenceProducer.instance[PublicKeyPropositionCurve25519] { prop: PublicKeyPropositionCurve25519 =>
      Evidence(typePrefix, EvidenceContent(blake2b256.hash(prop.bytes.tail)))
    }

  implicit val identifier: Identifiable[PublicKeyPropositionCurve25519] = Identifiable.instance { () =>
    Identifier(typeString, typePrefix)
  }

  // see circe documentation for custom encoder / decoders
  // https://circe.github.io/circe/codecs/custom-codecs.html
  implicit val jsonEncoder: Encoder[PublicKeyPropositionCurve25519] = (prop: PublicKeyPropositionCurve25519) =>
    prop.toString.asJson

  implicit val jsonKeyEncoder: KeyEncoder[PublicKeyPropositionCurve25519] = (prop: PublicKeyPropositionCurve25519) =>
    prop.toString
  implicit val jsonDecoder: Decoder[PublicKeyPropositionCurve25519] = Decoder.decodeString.map(apply)
  implicit val jsonKeyDecoder: KeyDecoder[PublicKeyPropositionCurve25519] = (str: String) => Some(apply(str))
}

/* ----------------- */ /* ----------------- */ /* ----------------- */ /* ----------------- */ /* ----------------- */

case class ThresholdPropositionCurve25519(threshold: Int, pubKeyProps: SortedSet[PublicKeyPropositionCurve25519])
    extends KnowledgeProposition[PrivateKeyCurve25519] {

  pubKeyProps.foreach { prop =>
    require(
      prop.pubKeyBytes.value.length == Curve25519.KeyLength,
      s"Incorrect pubKey length, ${Curve25519.KeyLength} expected, ${prop.pubKeyBytes.value.length} found"
    )
  }

//  val propTypeString: String = ThresholdPropositionCurve25519.typeString
//  val propTypePrefix: EvidenceTypePrefix = ThresholdPropositionCurve25519.typePrefix

  def address(implicit networkPrefix: NetworkPrefix): Address = Address.from(this)

}

object ThresholdPropositionCurve25519 {
  // type prefix used for address creation
  val typePrefix: EvidenceTypePrefix = 2: Byte
  val typeString: String = "ThresholdCurve25519"

  def apply(str: String): ThresholdPropositionCurve25519 =
    Proposition.fromString(str) match {
      case Success(prop: ThresholdPropositionCurve25519) => prop
      case Success(_)                                    => throw new Error("Invalid proposition generation")
      case Failure(ex)                                   => throw ex
    }

  implicit val evProducer: EvidenceProducer[ThresholdPropositionCurve25519] =
    EvidenceProducer.instance[ThresholdPropositionCurve25519] { prop: ThresholdPropositionCurve25519 =>
      Evidence(typePrefix, EvidenceContent(blake2b256.hash(prop.bytes.tail)))
    }

  implicit val identifier: Identifiable[ThresholdPropositionCurve25519] = Identifiable.instance { () =>
    Identifier(typeString, typePrefix)
  }

  // see circe documentation for custom encoder / decoders
  // https://circe.github.io/circe/codecs/custom-codecs.html
  implicit val jsonEncoder: Encoder[ThresholdPropositionCurve25519] = (prop: ThresholdPropositionCurve25519) =>
    prop.toString.asJson

  implicit val jsonKeyEncoder: KeyEncoder[ThresholdPropositionCurve25519] = (prop: ThresholdPropositionCurve25519) =>
    prop.toString
  implicit val jsonDecoder: Decoder[ThresholdPropositionCurve25519] = Decoder.decodeString.map(apply)
  implicit val jsonKeyDecoder: KeyDecoder[ThresholdPropositionCurve25519] = (str: String) => Some(apply(str))
}

/* ----------------- */ /* ----------------- */ /* ----------------- */ /* ----------------- */ /* ----------------- */

case class PublicKeyPropositionEd25519(private[attestation] val pubKeyBytes: PublicKey)
    extends KnowledgeProposition[PrivateKeyEd25519] {

  require(
    pubKeyBytes.value.length == Curve25519.KeyLength,
    s"Incorrect pubKey length, ${Curve25519.KeyLength} expected, ${pubKeyBytes.value.length} found"
  )

  def address(implicit networkPrefix: NetworkPrefix): Address = Address.from(this)

}

object PublicKeyPropositionEd25519 {
  // type prefix used for address creation
  val typePrefix: EvidenceTypePrefix = 3: Byte
  val typeString: String = "PublicKeyEd25519"

  def apply(str: String): PublicKeyPropositionEd25519 =
    Proposition.fromString(str) match {
      case Success(prop: PublicKeyPropositionEd25519) => prop
      case Success(_)                                 => throw new Error("Invalid proposition generation")
      case Failure(ex)                                => throw ex
    }

  implicit val evProducer: EvidenceProducer[PublicKeyPropositionEd25519] =
    EvidenceProducer.instance[PublicKeyPropositionEd25519] { prop: PublicKeyPropositionEd25519 =>
      Evidence(typePrefix, EvidenceContent(blake2b256.hash(prop.bytes.tail)))
    }

  implicit val identifier: Identifiable[PublicKeyPropositionEd25519] = Identifiable.instance { () =>
    Identifier(typeString, typePrefix)
  }

  // see circe documentation for custom encoder / decoders
  // https://circe.github.io/circe/codecs/custom-codecs.html
  implicit val jsonEncoder: Encoder[PublicKeyPropositionEd25519] = (prop: PublicKeyPropositionEd25519) =>
    prop.toString.asJson

  implicit val jsonKeyEncoder: KeyEncoder[PublicKeyPropositionEd25519] = (prop: PublicKeyPropositionEd25519) =>
    prop.toString
  implicit val jsonDecoder: Decoder[PublicKeyPropositionEd25519] = Decoder.decodeString.map(apply)
  implicit val jsonKeyDecoder: KeyDecoder[PublicKeyPropositionEd25519] = (str: String) => Some(apply(str))
}
