package co.topl.storage.chain

import akka.Done
import cats.data.EitherT
import co.topl.modifier.ModifierId
import co.topl.modifier.block.{Block, BloomFilter}
import co.topl.storage.kv.KeyValueStorage
import co.topl.utils.Int128
import com.google.common.primitives.Longs
import scorex.crypto.hash.{Blake2b256, Digest32}

import java.nio.charset.StandardCharsets
import scala.concurrent.Future

class KVChainStore(keyValueStorage: KeyValueStorage) extends ChainStore {
  import KVChainStore._

  override def scoreOf(blockId: ModifierId): EitherT[Future, ChainStore.Error, Int128] = ???

  override def heightOf(blockId: ModifierId): EitherT[Future, ChainStore.Error, Int128] = ???

  override def timestampOf(blockId: ModifierId): EitherT[Future, ChainStore.Error, Long] = ???

  override def blockIdAtHeight(height: Long): EitherT[Future, ChainStore.Error, ModifierId] = ???

  override def difficultyOf(blockId: ModifierId): EitherT[Future, ChainStore.Error, Int128] = ???

  override def bloomOf(blockId: ModifierId): EitherT[Future, ChainStore.Error, BloomFilter] = ???

  override def parentIdOf(blockId: ModifierId): EitherT[Future, ChainStore.Error, ModifierId] = ???

  override def bestBlockId(): EitherT[Future, ChainStore.Error, ModifierId] = ???

  override def bestBlock(): EitherT[Future, ChainStore.Error, Block] = ???

  override def contains(id: ModifierId): EitherT[Future, ChainStore.Error, Boolean] = ???

  override def update(block: Block, isBest: Boolean): EitherT[Future, ChainStore.Error, Done] = ???

  override def rollbackTo(modifierId: ModifierId): EitherT[Future, ChainStore.Error, Done] = ???
}

object KVChainStore {

  implicit private class ModifierIdHelpers(modifierId: ModifierId) {

    def scoreKey: Array[Byte] =
      prependedKey("score")

    def heightKey: Array[Byte] =
      prependedKey("heightKey")

    def difficultyKey: Array[Byte] =
      prependedKey("difficulty")

    def timestampKey: Array[Byte] =
      prependedKey("timestamp")

    def bloomKey: Array[Byte] =
      prependedKey("bloom")

    def parentKey: Array[Byte] =
      prependedKey("parentId")

    private def prependedKey(name: String): Array[Byte] =
      Blake2b256(name.getBytes(StandardCharsets.UTF_8) ++ modifierId.getIdBytes)
  }

  implicit private class LongHelpers(value: Long) {

    def heightKey: Digest32 =
      Blake2b256(Longs.toByteArray(value))
  }
}
