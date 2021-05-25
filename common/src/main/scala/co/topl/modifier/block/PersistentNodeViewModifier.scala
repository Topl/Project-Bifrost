package co.topl.modifier.block

import co.topl.crypto.accumulators.LeafData
import co.topl.crypto.accumulators.merkle.MerkleTree
import co.topl.crypto.accumulators.merkle.MerkleTree.MerkleTreeResult
import co.topl.crypto.hash.Blake2b
import co.topl.crypto.hash.digest.Digest32
import co.topl.crypto.hash.implicits._
import co.topl.modifier.block.BloomFilter.BloomTopic
import co.topl.modifier.block.PersistentNodeViewModifier.PNVMVersion
import co.topl.modifier.transaction.Transaction
import co.topl.modifier.{ModifierId, NodeViewModifier}
import co.topl.utils.IdiomaticScalaTransition.implicits.toEitherOps

trait PersistentNodeViewModifier extends NodeViewModifier {
  def parentId: ModifierId
  def version: PNVMVersion
}

object PersistentNodeViewModifier {
  type PNVMVersion = Byte
}

/* ----------------- */ /* ----------------- */ /* ----------------- */ /* ----------------- */ /* ----------------- */ /* ----------------- */

trait TransactionCarryingPersistentNodeViewModifier[TX <: Transaction.TX] extends PersistentNodeViewModifier {

  val transactions: Seq[TX]

  @deprecated
  lazy val merkleTree: MerkleTree[Blake2b, Digest32] =
    MerkleTree.construct[Blake2b, Digest32](transactions.map(tx => LeafData(tx.bytes))).getOrThrow()

  def constructMerkleTree(): MerkleTreeResult[Blake2b, Digest32] =
    MerkleTree.construct[Blake2b, Digest32](transactions.map(tx => LeafData(tx.bytes)))

  lazy val bloomFilter: BloomFilter = TransactionsCarryingPersistentNodeViewModifier.createBloom(transactions)

}

object TransactionsCarryingPersistentNodeViewModifier {

  /**
   * Calculates a bloom filter based on the topics in the transactions
   * @param txs sequence of transaction to create the bloom for
   * @return a bloom filter
   */
  def createBloom(txs: Seq[Transaction.TX]): BloomFilter = {
    val topics = txs.foldLeft(Set[BloomTopic]()) { (acc, tx) =>
      acc ++ tx.bloomTopics
    }

    BloomFilter(topics)
  }
}
