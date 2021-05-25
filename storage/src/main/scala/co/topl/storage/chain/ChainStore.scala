package co.topl.storage.chain

import akka.stream.scaladsl.Source
import akka.{Done, NotUsed}
import cats.data.EitherT
import cats.implicits._
import co.topl.modifier.ModifierId
import co.topl.modifier.block.{Block, BloomFilter}
import co.topl.utils.TimeProvider

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

  case class ErrorAsException(error: Error) extends Throwable

  trait Implicits {

    implicit class ErrorOps(error: Error) {
      def throwable: Throwable = ErrorAsException(error)
    }

    implicit class AkkaStreamOps(block: Block) {

      /**
       * Fetches the recursive parents of this Block
       */
      def blockHistory(implicit chainStore: ChainStore): Source[Block, NotUsed] =
        Source
          .fromMaterializer { case (mat, _) =>
            import mat.executionContext
            Source.unfoldAsync(block)(b =>
              chainStore.block(b.parentId).value.map {
                case Right(value)                         => Some((value, value))
                case Left(ChainStore.ModifierNotFound(_)) => None
                case Left(e)                              => throw e.throwable
              }
            )
          }
          .mapMaterializedValue(_ => NotUsed)

      def timestampHistory(implicit chainStore: ChainStore): Source[TimeProvider.Time, NotUsed] =
        Source
          .fromMaterializer { case (mat, _) =>
            import mat.executionContext
            Source.unfoldAsync(block.id)(b =>
              chainStore
                .parentIdOf(b)
                .flatMap(parentId => chainStore.timestampOf(parentId).map(parentId -> _))
                .value
                .map {
                  case Right((blockId, timestamp))          => Some((blockId, timestamp))
                  case Left(ChainStore.ModifierNotFound(_)) => None
                  case Left(e)                              => throw e.throwable
                }
            )
          }
          .mapMaterializedValue(_ => NotUsed)
    }
  }
}
