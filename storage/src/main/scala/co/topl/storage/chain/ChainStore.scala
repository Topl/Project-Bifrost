package co.topl.storage.chain

import akka.Done
import cats.implicits._
import cats.data.EitherT
import co.topl.modifier.ModifierId
import co.topl.modifier.block.{Block, BlockBody, BlockHeader, BloomFilter}
import co.topl.utils.Int128

import scala.concurrent.{ExecutionContext, Future}

trait ChainStore {
  def scoreOf(blockId:           ModifierId): EitherT[Future, ChainStore.Error, Int128]
  def blockIdsAtHeight(height:   Long): EitherT[Future, ChainStore.Error, List[ModifierId]]
  def blockHeader(blockHeaderId: ModifierId): EitherT[Future, ChainStore.Error, BlockHeader]
  def blockBody(blockBodyId:     ModifierId): EitherT[Future, ChainStore.Error, BlockBody]

  def block(blockId: ModifierId)(implicit ec: ExecutionContext): EitherT[Future, ChainStore.Error, Block] =
    for {
      header <- blockHeader(ModifierId.parseBytes(Array(BlockHeader.modifierTypeId) ++ blockId.getIdBytes).get)
      body   <- blockBody(ModifierId.parseBytes(Array(BlockBody.modifierTypeId) ++ blockId.getIdBytes).get)
    } yield Block.fromComponents(header, body)

  def bestBlockId(): EitherT[Future, ChainStore.Error, ModifierId]
  def bestBlock(): EitherT[Future, ChainStore.Error, Block]
  def contains(id:           ModifierId): EitherT[Future, ChainStore.Error, Boolean]
  def update(block:          Block, isBest: Boolean): EitherT[Future, ChainStore.Error, Done]
  def markBest(modifierId:   ModifierId)
  def rollbackTo(modifierId: ModifierId): EitherT[Future, ChainStore.Error, Done]
}

object ChainStore {
  sealed abstract class Error
}
