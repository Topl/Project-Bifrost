package co.topl.nodeView.history

import akka.actor.typed._
import akka.actor.typed.scaladsl.Behaviors
import akka.util.Timeout
import cats.data.EitherT
import cats.implicits._
import co.topl.modifier.ModifierId
import co.topl.modifier.block.serialization.BlockSerializer
import co.topl.modifier.block.{Block, BloomFilter}
import co.topl.modifier.transaction.Transaction
import co.topl.modifier.transaction.validation.SemanticValidation
import co.topl.utils.IdiomaticScalaTransition.implicits.toEitherOps
import co.topl.utils.{Int128, Logging}
import com.google.common.cache.{CacheBuilder, CacheLoader, LoadingCache}
import com.google.common.primitives.Longs
import io.iohk.iodb.{ByteArrayWrapper, LSMStore}
import scorex.crypto.hash.{Blake2b256, Digest32}

import scala.annotation.tailrec
import scala.concurrent.Future
import scala.concurrent.duration.MILLISECONDS
import scala.util.Try

class Storage(private[history] val storage: LSMStore, private val cacheExpire: Int, private val cacheSize: Int)
    extends Logging {
  /* ------------------------------- Cache Initialization ------------------------------- */
  type KEY = ByteArrayWrapper
  type VAL = ByteArrayWrapper

  private val blockLoader: CacheLoader[KEY, Option[VAL]] = new CacheLoader[KEY, Option[VAL]] {

    def load(key: KEY): Option[VAL] =
      storage.get(key) match {
        case Some(blockData: VAL) => Some(blockData)
        case _                    => None
      }
  }

  val blockCache: LoadingCache[KEY, Option[VAL]] = CacheBuilder
    .newBuilder()
    .expireAfterAccess(cacheExpire, MILLISECONDS)
    .maximumSize(cacheSize)
    .build[KEY, Option[VAL]](blockLoader)
  /* ------------------------------------------------------------------------------------- */

  private val bestBlockIdKey = Array.fill(storage.keySize)(-1: Byte)

  def scoreAt(b: ModifierId): Long = scoreOf(b).getOrElse(0L)

  def heightAt(b: ModifierId): Long = heightOf(b).getOrElse(0L)

  def difficultyAt(b: ModifierId): Long = difficultyOf(b).getOrElse(0L)

  def bestBlockId: ModifierId =
    blockCache
      .get(ByteArrayWrapper(bestBlockIdKey))
      .flatMap(d => ModifierId.parseBytes(d.data).toOption)
      .getOrElse(History.GenesisParentId)

  def bestBlock: Block =
    modifierById(bestBlockId).getOrElse(throw new Error("Unable to retrieve best block from storage"))

  /** Check for the existence of a modifier in storage without parsing the bytes */
  def containsModifier(id: ModifierId): Boolean =
    blockCache.get(ByteArrayWrapper(id.getIdBytes)).isDefined

  /** Retrieve a transaction and its block details from storage */
  def lookupConfirmedTransaction(id: ModifierId): Option[(Transaction.TX, ModifierId, Long)] =
    id.getModType match {
      case Transaction.modifierTypeId =>
        blockCache
          .get(ByteArrayWrapper(id.getIdBytes))
          .flatMap(blockCache.get)
          .flatMap(bwBlock => BlockSerializer.parseBytes(bwBlock.data.tail).toOption)
          .map(block => (block.transactions.find(_.id == id).get, block.id, block.height))

      case _ => None
    }

  /** Retrieve a block from storage */
  def modifierById(id: ModifierId): Option[Block] =
    id.getModType match {
      case Block.modifierTypeId =>
        blockCache
          .get(ByteArrayWrapper(id.getIdBytes))
          .flatMap(bwBlock => BlockSerializer.parseBytes(bwBlock.data.tail).toOption)

      case _ => None
    }

  /** These methods allow us to lookup top-level information from blocks using the special keys defined below */
  def scoreOf(blockId: ModifierId): Option[Long] =
    blockCache
      .get(ByteArrayWrapper(blockScoreKey(blockId)))
      .map(b => Longs.fromByteArray(b.data))

  def heightOf(blockId: ModifierId): Option[Long] =
    blockCache
      .get(ByteArrayWrapper(blockHeightKey(blockId)))
      .map(b => Longs.fromByteArray(b.data))

  def timestampOf(blockId: ModifierId): Option[Long] =
    blockCache
      .get(ByteArrayWrapper(blockTimestampKey(blockId)))
      .map(b => Longs.fromByteArray(b.data))

  def idAtHeightOf(height: Long): Option[ModifierId] =
    blockCache
      .get(ByteArrayWrapper(idHeightKey(height)))
      .flatMap(id => ModifierId.parseBytes(id.data).toOption)

  def difficultyOf(blockId: ModifierId): Option[Long] =
    blockCache
      .get(ByteArrayWrapper(blockDiffKey(blockId)))
      .map(b => Longs.fromByteArray(b.data))

  def bloomOf(blockId: ModifierId): Option[BloomFilter] =
    blockCache
      .get(ByteArrayWrapper(blockBloomKey(blockId)))
      .flatMap(b => BloomFilter.parseBytes(b.data).toOption)

  def parentIdOf(blockId: ModifierId): Option[ModifierId] =
    blockCache
      .get(ByteArrayWrapper(blockParentKey(blockId)))
      .flatMap(d => ModifierId.parseBytes(d.data).toOption)

  /**
   * The keys below are used to store top-level information about blocks that we might be interested in
   * without needing to parse the entire block from storage
   */
  private def blockScoreKey(blockId: ModifierId): Digest32 =
    Blake2b256("score".getBytes ++ blockId.getIdBytes)

  private def blockHeightKey(blockId: ModifierId): Digest32 =
    Blake2b256("height".getBytes ++ blockId.getIdBytes)

  private def blockDiffKey(blockId: ModifierId): Digest32 =
    Blake2b256("difficulty".getBytes ++ blockId.getIdBytes)

  private def blockTimestampKey(blockId: ModifierId): Digest32 =
    Blake2b256("timestamp".getBytes ++ blockId.getIdBytes)

  private def blockBloomKey(blockId: ModifierId): Digest32 =
    Blake2b256("bloom".getBytes ++ blockId.getIdBytes)

  private def blockParentKey(blockId: ModifierId): Digest32 =
    Blake2b256("parentId".getBytes ++ blockId.getIdBytes)

  private def idHeightKey(height: Long): Digest32 =
    Blake2b256(Longs.toByteArray(height))

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
        parentBlock).map { case (k, v) =>
        ByteArrayWrapper(k) -> ByteArrayWrapper(v)
      }

    /* update storage */
    storage.update(ByteArrayWrapper(b.id.bytes), Seq(), wrappedUpdate)

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
    storage.rollback(ByteArrayWrapper(parentId.bytes))
  }
}

trait ChainStoreReader[E] {
  def scoreOf(blockId:        ModifierId): EitherT[Future, E, Int128]
  def heightOf(blockId:       ModifierId): EitherT[Future, E, Int128]
  def timestampOf(blockId:    ModifierId): EitherT[Future, E, Long]
  def blockIdAtHeight(height: Long): EitherT[Future, E, ModifierId]
  def difficultyOf(blockId:   ModifierId): EitherT[Future, E, Int128]
  def bloomOf(blockId:        ModifierId): EitherT[Future, E, BloomFilter]
  def parentIdOf(blockId:     ModifierId): EitherT[Future, E, ModifierId]
  def bestBlockId(): EitherT[Future, E, ModifierId]
  def bestBlock(): EitherT[Future, E, Block]
  def contains(id: ModifierId): EitherT[Future, E, Boolean]
}

trait ChainStoreWriter[E] {
  def update(block:          Block, isBest: Boolean): EitherT[Future, E, Unit]
  def rollbackTo(modifierId: ModifierId): EitherT[Future, E, Unit]
}

class InMemoryChainStore(implicit system: ActorSystem[_])
    extends ChainStoreReader[InMemoryChainStore.Error]
    with ChainStoreWriter[InMemoryChainStore.Error] {

  import system.executionContext

  private val actor = system.systemActorOf(InMemoryChainStore.StoreActor(), "chain-store")

  override def scoreOf(blockId: ModifierId): EitherT[Future, InMemoryChainStore.Error, Int128] =
    currentBlocks().map(blocks =>
      SemanticValidation
        .takeWhileInclusive(blocks.toStream)(_.id != blockId)
        .foldLeft(0: Int128)((score, b) => score + b.difficulty / 10000000000L)
    )

  override def heightOf(blockId: ModifierId): EitherT[Future, InMemoryChainStore.Error, Int128] =
    currentBlocks().subflatMap(_.indexWhere(_.id == blockId) match {
      case -1 => Left(InMemoryChainStore.BlockNotFound(blockId))
      case i  => Right(i + 1)
    })

  override def timestampOf(blockId: ModifierId): EitherT[Future, InMemoryChainStore.Error, Long] =
    findBlock(blockId).map(_.timestamp)

  override def blockIdAtHeight(height: Long): EitherT[Future, InMemoryChainStore.Error, ModifierId] =
    currentBlocks().subflatMap(_.lift(height.toInt + 1).toRight(InMemoryChainStore.HeightNotFound(height)))

  override def difficultyOf(blockId: ModifierId): EitherT[Future, InMemoryChainStore.Error, Int128] =
    findBlock(blockId).map(_.difficulty)

  override def bloomOf(blockId: ModifierId): EitherT[Future, InMemoryChainStore.Error, BloomFilter] =
    findBlock(blockId).map(_.bloomFilter)

  override def parentIdOf(blockId: ModifierId): EitherT[Future, InMemoryChainStore.Error, ModifierId] =
    findBlock(blockId).map(_.parentId)

  override def bestBlockId(): EitherT[Future, InMemoryChainStore.Error, ModifierId] =
    bestBlock().map(_.id)

  override def bestBlock(): EitherT[Future, InMemoryChainStore.Error, Block] =
    currentBlocks().subflatMap(_.lastOption.toRight(InMemoryChainStore.HeightNotFound(0)))

  override def contains(id: ModifierId): EitherT[Future, InMemoryChainStore.Error, Boolean] =
    currentBlocks().map(_.exists(_.id == id))

  import akka.actor.typed.scaladsl.AskPattern._
  import scala.concurrent.duration._
  implicit private val timeout: Timeout = Timeout(1.seconds)

  override def update(block: Block, isBest: Boolean): EitherT[Future, InMemoryChainStore.Error, Unit] =
    EitherT(actor.ask(ref => InMemoryChainStore.StoreActor.Update(block, ref)))

  override def rollbackTo(modifierId: ModifierId): EitherT[Future, InMemoryChainStore.Error, Unit] =
    EitherT(actor.ask(ref => InMemoryChainStore.StoreActor.RollbackTo(modifierId, ref)))

  private def currentBlocks(): EitherT[Future, InMemoryChainStore.Error, List[Block]] =
    EitherT(actor.ask(ref => InMemoryChainStore.StoreActor.GetBlocks(ref)))

  private def findBlock(modifierId: ModifierId): EitherT[Future, InMemoryChainStore.Error, Block] =
    currentBlocks().subflatMap(blocks =>
      blocks.find(_.id == modifierId).toRight(InMemoryChainStore.BlockNotFound(modifierId))
    )
}

object InMemoryChainStore {
  sealed abstract class Error
  case class BlockNotFound(blockId: ModifierId) extends Error
  case class HeightNotFound(height: Long) extends Error

  private object StoreActor {
    sealed abstract class Message

    case class Update(block: Block, replyTo: ActorRef[Either[InMemoryChainStore.Error, Unit]]) extends Message

    case class GetBlocks(replyTo: ActorRef[Either[InMemoryChainStore.Error, List[Block]]]) extends Message

    case class RollbackTo(blockId: ModifierId, replyTo: ActorRef[Either[InMemoryChainStore.Error, Unit]])
        extends Message

    def apply(): Behavior[Message] = apply(State(Nil))

    def apply(state: State): Behavior[Message] =
      Behaviors.receiveMessage {
        case Update(block, replyTo) =>
          replyTo ! Right({})
          apply(state.copy(state.blocks :+ block))

        case GetBlocks(replyTo) =>
          replyTo ! Right(state.blocks)
          Behaviors.same
        case RollbackTo(blockId, replyTo) =>
          @tailrec
          def rolledBack(blocks: List[Block]): List[Block] =
            blocks match {
              case Nil                            => Nil
              case blocks :+ b if b.id == blockId => blocks
              case _                              => rolledBack(blocks.init)
            }
          val updatedState = state.copy(rolledBack(state.blocks))
          replyTo ! Right({})
          apply(updatedState)
      }

    case class State(
      blocks: List[Block]
    )
  }
}
