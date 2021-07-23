package co.topl.nodeView.history

import co.topl.db.LDBVersionedStore
import co.topl.crypto.hash.blake2b256
import co.topl.crypto.hash.digest.Digest32
import co.topl.crypto.hash.digest.implicits._
import co.topl.modifier.ModifierId
import co.topl.modifier.block.serialization.BlockSerializer
import co.topl.modifier.block.{Block, BloomFilter}
import co.topl.modifier.transaction.Transaction
import co.topl.utils.Logging
import com.google.common.cache.{CacheBuilder, CacheLoader, LoadingCache}
import com.google.common.primitives.Longs

import scala.concurrent.duration.MILLISECONDS
import scala.util.Try

class Storage(private[history] val storage: LDBVersionedStore, private val cacheExpire: Int, private val cacheSize: Int)
    extends Logging {
  /* ------------------------------- Cache Initialization ------------------------------- */
  type KEY = Array[Byte]
  type VAL = Array[Byte]

  private val blockLoader: CacheLoader[KEY, Option[VAL]] = new CacheLoader[KEY, Option[VAL]] {

    def load(key: KEY): Option[VAL] =
      storage.get(key) match {
        case Some(blockData: VAL) => Some(blockData)
        case _                    => None
      }
  }

  // todo: change key from Array[byte], the cache cannot match on an array of bytes
  val blockCache: LoadingCache[KEY, Option[VAL]] = CacheBuilder
    .newBuilder()
    .expireAfterAccess(cacheExpire, MILLISECONDS)
    .maximumSize(cacheSize)
    .build[KEY, Option[VAL]](blockLoader)
  /* ------------------------------------------------------------------------------------- */

  private val bestBlockIdKey = Array.fill(ModifierId.size)(-1: Byte)

  def scoreAt(b: ModifierId): Long = scoreOf(b).getOrElse(0L)

  def heightAt(b: ModifierId): Long = heightOf(b).getOrElse(0L)

  def difficultyAt(b: ModifierId): Long = difficultyOf(b).getOrElse(0L)

  def bestBlockId: ModifierId =
    blockCache
      .get(bestBlockIdKey)
      .flatMap(d => ModifierId.parseBytes(d).toOption)
      .getOrElse(History.GenesisParentId)

  def bestBlock: Block =
    modifierById(bestBlockId).getOrElse(throw new Error("Unable to retrieve best block from storage"))

  /** Check for the existence of a modifier in storage without parsing the bytes */
  def containsModifier(id: ModifierId): Boolean =
    blockCache.get(id.getIdBytes).isDefined

  /** Retrieve a transaction and its block details from storage */
  def lookupConfirmedTransaction(id: ModifierId): Option[(Transaction.TX, ModifierId, Long)] =
    id.getModType match {
      case Transaction.modifierTypeId =>
        blockCache
          .get(id.getIdBytes)
          .flatMap(blockCache.get)
          .flatMap(bwBlock => BlockSerializer.parseBytes(bwBlock.tail).toOption)
          .map(block => (block.transactions.find(_.id == id).get, block.id, block.height))

      case _ => None
    }

  /** Retrieve a block from storage */
  def modifierById(id: ModifierId): Option[Block] =
    id.getModType match {
      case Block.modifierTypeId =>
        blockCache
          .get(id.getIdBytes)
          .flatMap(bwBlock => BlockSerializer.parseBytes(bwBlock.tail).toOption)

      case _ => None
    }

  /** These methods allow us to lookup top-level information from blocks using the special keys defined below */
  def scoreOf(blockId: ModifierId): Option[Long] =
    blockCache
      .get(blockScoreKey(blockId).bytes)
      .map(b => Longs.fromByteArray(b))

  def heightOf(blockId: ModifierId): Option[Long] =
    blockCache
      .get(blockHeightKey(blockId).bytes)
      .map(b => Longs.fromByteArray(b))

  def timestampOf(blockId: ModifierId): Option[Long] =
    blockCache
      .get(blockTimestampKey(blockId).bytes)
      .map(b => Longs.fromByteArray(b))

  def idAtHeightOf(height: Long): Option[ModifierId] =
    blockCache
      .get(idHeightKey(height).bytes)
      .flatMap(id => ModifierId.parseBytes(id).toOption)

  def difficultyOf(blockId: ModifierId): Option[Long] =
    blockCache
      .get(blockDiffKey(blockId).bytes)
      .map(b => Longs.fromByteArray(b))

  def bloomOf(blockId: ModifierId): Option[BloomFilter] =
    blockCache
      .get(blockBloomKey(blockId).bytes)
      .flatMap(b => BloomFilter.parseBytes(b).toOption)

  def parentIdOf(blockId: ModifierId): Option[ModifierId] =
    blockCache
      .get(blockParentKey(blockId).bytes)
      .flatMap(d => ModifierId.parseBytes(d).toOption)

  /**
   * The keys below are used to store top-level information about blocks that we might be interested in
   * without needing to parse the entire block from storage
   */
  private def blockScoreKey(blockId: ModifierId): Digest32 =
    blake2b256.hash("score".getBytes ++ blockId.getIdBytes)

  private def blockHeightKey(blockId: ModifierId): Digest32 =
    blake2b256.hash("height".getBytes ++ blockId.getIdBytes)

  private def blockDiffKey(blockId: ModifierId): Digest32 =
    blake2b256.hash("difficulty".getBytes ++ blockId.getIdBytes)

  private def blockTimestampKey(blockId: ModifierId): Digest32 =
    blake2b256.hash("timestamp".getBytes ++ blockId.getIdBytes)

  private def blockBloomKey(blockId: ModifierId): Digest32 =
    blake2b256.hash("bloom".getBytes ++ blockId.getIdBytes)

  private def blockParentKey(blockId: ModifierId): Digest32 =
    blake2b256.hash("parentId".getBytes ++ blockId.getIdBytes)

  private def idHeightKey(height: Long): Digest32 = blake2b256.hash(Longs.toByteArray(height))

  /* << EXAMPLE >>
      For version "b00123123":
      ADD
      {
        "b00123123": "Block" | b,
        "diffb00123123": diff,
        "heightb00123123": parentHeight(b00123123) + 1,
        "scoreb00123123": parentChainScore(b00123123) + diff,
        "bestBlock": b00123123
      }
   */
  def update(b: Block, isBest: Boolean): Unit = {
    log.debug(s"Write new best=$isBest block ${b.id}")

    val blockK = Seq(b.id.getIdBytes -> b.bytes)

    val bestBlock = if (isBest) Seq(bestBlockIdKey -> b.id.bytes) else Seq()

    val newTransactionsToBlockIds = b.transactions.map(tx => (tx.id.getIdBytes, b.id.getIdBytes))

    val blockH = Seq(blockHeightKey(b.id).bytes -> Longs.toByteArray(heightAt(b.parentId) + 1))

    val idHeight = Seq(idHeightKey(heightAt(b.parentId) + 1).bytes -> b.id.bytes)

    val blockDiff = Seq(blockDiffKey(b.id).bytes -> Longs.toByteArray(b.difficulty))

    val blockTimestamp = Seq(blockTimestampKey(b.id).bytes -> Longs.toByteArray(b.timestamp))

    // reference Bifrost #519 & #527 for discussion on this division of the score
    val blockScore = Seq(
      blockScoreKey(b.id).bytes -> Longs.toByteArray(scoreAt(b.parentId) + b.difficulty / 10000000000L)
    )

    val parentBlock =
      if (b.parentId == History.GenesisParentId) Seq()
      else Seq(blockParentKey(b.id).bytes -> b.parentId.bytes)

    val blockBloom = Seq(blockBloomKey(b.id).bytes -> b.bloomFilter.bytes)

    val wrappedUpdate: Seq[(Array[Byte], Array[Byte])] =
      blockK ++
      blockDiff ++
      blockTimestamp ++
      blockH ++
      idHeight ++
      blockScore ++
      bestBlock ++
      newTransactionsToBlockIds ++
      blockBloom ++
      parentBlock

    /* update storage */
    storage.update(b.id.bytes, Seq(), wrappedUpdate)

    /* update the cache the in the same way */
    wrappedUpdate.foreach(pair => blockCache.put(pair._1, Some(pair._2)))
  }

  /**
   * rollback storage to have the parent block as the last block
   *
   * @param parentId is the parent id of the block intended to be removed
   */
  def rollback(parentId: ModifierId): Try[Unit] = Try {
    blockCache.invalidateAll()
    storage.rollbackTo(parentId.bytes)
  }
}
