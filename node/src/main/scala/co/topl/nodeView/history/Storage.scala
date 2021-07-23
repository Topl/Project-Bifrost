package co.topl.nodeView.history

import co.topl.crypto.hash.blake2b256
import co.topl.crypto.hash.digest.Digest32
import co.topl.modifier.ModifierId
import co.topl.modifier.block.serialization.BlockSerializer
import co.topl.modifier.block.{Block, BloomFilter}
import co.topl.modifier.transaction.Transaction
import co.topl.nodeView.KeyValueStore
import co.topl.utils.Logging
import com.google.common.primitives.Longs
import io.iohk.iodb.ByteArrayWrapper

import scala.util.Try

class Storage(private[nodeView] val keyValueStore: KeyValueStore, keySize: Int) extends Logging {
  /* ------------------------------- Cache Initialization ------------------------------- */
  type KEY = ByteArrayWrapper
  type VAL = ByteArrayWrapper
  /* ------------------------------------------------------------------------------------- */

  private val bestBlockIdKey = Array.fill(keySize)(-1: Byte)

  def scoreAt(b: ModifierId): Long = scoreOf(b).getOrElse(0L)

  def heightAt(b: ModifierId): Long = heightOf(b).getOrElse(0L)

  def difficultyAt(b: ModifierId): Long = difficultyOf(b).getOrElse(0L)

  def bestBlockId: ModifierId =
    keyValueStore
      .get(ByteArrayWrapper(bestBlockIdKey))
      .flatMap(d => ModifierId.parseBytes(d.data).toOption)
      .getOrElse(History.GenesisParentId)

  def bestBlock: Block =
    modifierById(bestBlockId).getOrElse(throw new Error("Unable to retrieve best block from storage"))

  /** Check for the existence of a modifier in storage without parsing the bytes */
  def containsModifier(id: ModifierId): Boolean =
    keyValueStore.get(ByteArrayWrapper(id.getIdBytes)).isDefined

  /** Retrieve a transaction and its block details from storage */
  def lookupConfirmedTransaction(id: ModifierId): Option[(Transaction.TX, ModifierId, Long)] =
    id.getModType match {
      case Transaction.modifierTypeId =>
        keyValueStore
          .get(ByteArrayWrapper(id.getIdBytes))
          .flatMap(keyValueStore.get)
          .flatMap(bwBlock => BlockSerializer.parseBytes(bwBlock.data.tail).toOption)
          .map(block => (block.transactions.find(_.id == id).get, block.id, block.height))

      case _ => None
    }

  /** Retrieve a block from storage */
  def modifierById(id: ModifierId): Option[Block] =
    id.getModType match {
      case Block.modifierTypeId =>
        keyValueStore
          .get(ByteArrayWrapper(id.getIdBytes))
          .flatMap(bwBlock => BlockSerializer.parseBytes(bwBlock.data.tail).toOption)

      case _ =>
        //
        None
    }

  /** These methods allow us to lookup top-level information from blocks using the special keys defined below */
  def scoreOf(blockId: ModifierId): Option[Long] =
    keyValueStore
      .get(ByteArrayWrapper(blockScoreKey(blockId).value))
      .map(b => Longs.fromByteArray(b.data))

  def heightOf(blockId: ModifierId): Option[Long] =
    keyValueStore
      .get(ByteArrayWrapper(blockHeightKey(blockId).value))
      .map(b => Longs.fromByteArray(b.data))

  def timestampOf(blockId: ModifierId): Option[Long] =
    keyValueStore
      .get(ByteArrayWrapper(blockTimestampKey(blockId).value))
      .map(b => Longs.fromByteArray(b.data))

  def idAtHeightOf(height: Long): Option[ModifierId] =
    keyValueStore
      .get(ByteArrayWrapper(idHeightKey(height).value))
      .flatMap(id => ModifierId.parseBytes(id.data).toOption)

  def difficultyOf(blockId: ModifierId): Option[Long] =
    keyValueStore
      .get(ByteArrayWrapper(blockDiffKey(blockId).value))
      .map(b => Longs.fromByteArray(b.data))

  def bloomOf(blockId: ModifierId): Option[BloomFilter] =
    keyValueStore
      .get(ByteArrayWrapper(blockBloomKey(blockId).value))
      .flatMap(b => BloomFilter.parseBytes(b.data).toOption)

  def parentIdOf(blockId: ModifierId): Option[ModifierId] =
    keyValueStore
      .get(ByteArrayWrapper(blockParentKey(blockId).value))
      .flatMap(d => ModifierId.parseBytes(d.data).toOption)

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

    val blockH = Seq(blockHeightKey(b.id) -> Longs.toByteArray(heightAt(b.parentId) + 1))

    val idHeight = Seq(idHeightKey(heightAt(b.parentId) + 1) -> b.id.bytes)

    val blockDiff = Seq(blockDiffKey(b.id) -> Longs.toByteArray(b.difficulty))

    val blockTimestamp = Seq(blockTimestampKey(b.id) -> Longs.toByteArray(b.timestamp))

    // reference Bifrost #519 & #527 for discussion on this division of the score
    val blockScore = Seq(blockScoreKey(b.id) -> Longs.toByteArray(scoreAt(b.parentId) + b.difficulty / 10000000000L))

    val parentBlock =
      if (b.parentId == History.GenesisParentId) Seq()
      else Seq(blockParentKey(b.id) -> b.parentId.bytes)

    val blockBloom = Seq(blockBloomKey(b.id) -> b.bloomFilter.bytes)

    val wrappedUpdate =
      (blockK ++
        blockDiff ++
        blockTimestamp ++
        blockH ++
        idHeight ++
        blockScore ++
        bestBlock ++
        newTransactionsToBlockIds ++
        blockBloom ++
        parentBlock)
        .map { case (k: Array[Byte], v) => ByteArrayWrapper(k) -> ByteArrayWrapper(v) }

    /* update storage */
    keyValueStore.update(ByteArrayWrapper(b.id.bytes), Seq(), wrappedUpdate)
  }

  /**
   * rollback storage to have the parent block as the last block
   *
   * @param parentId is the parent id of the block intended to be removed
   */
  def rollback(parentId: ModifierId): Try[Unit] = Try {
    keyValueStore.rollback(ByteArrayWrapper(parentId.bytes))
  }
}
