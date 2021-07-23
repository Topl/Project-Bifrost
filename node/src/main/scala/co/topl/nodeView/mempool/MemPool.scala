package co.topl.nodeView.mempool

import co.topl.modifier.ModifierId
import co.topl.modifier.box.BoxId
import co.topl.modifier.transaction.Transaction
import co.topl.utils.{Logging, TimeProvider}

import scala.collection.concurrent.TrieMap
import scala.math.Ordering
import scala.util.Try

case class MemPool(private val unconfirmed: TrieMap[ModifierId, UnconfirmedTx[Transaction.TX]])
    extends MemoryPool[Transaction.TX, MemPool]
    with Logging {

  override type NVCT = MemPool
  type TX = Transaction.TX

  private val boxesInMempool = new TrieMap[BoxId, BoxId]()

  //getters
  override def modifierById(id: ModifierId): Option[TX] = unconfirmed.get(id).map(_.tx)

  override def contains(id: ModifierId): Boolean = unconfirmed.contains(id)

  override def getAll(ids: Seq[ModifierId]): Seq[TX] = ids.flatMap(modifierById)

  override def size: Int = unconfirmed.size

  //modifiers
  override def put(tx: TX, time: TimeProvider.Time): Try[MemPool] = Try {
    unconfirmed.put(tx.id, UnconfirmedTx(tx, time))
    tx.boxIdsToOpen.foreach(boxId => require(!boxesInMempool.contains(boxId)))
    tx.boxIdsToOpen.foreach(boxId => boxesInMempool.put(boxId, boxId))
    this
  }

  /**
   * @param txs
   * @return
   */
  override def put(txs: Iterable[TX], time: TimeProvider.Time): Try[MemPool] = Try {
    txs.foreach(tx => unconfirmed.put(tx.id, UnconfirmedTx(tx, time)))
    txs.foreach(tx =>
      tx.boxIdsToOpen.foreach { boxId =>
        require(!boxesInMempool.contains(boxId))
      }
    )
    txs.foreach(tx =>
      tx.boxIdsToOpen.map { boxId =>
        boxesInMempool.put(boxId, boxId)
      }
    )
    this
  }

  /**
   * @param txs
   * @return
   */
  override def putWithoutCheck(txs: Iterable[TX], time: TimeProvider.Time): MemPool = {
    txs.foreach(tx => unconfirmed.put(tx.id, UnconfirmedTx(tx, time)))
    txs.foreach(tx =>
      tx.boxIdsToOpen.map { boxId =>
        boxesInMempool.put(boxId, boxId)
      }
    )
    this
  }

  /**
   * @param tx
   * @return
   */
  override def remove(tx: TX): MemPool = {
    unconfirmed.remove(tx.id)
    this
  }

  /**
   * @param limit
   * @return
   */
  override def take[A](limit: Int)(f: UnconfirmedTx[TX] => A)(implicit ord: Ordering[A]): Iterable[UnconfirmedTx[TX]] =
    unconfirmed.values.toSeq.sortBy(f).take(limit)

  /**
   * @param condition
   * @return
   */
  override def filter(condition: TX => Boolean): MemPool = {
    unconfirmed.filterInPlace { (_, v) =>
      if (condition(v.tx)) {
        true
      } else {
        v.tx.boxIdsToOpen.foreach { boxId =>
          boxesInMempool -= (boxId: BoxId)
        }
        false
      }
    }

    this
  }
}

object MemPool {
  def empty(): MemPool = MemPool(TrieMap())

}
