package co.topl.storage.chain

import akka.Done
import cats.data.EitherT
import co.topl.modifier.ModifierId
import co.topl.modifier.block.{Block, BloomFilter}

import scala.concurrent.Future

trait ChainStore {
  def scoreOf(blockId:      ModifierId): EitherT[Future, ChainStore.Error, Long]
  def heightOf(blockId:     ModifierId): EitherT[Future, ChainStore.Error, Long]
  def timestampOf(blockId:  ModifierId): EitherT[Future, ChainStore.Error, Long]
  def difficultyOf(blockId: ModifierId): EitherT[Future, ChainStore.Error, Long]
  def bloomOf(blockId:      ModifierId): EitherT[Future, ChainStore.Error, BloomFilter]
  def parentIdOf(blockId:   ModifierId): EitherT[Future, ChainStore.Error, ModifierId]

  def blockIdAtHeight(height: Long): EitherT[Future, ChainStore.Error, ModifierId]

  def block(blockId: ModifierId): EitherT[Future, ChainStore.Error, Block]

  def bestBlockId(): EitherT[Future, ChainStore.Error, ModifierId]
  def bestBlock(): EitherT[Future, ChainStore.Error, Block]

  def contains(id: ModifierId): EitherT[Future, ChainStore.Error, Boolean]

  def update(block: Block, isBest: Boolean): EitherT[Future, ChainStore.Error, Done]

  def rollbackTo(modifierId: ModifierId): EitherT[Future, ChainStore.Error, Done]
}

object ChainStore {
  sealed abstract class Error
  case class ModifierNotFound(modifierId: ModifierId) extends Error
  case class HeightNotFound(height: Long) extends Error
  case class DomainChainStoreError[T](error: T) extends Error
  case class ExceptionError(throwable: Throwable) extends Error
}
