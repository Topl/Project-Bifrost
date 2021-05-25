package co.topl.nodeView

import co.topl.attestation.Address
import co.topl.modifier.ModifierId
import co.topl.modifier.box.{Box, ProgramBox, TokenBox, TokenValueHolder}
import co.topl.utils.NetworkType.NetworkPrefix

import scala.util.{Failure, Success}

package object state {

  /**
   * This function will modify the state storage directly without returning a new instance of state
   * USE WITH EXTREME CAUTION!!
   */
  def directlyAddStateStorage(version: ModifierId, boxes: Seq[Box[_]], state: State): Unit = {
    // Manually manipulate state
    val boxSC = StateChanges(Seq(), boxes)

    // this works by updating the underlying storage object directly and ignoring the updated state instance
    state.applyChanges(version, boxSC) match {
      case Success(_)  => ()
      case Failure(ex) => throw ex
    }
  }

  /**
   * This function will modify the PBR storage directly without returning a new instance of the registry
   * USE WITH EXTREME CAUTION!!
   */
  def directlyAddPBRStorage(version: ModifierId, boxes: Seq[ProgramBox], state: State): Unit = {

    /** Manually manipulate state */
    val updates = boxes.map(bx => bx.value -> Seq(bx.id)).toMap
    val pbrSC = ProgramRegistryChanges(Map(), updates)

    /** This works by updating the underlying storage object directly and ignoring the updated state instance */
    directlyAddStateStorage(version, boxes, state)
    state.pbrOpt.get.update(version, pbrSC.toRemove, pbrSC.toUpdate) match {
      case Success(_)  => ()
      case Failure(ex) => throw ex
    }
  }

  /**
   * This function will modify the PBR storage directly without returning a new instance of the registry
   * USE WITH EXTREME CAUTION!!
   */
  def directlyAddTBRStorage(version: ModifierId, boxes: Seq[TokenBox[TokenValueHolder]], state: State)(implicit
    networkPrefix:                   NetworkPrefix
  ): Unit = {

    /** Manually manipulate state */
    val updates = boxes.map(bx => Address(bx.evidence) -> Seq(bx.nonce)).toMap
    val tbrSC = TokenRegistryChanges(Map(), updates)

    /** This works by updating the underlying storage object directly and ignoring the updated state instance */
    directlyAddStateStorage(version, boxes, state)
    state.tbrOpt.get.update(version, tbrSC.toRemove, tbrSC.toAppend) match {
      case Success(_)  => ()
      case Failure(ex) => throw ex
    }
  }
}
