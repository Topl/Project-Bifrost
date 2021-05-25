package co.topl.modifier.box

import co.topl.attestation.EvidenceProducer.Syntax.ProducerOps
import co.topl.attestation.{Address, EvidenceProducer, Proof, Proposition}
import scala.Iterable

sealed abstract class Unlocker[P <: Proposition]

class BoxUnlocker[P <: Proposition, PR <: Proof[P]](val closedBoxId: BoxId, val proposition: P, val boxKey: PR)
    extends Unlocker[P] {

  override def toString: String = s"BoxUnlocker(id: $closedBoxId, boxKey: $boxKey)"
}

object BoxUnlocker {

  /**
   * Generate a series of unlockers for a transactions that is used to validate the transaction.
   * Unlockers are used to match the address in a box to a particular proposition -> proof pair in
   * the attestation map
   *
   * @param from a sequence of Address -> Nonce from which the box id's for the transaction can be generated
   * @param attestation a map of propositions matching the evidence contained in the given addresses, as well as proof satisfying the proposition
   * @return a set of box unlockers that
   */
  def generate[P <: Proposition: EvidenceProducer, PR <: Proof[P]](
    from:        Seq[(Address, Box.Nonce)],
    attestation: Map[P, PR]
  ): Iterable[BoxUnlocker[P, PR]] = {

    val evidence = attestation.keys.map { prop =>
      prop.generateEvidence -> prop
    }

    from.map { case (addr, nonce) =>
      val boxId = BoxId.idFromEviNonce(addr.evidence, nonce)
      val (prop, proof) = evidence
        .collectFirst[(P, PR)] {
          case (ev, prop) if ev == addr.evidence => prop -> attestation(prop)
        }
        .get

      new BoxUnlocker(boxId, prop, proof)
    }
  }

  def generate[P <: Proposition, PR <: Proof[P]](
    boxIds: Seq[BoxId],
    prop:   P,
    proof:  PR
  ): Iterable[BoxUnlocker[P, PR]] =
    boxIds.map(new BoxUnlocker[P, PR](_, prop, proof))

}
