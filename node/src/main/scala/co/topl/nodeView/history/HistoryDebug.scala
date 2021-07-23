package co.topl.nodeView.history

import co.topl.attestation.PublicKeyPropositionCurve25519
import co.topl.modifier.ModifierId
import co.topl.modifier.block.Block
import co.topl.network.message.SyncInfo

import scala.annotation.tailrec
import scala.util.Try

/** A class for collecting methods that help debug history */
class HistoryDebug(hr: HistoryReader[Block, _ <: SyncInfo]) {

  def count(f: Block => Boolean): Int = hr.filter(f).length

  /**
   * @param height - block height
   * @return ids of headers on chosen height.
   *         Seq.empty we don't have any headers on this height (e.g. it is too big or we bootstrap in PoPoW regime)
   *         single id if no forks on this height
   *         multiple ids if there are forks at chosen height.
   *         First id is always from the best headers chain.
   */
  def idsAtHeight(height: Long): Seq[ModifierId] = hr.idAtHeightOf(height).toSeq

  def getIdsFrom(startHeight: Long, limit: Int): Option[Seq[ModifierId]] =
    hr.modifierByHeight(startHeight) match {
      case Some(block) => hr.getIdsFrom(block, _ => false, limit)
      case None        => None
    }

  /**
   * Average delay in milliseconds between last `blockNum` blocks starting from `block`
   * Debug only
   *
   * @param id modifier to start at
   * @param blockNum number of blocks to traverse back
   */
  def averageDelay(id: ModifierId, blockNum: Int): Try[Long] = Try {
    val block = hr.modifierById(id).get
    val prevTimes = hr.getTimestampsFrom(block, blockNum)
    prevTimes.drop(1).lazyZip(prevTimes).map(_ - _).sum / (prevTimes.length)
  }

  /**
   * Calculates the distribution of blocks to forgers
   *
   * @return a map from public keys of forgers to the number of blocks they have forged
   */
  def forgerDistribution(): Map[PublicKeyPropositionCurve25519, Int] = {
    val map = collection.mutable.Map[PublicKeyPropositionCurve25519, Int]().withDefaultValue(0)

    /**
     * Finds the forger for this block, increments their block number entry in map, and continues down the chain
     * m is the current block for which to increment the forger entry
     */
    @tailrec
    def loopBackAndIncrementForger(m: Block): Unit = {
      val forger = m.publicKey
      map.update(forger, map(forger) + 1)
      hr.parentBlock(m) match {
        case Some(parent) => loopBackAndIncrementForger(parent)
        case None         =>
      }
    }

    loopBackAndIncrementForger(hr.bestBlock)
    map.toMap
  }

//  /** @param f : predicate that tests whether a queryBloom is compatible with a block's bloom
//    * @return Seq of blockId that satisfies f
//    */
//  def getBlockIdsByBloom(f: BloomFilter => Boolean): Seq[ModifierId] = {
//    @tailrec
//    def loop(current: Array[Byte], acc: Seq[Array[Byte]]): Seq[ModifierId] =
//      storage.serializedParentIdOf(current) match {
//        case Some(value) =>
//          if (f(storage.bloomOf(current).get)) loop(value, current +: acc) else loop(value, acc)
//
//        case None =>
//          if (f(storage.bloomOf(current).get)) (current +: acc).map(ModifierId(_)) else acc.map(ModifierId(_))
//      }
//
//    loop(storage.bestBlockId.getIdBytes, Seq())
//  }
//
//  /**
//   * Returns a set of transactions matching the specified topics
//   *
//   * @param queryBloomTopics topics to search the the block bloom filter for
//   * @return
//   */
//  def bloomFilter(queryBloomTopics: IndexedSeq[BloomTopic]): Seq[Transaction.TX] = {
//    val f: BloomFilter => Boolean = {
//      blockBloom => queryBloomTopics.forall(blockBloom.contains)
//    }
//
//    // Go through all pertinent txs to filter out false positives
//    getBlockIdsByBloom(f).flatMap { b =>
//      modifierById(b).get.transactions.filter { tx =>
//        tx.bloomTopics.exists { txTopic =>
//          val txBloomsWrapper = (txTopic)
//          val queryBloomsWrapper = queryBloomTopics.map((_))
//          queryBloomsWrapper.contains(txBloomsWrapper)
//        }
//      }
//    }
//  }
}
