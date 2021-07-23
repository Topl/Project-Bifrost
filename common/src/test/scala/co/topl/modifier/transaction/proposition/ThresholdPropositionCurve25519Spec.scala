package co.topl.modifier.transaction.proposition

import co.topl.attestation.keyManagement.PrivateKeyCurve25519
import co.topl.attestation.{
  PublicKeyPropositionCurve25519,
  ThresholdPropositionCurve25519,
  ThresholdSignatureCurve25519
}
import co.topl.utils.CommonGenerators
import org.scalatest.matchers.should.Matchers
import org.scalatest.propspec.AnyPropSpec
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks

import scala.collection.SortedSet

class ThresholdPropositionCurve25519Spec
    extends AnyPropSpec
    with ScalaCheckDrivenPropertyChecks
    with Matchers
    with CommonGenerators {

  property("Any signature from set validates") {
    forAll(oneOfNPropositionCurve25519Gen) {
      case (keySet: Set[PrivateKeyCurve25519], mn: ThresholdPropositionCurve25519) =>
        val message = nonEmptyBytesGen.sample.getOrElse(Array.fill(positiveMediumIntGen.sample.get)(1: Byte))
        val signatures = keySet.map(_.sign(message))

        signatures
          .map(s => ThresholdSignatureCurve25519(Set(s)))
          .forall(_.isValid(mn, message)) shouldBe true
    }
  }

  property("Number of valid signatures in set is no less than the threshold") {
    forAll(thresholdPropositionCurve25519Gen) {
      case (keySet: Set[PrivateKeyCurve25519], mn: ThresholdPropositionCurve25519) =>
        val message = nonEmptyBytesGen.sample.getOrElse(Array.fill(positiveMediumIntGen.sample.get)(1: Byte))
        val signatures = keySet.map(_.sign(message))

        ThresholdSignatureCurve25519(signatures.take(mn.threshold)).isValid(mn, message) shouldBe true
        ThresholdSignatureCurve25519(signatures.take(mn.threshold - 1)).isValid(mn, message) shouldBe false
    }
  }

  property(
    "Threshold will not be satisfied if not enough signatures generated with unique proposition is" +
    " provided"
  ) {
    forAll(thresholdPropositionCurve25519Gen) {
      case (keySet: Set[PrivateKeyCurve25519], mn: ThresholdPropositionCurve25519) =>
        val message = nonEmptyBytesGen.sample.getOrElse(Array.fill(positiveMediumIntGen.sample.get)(1: Byte))
        val signatures = List.fill(keySet.size)(keySet.head).map(_.sign(message)).toSet // sigs from the same key

        ThresholdSignatureCurve25519(signatures).isValid(mn, message) shouldBe false
        ThresholdSignatureCurve25519(signatures.take(mn.threshold)).isValid(mn, message) shouldBe false
        ThresholdSignatureCurve25519(signatures.take(mn.threshold - 1)).isValid(mn, message) shouldBe false
    }
  }

  property(
    "A MultiSignature25519 created from single Signature25519 " +
    "should be valid for oneOfNProposition"
  ) {

    forAll(keyPairSetCurve25519Gen) { s: Set[(PrivateKeyCurve25519, PublicKeyPropositionCurve25519)] =>
      val message = nonEmptyBytesGen.sample.getOrElse(Array.fill(positiveMediumIntGen.sample.get)(1: Byte))
      val signatures = s.map(_._1.sign(message))
      val pubKeyProps = SortedSet[PublicKeyPropositionCurve25519]() ++ s.map(_._2)
      val oneOfNProposition = ThresholdPropositionCurve25519(1, pubKeyProps)

      signatures
        .map(s => ThresholdSignatureCurve25519(Set(s)))
        .forall(_.isValid(oneOfNProposition, message)) shouldBe true
    }
  }

  property(
    "A ThresholdSignatureCurve25519 created from single Signature25519 " +
    "should not be valid for twoOfNProposition"
  ) {

    forAll(keyPairSetCurve25519Gen) { s: Set[(PrivateKeyCurve25519, PublicKeyPropositionCurve25519)] =>
      val message = nonEmptyBytesGen.sample.getOrElse(Array.fill(positiveMediumIntGen.sample.get)(1: Byte))
      val signatures = s.map(_._1.sign(message))
      val pubKeyProps = SortedSet[PublicKeyPropositionCurve25519]() ++ s.map(_._2)
      val oneOfNProposition = ThresholdPropositionCurve25519(2, pubKeyProps)

      signatures
        .map(s => ThresholdSignatureCurve25519(Set(s)))
        .forall(_.isValid(oneOfNProposition, message)) shouldBe false
    }
  }
}
