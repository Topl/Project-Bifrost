package co.topl.nodeView.history

import co.topl.modifier.ModifierId
import co.topl.modifier.block.Block
import co.topl.modifier.transaction.Transaction.TX
import co.topl.db.LDBVersionedStore
import co.topl.utils.{CommonGenerators, FileUtils, NodeGenerators}
import org.scalatest.matchers.should.Matchers
import org.scalatest.propspec.AnyPropSpec
import org.scalatestplus.scalacheck.{ScalaCheckDrivenPropertyChecks, ScalaCheckPropertyChecks}

import java.io.File

class LevelDBSpec
    extends AnyPropSpec
    with ScalaCheckPropertyChecks
    with ScalaCheckDrivenPropertyChecks
    with Matchers
    with NodeGenerators
    with FileUtils {

  val iFile: File = createTempDir

  val blocksStorage = new LDBVersionedStore(iFile, 1000)
  blocksStorage.update(Array[Byte](1), Seq(), Seq())

  property("Rollback should not touch keys before") {

    /**
     * Apply a transaction by storing its new boxes (ignore old boxes)
     *
     * @param tx the transaction to write boxes to storage
     */
    def writeTx(tx: TX): Unit = {
      val boxIdsToRemove: Iterable[Array[Byte]] = Seq()
      val boxesToAdd: Iterable[(Array[Byte], Array[Byte])] =
        tx.newBoxes
          .map(b => (b.id.hash.value, b.bytes))
          .toList

      blocksStorage.update(tx.id.getIdBytes, boxIdsToRemove, boxesToAdd)
    }

    /**
     * Check that the boxes for the transaction are all stored
     *
     * @param tx the transaction to check has boxes in storage
     */
    def checkTx(tx: TX): Unit =
      tx.newBoxes
        .foreach(b => require(blocksStorage.get(b.id.hash.value).isDefined))

    forAll(bifrostTransactionSeqGen) { txs =>
      whenever(txs.length >= 2) {
        blocksStorage.rollbackTo(Array[Byte](1))

        /* Make sure transactions get written to storage */
        txs.foreach { tx =>
          writeTx(tx)
          checkTx(tx)
        }

        val head = txs.head

        /* Rollback to head shouldn't affect the head tx */
        blocksStorage.rollbackTo(head.id.getIdBytes)
        checkTx(head)
      }
    }
  }

  property("Writing a block should result in storage of block") {

    /**
     * Apply a block by storing all of its transactions' new boxes (ignore old boxes)
     *
     * @param b the block to write tx boxes to storage
     */

    def writeBlock(b: Block): Unit =
      blocksStorage.update(
        b.id.getIdBytes,
        Seq(),
        Seq(b.id.getIdBytes -> (Block.modifierTypeId.value +: b.bytes))
      )

    var ids: Seq[ModifierId] = Seq()

    forAll(blockCurve25519Gen) { block =>
      ids = block.id +: ids
      writeBlock(block)
      blocksStorage.get(block.id.getIdBytes).isDefined shouldBe true
    }

    ids.foreach { id =>
      val idInStorage = blocksStorage.get(id.getIdBytes) match {
        case None    => log.warn(s"${Console.RED} Id ${id.toString} not found"); false
        case Some(_) => true
      }
      require(idInStorage)
    }
  }

}
