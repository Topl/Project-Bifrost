package co.topl

import co.topl.modifier.block.Block
import co.topl.modifier.box.ArbitBox
import co.topl.settings.ProtocolSettings
import co.topl.utils.{Int128, TimeProvider}
import com.google.common.primitives.Longs
import scorex.crypto.hash.Blake2b256

import scala.concurrent.duration._
import scala.math.{max, min}

package object consensus {
  private var _protocolMngr: ProtocolVersioner = ProtocolVersioner.empty

  // Initialize or restore a consensus storage that keeps track of the maxStake, difficulty, height, and inflation
  private[consensus] var consensusStorage: ConsensusStorage = ConsensusStorage.emptyStorage()

  // setter
  private[consensus] def protocolMngr_=(value: ProtocolVersioner): Unit = _protocolMngr = value

  // getters
  def protocolMngr: ProtocolVersioner = _protocolMngr

  // number of blocks to use for determining the avg block delay
  def nxtBlockNum: Int = 3

  /** Find the rule set for the given app version and block height */
  def getProtocolRules(blockHeight: Long): ProtocolSettings =
    protocolMngr
      .current(blockHeight)
      .getOrElse(throw new Error("Unable to find applicable protocol rules"))

  def targetBlockTime(blockHeight: Long): FiniteDuration =
    getProtocolRules(blockHeight).targetBlockTime.get

  def numTxInBlock(blockHeight: Long): Int =
    getProtocolRules(blockHeight).numTxPerBlock.get

  def blockVersion(blockHeight: Long): Byte =
    getProtocolRules(blockHeight).blockVersion.get

  /**
   * Defines how we calculate the test value for determining eligibility to forge
   *
   * @param lastBlock previous block
   * @param box       box to be used for the test value
   * @return the test value to be compared to the adjusted difficulty
   */
  def calcHit(lastBlock: Block)(box: ArbitBox): Long = {
    val h = Blake2b256(lastBlock.bytes ++ box.bytes)

    Longs.fromByteArray((0: Byte) +: h.take(7))
  }

  /**
   * Gets the target threshold.
   * threshold = ( (address stake) * (time delta) * (difficulty) ) / ( (total stake) * (target block time) )
   * @param stakeAmount amount of stake held in address
   * @param timeDelta delta from previous block time to the current time
   * @param difficulty forging difficulty
   * @param parentHeight parent block height
   * @return the target value
   */
  def calcTarget(stakeAmount: Int128, timeDelta: Long, difficulty: Long, parentHeight: Long): Int128 =
    (stakeAmount * difficulty * timeDelta) /
    (consensusStorage.totalStake * targetBlockTime(parentHeight).toUnit(MILLISECONDS).toLong)

  /**
   * Calculate the block difficulty according to
   * [[https://nxtdocs.jelurida.com/Nxt_Whitepaper#Block_Creation_.28Forging.29]]
   *
   * @param prevDifficulty the previous base difficulty
   * @param prevTimes      sequence of block times to calculate the average and compare to target
   * @return the modified difficulty
   */
  def calcNewBaseDifficulty(newHeight: Long, prevDifficulty: Long, prevTimes: Seq[TimeProvider.Time]): Long = {

    val averageDelay = (prevTimes drop 1, prevTimes).zipped.map(_ - _).sum / (prevTimes.length - 1)
    val targetTimeMilli = targetBlockTime(newHeight).toUnit(MILLISECONDS)

    // magic numbers here (1.1, 0.9, and 0.64) are straight from NXT
    if (averageDelay > targetTimeMilli) {
      (prevDifficulty * min(averageDelay.toDouble, targetTimeMilli * 1.1) / targetTimeMilli).toLong
    } else {
      (prevDifficulty * (1 - 0.64 * (1 - (max(averageDelay.toDouble, targetTimeMilli * 0.9) / targetTimeMilli)))).toLong
    }
  }
}
