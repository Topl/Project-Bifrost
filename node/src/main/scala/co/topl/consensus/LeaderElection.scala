package co.topl.consensus

import co.topl.attestation.Address
import co.topl.modifier.block.Block
import co.topl.modifier.box.{ArbitBox, ProgramId}
import co.topl.modifier.transaction.Transaction
import co.topl.nodeView.state.StateReader
import co.topl.utils.{Logging, TimeProvider}

import scala.collection.Set

object LeaderElection extends Logging {

  type TX = Transaction.TX
  type SR = StateReader[ProgramId, Address]

  /**
   * Gets an arbit box that is eligible for forging the next block if there are any.
   * @param parent the parent block
   * @param addresses the addresses to stake with
   * @param timestamp the current time
   * @param stateReader a read-only version of state
   * @return an eligible box if one is found
   */
  def getEligibleBox(
    parent:      Block,
    addresses:   Set[Address],
    timestamp:   TimeProvider.Time,
    stateReader: SR
  ): Either[IneligibilityReason, ArbitBox] =
    if (addresses.isEmpty) {
      Left(NoAddressesAvailable)
    } else {
      val arbitBoxes = addresses.flatMap {
        stateReader
          .getTokenBoxes(_)
          .getOrElse(Seq())
          .collect { case box: ArbitBox => box }
      }.toSeq

      (arbitBoxes match {
        case Seq() => Left(NoArbitBoxesAvailable)
        case seq   => Right(seq)
      }).flatMap { boxes =>
        boxes
          .map(box => (box, calcHit(parent)(box)))
          .filter { case (box, hit) =>
            BigInt(hit) < calcTarget(box.value.quantity, timestamp - parent.timestamp, parent.difficulty, parent.height)
          }
          .map(_._1)
          .headOption
          .toRight(NoBoxesEligible)
      }
    }

  sealed trait IneligibilityReason
  case object NoAddressesAvailable extends IneligibilityReason
  case object NoBoxesEligible extends IneligibilityReason
  case object NoArbitBoxesAvailable extends IneligibilityReason
}
