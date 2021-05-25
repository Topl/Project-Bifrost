package co.topl.storage.chain

import akka.Done
import cats.data.EitherT
import cats.implicits._
import co.topl.modifier.ModifierId
import co.topl.modifier.block.serialization.BlockSerializer
import co.topl.modifier.block.{Block, BloomFilter}
import co.topl.storage.kv.KeyValueStorage
import com.google.common.primitives.Longs
import scorex.crypto.hash.Blake2b256

import java.nio.charset.StandardCharsets
import scala.concurrent.{ExecutionContext, Future}

class KVChainStore(keyValueStorage: KeyValueStorage[Array[Byte], Array[Byte], Array[Byte]])(implicit
  ec:                               ExecutionContext
) extends ChainStore {

  final private val BestBlockIdKey = Array.fill(32)(-1: Byte)

  override def scoreOf(blockId: ModifierId): EitherT[Future, ChainStore.Error, Long] =
    fetchPrefixed("score", blockId).map(Longs.fromByteArray)

  override def heightOf(blockId: ModifierId): EitherT[Future, ChainStore.Error, Long] =
    fetchPrefixed("height", blockId).map(Longs.fromByteArray)

  override def timestampOf(blockId: ModifierId): EitherT[Future, ChainStore.Error, Long] =
    fetchPrefixed("timestamp", blockId).map(Longs.fromByteArray)

  override def difficultyOf(blockId: ModifierId): EitherT[Future, ChainStore.Error, Long] =
    fetchPrefixed("difficulty", blockId).map(Longs.fromByteArray)

  override def bloomOf(blockId: ModifierId): EitherT[Future, ChainStore.Error, BloomFilter] =
    fetchPrefixed("bloom", blockId)
      .subflatMap(BloomFilter.parseBytes(_).toEither.leftMap(ChainStore.ExceptionError(_): ChainStore.Error))

  override def parentIdOf(blockId: ModifierId): EitherT[Future, ChainStore.Error, ModifierId] =
    fetchPrefixed("parentId", blockId)
      .subflatMap(ModifierId.parseBytes(_).toEither.leftMap(ChainStore.ExceptionError(_): ChainStore.Error))

  override def blockIdAtHeight(height: Long): EitherT[Future, ChainStore.Error, ModifierId] =
    keyValueStorage
      .get(heightKey(height))
      .leftMap {
        case KeyValueStorage.NotFound(_) => ChainStore.HeightNotFound(height): ChainStore.Error
        case e                           => ChainStore.DomainChainStoreError(e): ChainStore.Error
      }
      .subflatMap(ModifierId.parseBytes(_).toEither.leftMap(ChainStore.ExceptionError(_): ChainStore.Error))

  override def block(blockId: ModifierId): EitherT[Future, ChainStore.Error, Block] =
    keyValueStorage
      .get(blockId.getIdBytes)
      .leftMap {
        case KeyValueStorage.NotFound(_) => ChainStore.ModifierNotFound(blockId): ChainStore.Error
        case e                           => ChainStore.DomainChainStoreError(e): ChainStore.Error
      }
      .subflatMap(BlockSerializer.parseBytes(_).toEither.leftMap(ChainStore.ExceptionError(_): ChainStore.Error))

  override def bestBlockId(): EitherT[Future, ChainStore.Error, ModifierId] =
    keyValueStorage
      .get(BestBlockIdKey)
      .leftMap(e => ChainStore.DomainChainStoreError(e): ChainStore.Error)
      .subflatMap(ModifierId.parseBytes(_).toEither.leftMap(ChainStore.ExceptionError(_): ChainStore.Error))

  override def bestBlock(): EitherT[Future, ChainStore.Error, Block] =
    bestBlockId().flatMap(block)

  override def contains(id: ModifierId): EitherT[Future, ChainStore.Error, Boolean] =
    keyValueStorage
      .contains(Blake2b256(id.getIdBytes))
      .leftMap(e => ChainStore.DomainChainStoreError(e): ChainStore.Error)

  override def update(block: Block, isBest: Boolean): EitherT[Future, ChainStore.Error, Done] =
    entriesForUpdate(block, isBest)
      .flatMap(
        keyValueStorage
          .put(version = block.id.bytes)(_: _*)
          .leftMap(e => ChainStore.DomainChainStoreError(e): ChainStore.Error)
      )

  override def rollbackTo(modifierId: ModifierId): EitherT[Future, ChainStore.Error, Done] =
    keyValueStorage
      .rollbackTo(Blake2b256(modifierId.getIdBytes))
      .leftMap(e => ChainStore.DomainChainStoreError(e): ChainStore.Error)

  private def fetchPrefixed(prefix: String, modifierId: ModifierId): EitherT[Future, ChainStore.Error, Array[Byte]] =
    keyValueStorage
      .get(prefixedKey(prefix, modifierId))
      .leftMap {
        case KeyValueStorage.NotFound(_) => ChainStore.ModifierNotFound(modifierId): ChainStore.Error
        case e                           => ChainStore.DomainChainStoreError(e): ChainStore.Error
      }

  private def prefixedKey(prefix: String, modifierId: ModifierId): Array[Byte] =
    Blake2b256(prefix.getBytes(StandardCharsets.UTF_8) ++ modifierId.getIdBytes)

  private def heightKey(height: Long): Array[Byte] =
    Blake2b256(Longs.toByteArray(height))

  private def entriesForUpdate(
    block:  Block,
    isBest: Boolean
  ): EitherT[Future, ChainStore.Error, List[(Array[Byte], Array[Byte])]] =
    for {
      parentScore  <- scoreOf(block.parentId)
      parentHeight <- heightOf(block.parentId)
    } yield entriesForUpdate(block, isBest, parentScore, parentHeight)

  private def entriesForUpdate(
    block:        Block,
    isBest:       Boolean,
    parentScore:  Long,
    parentHeight: Long
  ): List[(Array[Byte], Array[Byte])] = {
    val blockEntry =
      block.id.getIdBytes -> block.bytes

    val blockHeaderData =
      List(
        prefixedKey("height", block.id)     -> Longs.toByteArray(parentHeight + 1),
        heightKey(block.height)             -> Longs.toByteArray(parentHeight + 1),
        prefixedKey("difficulty", block.id) -> Longs.toByteArray(block.difficulty),
        prefixedKey("timestamp", block.id)  -> Longs.toByteArray(block.timestamp),
        prefixedKey("score", block.id)      -> Longs.toByteArray(parentScore + (block.difficulty / 10000000000L))
      )

    val transactionEntries =
      block.transactions.map(tx => tx.id.getIdBytes -> block.id.getIdBytes)

    val optionalEntries =
      Some(isBest).filter(identity).map(_ => BestBlockIdKey -> block.id.bytes) ++
      Some(block.parentId == ModifierId.genesisParentId)
        .filter(identity)
        .map(_ => prefixedKey("parent", block.id) -> block.parentId.bytes)

    List(blockEntry) ++ blockHeaderData ++ transactionEntries ++ optionalEntries
  }
}
