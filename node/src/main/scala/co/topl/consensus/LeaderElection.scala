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
      // This is ugly iterable/procedural code, but the goal is lazy traversal to avoid fetching all boxes for
      // all addresses when we're only looking for the _first_ valid candidate
      val arbitBoxesIterator =
        addresses.iterator
          .flatMap {
            stateReader
              .getTokenBoxes(_)
              .getOrElse(Nil)
          }
          .collect { case box: ArbitBox => box }

      if (arbitBoxesIterator.hasNext) {
        while (arbitBoxesIterator.hasNext) {
          val box = arbitBoxesIterator.next()
          val hit = calcHit(parent)(box)
          val calculatedTarget =
            calcTarget(box.value.quantity, timestamp - parent.timestamp, parent.difficulty, parent.height)
          if (hit < calculatedTarget) return Right(box)
        }
        Left(NoBoxesEligible)
      } else {
        Left(NoArbitBoxesAvailable)
      }
    }

  sealed trait IneligibilityReason
  case object NoAddressesAvailable extends IneligibilityReason
  case object NoBoxesEligible extends IneligibilityReason
  case object NoArbitBoxesAvailable extends IneligibilityReason
}
