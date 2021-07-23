package co.topl.nodeView.history

import co.topl.modifier.block.{Block, PersistentNodeViewModifier}
import co.topl.modifier.transaction.Transaction
import co.topl.modifier.{ContainsModifiers, ModifierId}
import co.topl.network.message.SyncInfo
import co.topl.nodeView.NodeViewComponent
import co.topl.nodeView.history.GenericHistory.{HistoryComparisonResult, ModifierIds}
import co.topl.utils.TimeProvider

import scala.util.Try

trait HistoryReader[PM <: PersistentNodeViewModifier, SI <: SyncInfo]
    extends NodeViewComponent
    with ContainsModifiers[PM] {

  def height: Long
  def bestBlock: PM
  def difficulty: Long
  def bestBlockId: ModifierId
  def score: Long

  /**
   * Is there's no history, even genesis block
   */
  def isEmpty: Boolean

  /** Retrieve a series of PersistentNodeViewModifiers until the filter is satisfied */
  def filter(f: PM => Boolean): Seq[PM]

  /** get block id at a certain height */
  def modifierByHeight(height: Long): Option[PM]

  /** get parent block of a given block */
  def parentBlock(m: PM): Option[PM]

  /**
   * Gets the timestamps for 'count' number of blocks prior to (and including) the startBlock
   *
   * @param startBlock the starting block
   * @param count number of blocks to go back
   * @return timestamps of number of blocks including the given starting block
   */
  def getTimestampsFrom(startBlock: PM, count: Long): Vector[TimeProvider.Time]

  def transactionById(id: ModifierId): Option[(Transaction.TX, ModifierId, Long)]

  /**
   * Whether a modifier could be applied to the history
   *
   * @param modifier  - modifier to apply
   * @return `Success` if modifier can be applied, `Failure(ModifierError)` if can not
   */
  def applicableTry(modifier: PM): Try[Unit]

  //todo: output should be ID | Seq[ID]
  def openSurfaceIds(): Seq[ModifierId]

  /**
   * Ids of modifiers, that node with info should download and apply to synchronize
   */
  def continuationIds(info: SI, size: Int): ModifierIds

  /**
   * Information about our node synchronization status. Other node should be able to compare it's view with ours by
   * this syncInfo message and calculate modifiers missed by our node.
   *
   * @return
   */
  def syncInfo: SI

  /**
   * Whether another's node syncinfo shows that another node is ahead or behind ours
   *
   * @param other other's node sync info
   * @return Equal if nodes have the same history, Younger if another node is behind, Older if a new node is ahead
   */
  def compare(other: SI): HistoryComparisonResult

  /**
   * Checks whether the modifier can be appended to the canonical chain or a tine
   * in the chain cache
   *
   * @param modifier new block to be tracked in history
   * @return 'true' if the block extends a known block, false otherwise
   */
  def extendsKnownTine(modifier: PM): Boolean

  /**
   * Gets the modifier ID at the given height.
   * @param height the height of the block
   * @return the modifier ID if it exists
   */
  def idAtHeightOf(height: Long): Option[ModifierId]

  /**
   * Go back through chain and get block ids until condition `until` is satisfied
   *
   * @param startBlock     the modifier to start at
   * @param until the condition that indicates (when true) that recursion should stop
   * @param limit the maximum number of blocks to recurse back
   * @return the sequence of block information (TypeId, Id) that were collected until `until` was satisfied
   *         (None only if the parent for a block was not found) starting from the original `m`
   */
  def getIdsFrom(startBlock: Block, until: Block => Boolean, limit: Int): Option[Seq[ModifierId]]
}
