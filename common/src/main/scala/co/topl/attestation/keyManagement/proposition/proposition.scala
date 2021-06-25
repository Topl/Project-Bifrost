package co.topl.attestation.keyManagement

import cats.data.Validated
import cats.implicits._
import co.topl.attestation.Evidence.EvidenceTypePrefix
import co.topl.attestation.PropositionNew
import co.topl.attestation.keyManagement.codec.{
  PublicKeyPropositionCurve25519Serializer,
  ThresholdPropositionCurve25519Serializer
}
import co.topl.attestation.keyManagement.implicits._
import co.topl.crypto.PublicKey
import co.topl.crypto.signatures.Curve25519
import co.topl.utils.StringDataTypes.implicits._
import co.topl.utils.codecs.implicits._
import co.topl.utils.{Identifiable, Identifier}
import io.estatico.newtype.macros.newtype
import io.estatico.newtype.ops._

import scala.collection.SortedSet

import scala.language.implicitConversions

package object proposition {

  @newtype
  class PublicKeyPropositionCurve25519(val value: PublicKey)

  object PublicKeyPropositionCurve25519 {

    sealed trait InvalidPublicKey
    case class IncorrectKeyLength() extends InvalidPublicKey

    // type prefix used for address creation
    val typePrefix: EvidenceTypePrefix = 1: Byte
    val typeString: String = "PublicKeyCurve25519"

    def validated(publicKey: PublicKey): Validated[InvalidPublicKey, PublicKeyPropositionCurve25519] =
      Validated.cond(
        publicKey.value.length == Curve25519.KeyLength,
        publicKey.coerce,
        IncorrectKeyLength()
      )

    def apply(publicKey: PublicKey): PublicKeyPropositionCurve25519 =
      validated(publicKey).valueOr(err => throw new IllegalArgumentException(s"Invalid public key: $err"))

    trait Instances {

      implicit val ord: Ordering[PublicKeyPropositionCurve25519] = Ordering.by(_.encodeAsBase58.show)

      implicit val proposition: PropositionNew[PublicKeyPropositionCurve25519] =
        // ignore first type byte
        PropositionNew.instance(typePrefix, PublicKeyPropositionCurve25519Serializer.toBytes(_).tail)

      implicit val identifier: Identifiable[PublicKeyPropositionCurve25519] =
        Identifiable.instance(() => Identifier(typeString, typePrefix))
    }

  }

  case class ThresholdPropositionCurve25519(threshold: Int, pubKeyProps: SortedSet[PublicKeyPropositionCurve25519])

  object ThresholdPropositionCurve25519 {

    // type prefix used for address creation
    val typePrefix: EvidenceTypePrefix = 2: Byte
    val typeString: String = "ThresholdCurve25519"

    trait Instances {

      implicit val proposition: PropositionNew[ThresholdPropositionCurve25519] =
        // ignore first type byte
        PropositionNew.instance(typePrefix, ThresholdPropositionCurve25519Serializer.toBytes(_).tail)

      implicit val identifier: Identifiable[ThresholdPropositionCurve25519] =
        Identifiable.instance(() => Identifier(typeString, typePrefix))

    }

  }

  trait Instances extends PublicKeyPropositionCurve25519.Instances with ThresholdPropositionCurve25519.Instances

  object implicits extends Instances

}
