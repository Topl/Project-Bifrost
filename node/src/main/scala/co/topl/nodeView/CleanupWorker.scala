package co.topl.nodeView

import akka.actor.Actor
import co.topl.attestation.Address
import co.topl.modifier.ModifierId
import co.topl.modifier.box.ProgramId
import co.topl.modifier.transaction.Transaction
import co.topl.nodeView.CleanupWorker.RunCleanup
import co.topl.nodeView.MempoolAuditor.CleanupDone
import co.topl.nodeView.mempool.MemPoolReader
import co.topl.nodeView.state.StateReader
import co.topl.settings.AppSettings
import co.topl.utils.NetworkType.NetworkPrefix
import co.topl.utils.{Logging, TimeProvider}

import scala.collection.immutable.TreeSet

/**
 * Performs mempool validation task on demand.
 * Validation result is sent directly to `NodeViewHolder`.
 */
class CleanupWorker(
  nodeViewHolderRef: akka.actor.typed.ActorRef[NodeViewHolder.ReceivableMessage],
  settings:          AppSettings
)(implicit
  networkPrefix: NetworkPrefix,
  timeProvider:  TimeProvider
) extends Actor
    with Logging {

  type SR = StateReader[ProgramId, Address]
  type MR = MemPoolReader[Transaction.TX]

  // keep some number of recently validated transactions in order
  // to avoid validating the same transactions too many times.
  private var validatedIndex: TreeSet[ModifierId] = TreeSet.empty[ModifierId]
  // count validation sessions in order to perform index cleanup.
  private var epochNr: Int = 0

  override def preStart(): Unit =
    log.info("Cleanup worker started")

  override def receive: Receive = {
    case RunCleanup(state: SR, mempool: MR) =>
      val validIds = runCleanup(state, mempool)
        .take(settings.application.rebroadcastCount)
      sender() ! CleanupDone(validIds)

    //Should not be here, if non-expected signal comes, check logic
    case a: Any => log.warn(s"Strange input: $a")
  }

  private def runCleanup(state: SR, mempool: MR): Seq[ModifierId] = {
    val (validated, toEliminate) = validatePool(state, mempool)
    if (toEliminate.nonEmpty) {
      log.info(s"${toEliminate.size} transactions from mempool were invalidated")
      nodeViewHolderRef ! NodeViewHolder.ReceivableMessages.EliminateTransactions(toEliminate)
    }
    validated
  }

  /**
   * Checks if the outputs of unconfirmed transactions exists in state or if the transaction has become
   *  stale (by exceeding the mempoolTimeout). If either are true, the transaction is removed from the mempool
   * @return - a sequence of recently validated transactions id's to be rebroadcast and a sequence of ids to remove
   */
  private def validatePool(stateReader: SR, mempool: MR): (Seq[ModifierId], Seq[ModifierId]) = {

    // Check transactions sorted by priority. Parent transaction comes before its children.
    val (validatedIds, invalidatedIds) = mempool
      .take(100)(-_.dateAdded)
      .filterNot(utx => validatedIndex.contains(utx.tx.id))
      .foldLeft((Seq[ModifierId](), Seq[ModifierId]()))({
        case ((validAcc: Seq[ModifierId], invalidAcc: Seq[ModifierId]), utx) =>
          // if any newly created box matches a box already in the UTXO set, remove the transaction
          val boxAlreadyExists = utx.tx.newBoxes.exists(b => stateReader.getBox(b.id).isDefined)
          val txTimeout =
            (timeProvider.time - utx.dateAdded) > settings.application.mempoolTimeout.toMillis

          if (boxAlreadyExists | txTimeout) (validAcc, utx.tx.id +: invalidAcc)
          else (utx.tx.id +: validAcc, invalidAcc)
      })

    if (epochNr < Int.MaxValue) epochNr += 1 else epochNr = 0
    if (epochNr % CleanupWorker.RevisionInterval == 0) {
      // drop old index in order to check potentially outdated transactions again.
      validatedIndex = TreeSet(validatedIds: _*)
    } else {
      validatedIndex ++= validatedIds
    }

    validatedIds -> invalidatedIds
  }

}

object CleanupWorker {

  /**
   * Constant which shows on how many cleanup operations (called when a new block arrives) a transaction
   * re-check happens.
   *
   * If transactions set is large and stable, then about (1/RevisionInterval)-th of the pool is checked
   */
  val RevisionInterval: Int = 4

  /**
   * A command to run (partial) memory pool cleanup
   *
   * @param stateReader - a state implementation which provides transaction validation
   * @param mempool - mempool reader instance
   */
  case class RunCleanup(stateReader: StateReader[ProgramId, Address], mempool: MemPoolReader[Transaction.TX])

}
