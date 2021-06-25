package co.topl.attestation

import simulacrum.typeclass

import scala.language.implicitConversions

/**
 * EvidenceProducer is a type-class that must be implemented by a proposition in order to generate the evidence
 * for that proposition. The evidence is then used to construct an address that holds outputs from a transaction.
 *
 * @tparam P a proposition that is used to encumber a UTXO
 */
sealed trait EvidenceProducer[P <: Proposition] {
  def generateEvidence(prop: P): Evidence
}

@typeclass
trait EvidenceProducerNew[T] {
  def generateEvidence(t: T): Evidence
}

object EvidenceProducer {
  def apply[P <: Proposition](implicit ev: EvidenceProducer[P]): EvidenceProducer[P] = ev

  def instance[P <: Proposition](f: P => Evidence): EvidenceProducer[P] = new EvidenceProducer[P] {
    override def generateEvidence(prop: P): Evidence = f(prop)
  }

  object Syntax {

    implicit final class ProducerOps[P <: Proposition: EvidenceProducer](private val value: P) {
      def generateEvidence: Evidence = EvidenceProducer[P].generateEvidence(value)
    }
  }
}
