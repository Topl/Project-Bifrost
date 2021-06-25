package co.topl.attestation.keyManagement

import cats.data.Validated
import co.topl.attestation.ProofNew
import co.topl.attestation.keyManagement.proposition.implicits._
import co.topl.attestation.keyManagement.proposition.{PublicKeyPropositionCurve25519, ThresholdPropositionCurve25519}
import co.topl.crypto.signatures.{Curve25519, Signature}
import io.estatico.newtype.macros.newtype
import io.estatico.newtype.ops._

import scala.util.Try

import scala.language.implicitConversions

package object proof {

  @newtype
  class SignatureCurve25519(val value: Signature)

  object SignatureCurve25519 {
    lazy val signatureSize: Int = Curve25519.SignatureLength

    /** Helper function to create empty signatures */
    lazy val empty: SignatureCurve25519 = SignatureCurve25519(Signature(Array.emptyByteArray))

    /** Returns a signature filled with 1's for use in genesis signatures */
    lazy val genesis: SignatureCurve25519 =
      SignatureCurve25519(Signature(Array.fill(SignatureCurve25519.signatureSize)(1: Byte)))

    sealed trait InvalidSignatureFailure
    case class InvalidLength() extends InvalidSignatureFailure

    def validated(signature: Signature): Validated[InvalidSignatureFailure, SignatureCurve25519] =
      Validated.cond(
        signature.value.length == 0 || signature.value.length == Curve25519.SignatureLength,
        signature.coerce,
        InvalidLength()
      )

    def apply(signature: Signature): SignatureCurve25519 =
      validated(signature).valueOr(err => throw new IllegalArgumentException(s"Invalid signature: $err"))

    def isValid(
      signature:   SignatureCurve25519,
      proposition: PublicKeyPropositionCurve25519,
      message:     Array[Byte]
    ): Boolean =
      Curve25519.verify(signature.value, message, proposition.value)

    trait Instances {
      implicit val proof: ProofNew[SignatureCurve25519, PublicKeyPropositionCurve25519] =
        ProofNew.instance(isValid)
    }
  }

  @newtype
  case class ThresholdSignatureCurve25519(signatures: Set[SignatureCurve25519])

  object ThresholdSignatureCurve25519 {

    /** Helper function to create empty signatures */
    def empty(): ThresholdSignatureCurve25519 = Set[SignatureCurve25519]().coerce

    def isValid(
      t:           ThresholdSignatureCurve25519,
      proposition: ThresholdPropositionCurve25519,
      message:     Array[Byte]
    ): Boolean = Try {
      // check that we have at least m signatures
      // JAA - the Try wraps this to expression so this check may prematurely exit evaluation and return false
      //       (i.e. the check should fail quickly)
      require(proposition.pubKeyProps.size >= proposition.threshold)

      // only need to check until the threshold is exceeded
      val numValidSigs = t.signatures.foldLeft(0) { (acc, sig) =>
        if (acc < proposition.threshold) {
          if (
            proposition.pubKeyProps
              .exists(prop => Curve25519.verify(sig.value, message, prop.value))
          ) {
            1
          } else {
            0
          }
        } else {
          0
        }
      }

      require(numValidSigs >= proposition.threshold)

    }.isSuccess

    trait Instances {
      implicit val proof: ProofNew[ThresholdSignatureCurve25519, ThresholdPropositionCurve25519] =
        ProofNew.instance(isValid)
    }
  }

  trait Instances extends ThresholdSignatureCurve25519.Instances with SignatureCurve25519.Instances

  object implicits extends Instances
}
