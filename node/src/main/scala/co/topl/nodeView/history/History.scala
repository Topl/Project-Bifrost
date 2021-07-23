package co.topl.nodeView.history

import co.topl.consensus.{BlockValidator, DifficultyBlockValidator, SyntaxBlockValidator}
import co.topl.modifier.ModifierId
import co.topl.modifier.NodeViewModifier.ModifierTypeId
import co.topl.modifier.block.Block
import co.topl.modifier.transaction.Transaction
import co.topl.network.message.BifrostSyncInfo
import co.topl.nodeView.history.GenericHistory._
import co.topl.nodeView.history.History.GenesisParentId
import co.topl.db.LDBVersionedStore
import co.topl.settings.AppSettings
import co.topl.utils.{Logging, TimeProvider}

import java.io.File
import scala.annotation.tailrec
import scala.util.{Failure, Success, Try}

/**
 * A representation of the entire blockchain (whether it's a blocktree, blockchain, etc.)
 *
 * @param storage    a wrapper primarily for the LSMStore and for storage of the minimal state
 * @param validators rule sets that dictate validity of blocks in the history
 */
class History(
  val storage:        Storage, //todo: JAA - make this private[history]
  fullBlockProcessor: BlockProcessor,
  validators:         Seq[BlockValidator[Block]]
) extends GenericHistory[Block, BifrostSyncInfo, History]
    with Logging {

  override type NVCT = History

  lazy val bestBlockId: ModifierId = storage.bestBlockId
  lazy val bestBlock: Block = storage.bestBlock
  lazy val height: Long = storage.heightAt(bestBlockId)
  lazy val score: Long = storage.scoreAt(bestBlockId)
  lazy val difficulty: Long = storage.difficultyAt(bestBlockId)

  /** Public method to close storage */
  def closeStorage(): Unit = {
    log.info("Attempting to close history storage")
    storage.storage.close()
  }

  /** If there's no history, even genesis block */
  override def isEmpty: Boolean = height <= 0

  override def applicable(block: Block): Boolean = storage.containsModifier(block.parentId)

  override def modifierById(id: ModifierId): Option[Block] = storage.modifierById(id)

  override def modifierByHeight(height: Long): Option[Block] =
    storage.idAtHeightOf(height).flatMap(storage.modifierById)

  def transactionById(id: ModifierId): Option[(Transaction.TX, ModifierId, Long)] =
    storage.lookupConfirmedTransaction(id)

  override def contains(id: ModifierId): Boolean =
    (id == History.GenesisParentId) || storage.containsModifier(id) || fullBlockProcessor.contains(id)

  private def isGenesis(b: Block): Boolean = b.parentId == History.GenesisParentId

  def parentBlock(m: Block): Option[Block] = modifierById(m.parentId)

  /** Return last count headers from best headers chain if exist or chain up to genesis otherwise */
  def lastHeaders(count: Int, offset: Int = 0): IndexedSeq[ModifierId] =
    getBlocksFrom(bestBlock, count).map(block => block.id).toIndexedSeq

  /**
   * Adds block to chain and updates storage (difficulty, score, etc.) relating to that
   *
   * @param block block to append
   * @return the update history including `block` as the most recent block
   */
  override def append(block: Block): Try[(History, ProgressInfo[Block])] = Try {

    log.debug(s"Trying to append block ${block.id} to history")

    // test new block against all validators
    val validationResults =
      if (!isGenesis(block)) {
        validators.map(_.validate(block)).map {
          case Failure(e) =>
            log.warn(s"Block validation failed", e)
            false

          case _ => true
        }
      } else Seq(true) // skipping validation for genesis block

    // check if all block validation passed
    if (validationResults.forall(_ == true)) {
      val res: (History, ProgressInfo[Block]) = {

        if (isGenesis(block)) {
          storage.update(block, isBest = true)
          val progInfo = ProgressInfo(None, Seq.empty, Seq(block), Seq.empty)

          // construct result and return
          (new History(storage, fullBlockProcessor, validators), progInfo)

        } else {
          val progInfo: ProgressInfo[Block] =
            // Check if the new block extends the last best block
            if (block.parentId.equals(storage.bestBlockId)) {
              log.debug(s"New best block ${block.id.toString}")

              // update storage
              storage.update(block, isBest = true)
              ProgressInfo(None, Seq.empty, Seq(block), Seq.empty)

              // if not, we'll check for a fork
            } else {
              // we want to check for a fork
              val forkProgInfo = fullBlockProcessor.process(this, block)

              // check if we need to update storage after checking for forks
              if (forkProgInfo.branchPoint.nonEmpty) {
                storage.rollback(forkProgInfo.branchPoint.get)

                forkProgInfo.toApply.foreach(b => storage.update(b, isBest = true))
              }

              forkProgInfo
            }

          // construct result and return
          (new History(storage, fullBlockProcessor, validators), progInfo)
        }
      }
      log.info(
        s"${Console.CYAN} Block ${block.id} appended to parent ${block.parentId} at height ${block.height} with score ${storage
          .scoreOf(block.id)}.${Console.RESET}"
      )
      res

    } else {
      throw new Error(s"${Console.RED}Failed to append block ${block.id} to history.${Console.RESET}")
    }
  }

  /**
   * Removes this block (and its children) from the history and rolls back to the state after the parent block
   *
   * Maybe we should check drop the id passed in is the best block's id? or just get rid of the arg
   *
   * @param modifierId the id of the block to chop off
   * @return a new history which is the current history ending at the parent of the dropped block
   */
  override def drop(modifierId: ModifierId): History = {

    val block = storage.modifierById(modifierId).map { case b: Block => b }.get
    val parentBlock = storage.modifierById(block.parentId).get

    log.debug(s"Failed to apply block. Rollback BifrostState to ${parentBlock.id} from version ${block.id}")
    storage.rollback(parentBlock.id)
    new History(storage, fullBlockProcessor, validators)
  }

  /**
   * Calculates the changes necessary for a swap to a fork
   *
   * @param block the block that has a parent on the canonical chain but is not on the chain
   * @return ProgressInfo that specifies the blocks to delete (worse fork) and blocks to add (better fork)
   */
  def bestForkChanges(block: Block): ProgressInfo[Block] = {

    /* Get the two branches of the fork including their common block */
    val (newSuffix, oldSuffix) = commonBlockThenSuffixes(modifierById(block.parentId).get)

    log.debug(
      s"Processing fork for block ${block.id}: \n" +
      s"old: $oldSuffix\n" +
      s"new: $newSuffix"
    )

    /* Roll back to the common block */
    val rollbackPoint = newSuffix.headOption
    val throwBlocks = oldSuffix.tail.map(id => modifierById(id).get)

    /* Add the newSuffix to the rolled-back chain */
    val applyBlocks = newSuffix.tail.map(id => modifierById(id).get) ++ Seq(block)

    require(applyBlocks.nonEmpty)
    require(throwBlocks.nonEmpty)

    ProgressInfo[Block](rollbackPoint, throwBlocks, applyBlocks, Seq.empty)
  }

  /**
   * Forms a list of available blocks to build upon
   *
   * @return the blocks that are "exposed" for use
   */
  override def openSurfaceIds(): Seq[ModifierId] =
    if (isEmpty) Seq(History.GenesisParentId)
    else Seq(bestBlockId)
  // TODO return sequence of exposed endpoints?

  /**
   * Return specified number of Bifrost blocks, ordered back from last one
   *
   * @param count - how many blocks to return
   * @return blocks, in reverse order (starting from the most recent one)
   */
  def getBlocksFrom(startBlock: Block, count: Long): Seq[Block] = {
    @tailrec
    def loop(b: Block, acc: Seq[Block] = Seq()): Seq[Block] =
      if (acc.length >= count) acc
      else
        modifierById(b.parentId) match {
          case Some(parent: Block) => loop(parent, b +: acc)
          case _                   => b +: acc
        }

    if (isEmpty) Seq()
    else loop(startBlock)
  }

  /**
   * Go back through chain and get block ids until condition `until` is satisfied
   *
   * @param startBlock     the modifier to start at
   * @param until the condition that indicates (when true) that recursion should stop
   * @param limit the maximum number of blocks to recurse back
   * @return the sequence of block information (TypeId, Id) that were collected until `until` was satisfied
   *         (None only if the parent for a block was not found) starting from the original `m`
   */
  final def getIdsFrom(
    startBlock: Block,
    until:      Block => Boolean,
    limit:      Int = Int.MaxValue
  ): Option[Seq[ModifierId]] = {

    @tailrec
    def loop(block: Block, acc: Seq[Block]): Seq[Block] =
      if (acc.lengthCompare(limit) == 0 || until(block)) {
        acc
      } else {
        parentBlock(block) match {
          case Some(parent: Block)         => loop(parent, acc :+ parent)
          case None if acc.contains(block) => acc
          case _                           => acc :+ block
        }
      }

    if (limit == 0) None
    else Option(loop(startBlock, Seq(startBlock)).map(_.id).reverse)
  }

  def getTimestampsFrom(startBlock: Block, count: Long): Vector[TimeProvider.Time] =
    History.getTimestamps(storage, count, startBlock)

  /**
   * Retrieve a sequence of blocks until the given filter is satisifed
   *
   * @param f filter function to be applied for retrieving blocks
   * @return a sequence of blocks starting from the tip of the chain
   */
  def filter(f: Block => Boolean): Seq[Block] = {
    @tailrec
    def loop(m: Block, acc: Seq[Block]): Seq[Block] = parentBlock(m) match {
      case Some(parent) => if (f(m)) loop(parent, m +: acc) else loop(parent, acc)
      case None         => if (f(m)) m +: acc else acc
    }

    loop(bestBlock, Seq())
  }

  /**
   * Whether another node's syncinfo shows that another node is ahead or behind ours
   *
   * @param info other's node sync info
   * @return Equal if nodes have the same history, Younger if another node is behind, Older if a new node is ahead
   */
  override def compare(info: BifrostSyncInfo): HistoryComparisonResult =
    Option(bestBlockId) match {

      //Our best header is the same as other node best header
      case Some(id) if info.lastBlockIds.lastOption.contains(id) => Equal

      //Our best header is in other node best chain, but not at the last position
      case Some(id) if info.lastBlockIds.contains(id) => Older

      //Other history is empty, our contain some headers
      case Some(_) if info.lastBlockIds.isEmpty => Younger

      //We are on different forks now.
      case Some(_) =>
        if (info.lastBlockIds.view.reverse.exists(id => contains(id))) {
          //Return Younger, because we can send blocks from our fork that other node can download.
          Fork
        } else {
          //We don't have any of id's from other's node sync info in history.
          //We don't know whether we can sync with it and what blocks to send in Inv message.
          //Assume it is older and far ahead from us
          Older
        }

      //Both nodes do not keep any blocks
      case None if info.lastBlockIds.isEmpty => Equal

      //Our history is empty, other contain some headers
      case None => Older
    }

  /**
   * Find common suffixes for two chains starting from forkBlock
   *
   * @param forkBlock the common source of a potential fork (normally just the parent of bestBlock)
   * @param limit     how far back to check for a common block
   * @return sequences which contain the last common block and variant blocks for the chains
   */
  final def commonBlockThenSuffixes(forkBlock: Block, limit: Int = Int.MaxValue): (Seq[ModifierId], Seq[ModifierId]) = {

    /* The entire chain that was "best" */
    val loserChain = getIdsFrom(bestBlock, isGenesis, limit).get

    /* `in` specifies whether `loserChain` has this block */
    def in(m: Block): Boolean = loserChain.contains(m.id)

    /* Finds the chain of blocks back from `forkBlock` until a common block to `loserChain` is found */
    val winnerChain = getIdsFrom(forkBlock, in, limit).get

    val i = loserChain.indexWhere(id => id == winnerChain.head)

    /* The two segments including their common block */
    (winnerChain, loserChain.takeRight(loserChain.length - i))

  }.ensuring(r => r._1.head == r._2.head)

  /**
   * Report that modifier is valid from point of view of the state component
   *
   * @param modifier - valid modifier
   * @return modified history
   */
  override def reportModifierIsValid(modifier: Block): History = {
    log.debug(s"Modifier ${modifier.id} is a valid block")
    this
  }

  /**
   * Report that modifier is invalid from other nodeViewHolder components point of view
   *
   * @param modifier     - invalid modifier
   * @param progressInfo - what suffix failed to be applied because of an invalid modifier
   * @return modified history and new progress info
   */
  override def reportModifierIsInvalid(
    modifier:     Block,
    progressInfo: ProgressInfo[Block]
  ): (History, ProgressInfo[Block]) = {
    drop(modifier.id)
    val progInfo: ProgressInfo[Block] = ProgressInfo(None, Seq.empty, Seq.empty, Seq.empty)
    (new History(storage, fullBlockProcessor, validators), progInfo)
  }

  /**
   * Whether a modifier could be applied to the history
   *
   * @param modifier - modifier to apply
   * @return `Success` if modifier can be applied, `Failure(ModifierError)` if can not
   */
  override def applicableTry(modifier: Block): Try[Unit] =
    modifier match {
      case b: Block => Success(())
    }

  /**
   * Checks whether the modifier can be appended to the canonical chain or a tine
   * in the chain cache
   *
   * @param modifier new block to be tracked in history
   * @return 'true' if the block extends a known block, false otherwise
   */
  override def extendsKnownTine(modifier: Block): Boolean =
    applicable(modifier) || fullBlockProcessor.applicableInCache(modifier)

  //TODO used in tests, but should replace with HistoryReader.continuationIds
  /**
   * Gather blocks from after `from` that should be added to the chain
   *
   * @param from the list of known blocks from which to gather continuation
   * @param size the number of blocks to return after `from`
   * @return
   */
  def continuationIds(from: Seq[(ModifierTypeId, ModifierId)], size: Int): Option[Seq[(ModifierTypeId, ModifierId)]] = {

    /* Whether m is a genesis block or is in `from` */
    def inList(m: Block): Boolean = idInList(m.id) || isGenesis(m)

    def idInList(id: ModifierId): Boolean = from.exists(f => f._2 == id)

    /* Extend chain back until end of `from` is found, then return <size> blocks continuing from that point */
    getIdsFrom(bestBlock, inList) match {
      case Some(chain) if chain.exists(id => idInList(id)) => Some(chain.take(size).map(id => id.getModType -> id))
      case Some(_) =>
        log.warn("Found chain without ids from remote")
        None
      case _ => None
    }
  }

  /**
   * Retrieve a segment of our chain to send to a remote node. This method works by identifying
   * a common ancestor by comparing the SyncInfo sent from the remote peer to our chain. If a
   * common ancestor can be found in the local history, an extension from that ancestor is
   * returned as a set of continuationIds for the remote node to request their missing modifiers.
   * NOTE: SyncInfo is computed from the remote node's tip down their chain, while continuationId's
   *       are computer from a common ancestor up towards the tip of the local node's chain. The
   *       local node is only able to identify a common ancestor if the remote SyncInfo contains
   *       a block that the local node knows about. For bootstrapping this works well because there
   *       is no limit to how deep the common ancestor can be. However, in the case where the remote node
   *       is on a fork with an ancestor more than SyncInfo.lastBlockIds.length back, the local node
   *       will be unable to identify a common ancestor and therefore will be unable to send a list
   *       of continuationId's
   *
   *       //TODO: JAA - we should use the height that is now included in a block to our advantage as
   *                     this will allow us to overcome the limitation of finding a common ancestor.
   *                     Proabbly want to add a field to SyncInfo of `lastBlockHeight` so we can
   *                     immediately identify the common ancestor instead of having to scan our chain.
   * @param info a message from a remote node containing the last block id's they are aware of
   * @param size limit of the number of block id's to send to the remote node
   * @return a seq of modifier ids to help the remote node sync up their chain
   */
  override def continuationIds(info: BifrostSyncInfo, size: Int): ModifierIds = {

    /** Helper function to avoid repetition */
    def getChainIds(heightFrom: Long): ModifierIds =
      storage
        .idAtHeightOf(heightFrom)
        .flatMap(modifierById)
        .flatMap(getIdsFrom(_, _ => false, size))
        .map(_.map(mod => mod.getModType -> mod))
        .getOrElse(Seq())

    // case where we are at genesis
    if (isEmpty) {
      info.startingPoints

      // case where the remote is at genesis
    } else if (info.lastBlockIds.isEmpty) {
      val heightFrom = Math.min(height, size)
      getChainIds(heightFrom)

      // case where the remote node is younger or on a recent fork (branchPoint less than size blocks back)
    } else {
      val commonAncestor: Option[ModifierId] =
        info.lastBlockIds.view.reverse.find(storage.containsModifier).orElse(None)

      commonAncestor.toSeq.flatMap { branchPoint =>
        val remoteHeight = storage.heightOf(branchPoint).get
        val heightFrom = Math.min(height, remoteHeight + size)
        getChainIds(heightFrom)
      }
    }
  }

  /**
   * Information about our node synchronization status. Other node should be able to compare it's view with ours by
   * this syncInfo message and calculate modifiers missed by our node.
   */
  override def syncInfo: BifrostSyncInfo =
    if (isEmpty)
      BifrostSyncInfo(Seq.empty)
    else {
      val startingPoints = lastHeaders(BifrostSyncInfo.MaxLastBlocks)

      if (startingPoints.headOption.contains(GenesisParentId))
        BifrostSyncInfo(GenesisParentId +: startingPoints)
      else
        BifrostSyncInfo(startingPoints)
    }

  override def idAtHeightOf(height: Long): Option[ModifierId] = storage.idAtHeightOf(height)
}

object History extends Logging {

  val GenesisParentId: ModifierId = ModifierId.genesisParentId

  def readOrGenerate(settings: AppSettings): History = {

    /** Setup persistent on-disk storage */
    val dataDir = settings.application.dataDir.ensuring(_.isDefined, "A data directory must be specified").get
    val file = new File(s"$dataDir/blocks")
    file.mkdirs()
    val blockStorageDB = new LDBVersionedStore(file, 100)
    val storage = new Storage(blockStorageDB, settings.application.cacheExpire, settings.application.cacheSize)

    /** This in-memory cache helps us to keep track of tines sprouting off the canonical chain */
    val blockProcessor = BlockProcessor(settings.network.maxChainCacheDepth)

    val validators = Seq(
      new DifficultyBlockValidator(storage, blockProcessor),
      new SyntaxBlockValidator
    )

    new History(storage, blockProcessor, validators)
  }

  /** Gets the timestamps for 'count' number of blocks prior to (and including) the startBlock */
  def getTimestamps(storage: Storage, count: Long, startBlock: Block): Vector[TimeProvider.Time] = {
    @tailrec
    def loop(id: ModifierId, acc: Vector[TimeProvider.Time] = Vector()): Vector[TimeProvider.Time] =
      if (acc.length > count) acc
      else
        storage.parentIdOf(id) match {
          case Some(parentId: ModifierId) =>
            val parentTimestamp = storage.timestampOf(parentId).get
            loop(parentId, parentTimestamp +: acc)

          case _ => acc
        }

    loop(startBlock.id, Vector(startBlock.timestamp))
  }
}
