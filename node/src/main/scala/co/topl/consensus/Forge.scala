package co.topl.consensus

import cats.data.Validated
import cats.implicits._
import co.topl.attestation.{Address, PublicKeyPropositionCurve25519, SignatureCurve25519}
import co.topl.consensus.KeyManager.KeyView
import co.topl.modifier.block.Block
import co.topl.modifier.box.{ArbitBox, ProgramId}
import co.topl.modifier.transaction.{ArbitTransfer, PolyTransfer, Transaction}
import co.topl.nodeView.ReadableNodeView
import co.topl.nodeView.mempool.MemPoolReader
import co.topl.nodeView.state.StateReader
import co.topl.utils.NetworkType.NetworkPrefix
import co.topl.utils.{Int128, TimeProvider}
import org.slf4j.Logger
import co.topl.utils.IdiomaticScalaTransition.implicits.toEitherOps

import scala.util.Try

/**
 * A Forge contains all of the data necessary to create a new block
 *
 * @param box an eligible arbit box
 * @param parent the parent block
 * @param previousBlockTimes the previous block times to determine next difficulty
 * @param rawRewards the raw forging rewards
 * @param transactionsToInclude the set of transactions to be entered into the block
 * @param forgeTime the current timestamp
 * @param sign a function for signing messages
 * @param getPublicKey a function for getting the public key associated with an address
 */
case class Forge(
  box:                   ArbitBox,
  parent:                Block,
  previousBlockTimes:    Vector[TimeProvider.Time],
  rawRewards:            Seq[Transaction.TX],
  transactionsToInclude: Seq[Transaction.TX],
  forgeTime:             TimeProvider.Time,
  sign:                  Address => Array[Byte] => Try[SignatureCurve25519],
  getPublicKey:          Address => Try[PublicKeyPropositionCurve25519]
) {

  def make(implicit networkPrefix: NetworkPrefix): Either[Forge.Failure, Block] = {

    // generate the address the owns the generator box
    val matchingAddr = Address(box.evidence)

    for {
      // lookup the public key associated with the box,
      // (this is separate from the signing function so that the private key never leaves the KeyRing)
      publicKey <- getPublicKey(matchingAddr).toEither.leftMap(_ => Forge.ArbitBoxKeyNotFound)

      // use the private key that owns the generator box to create a function that will sign the new block
      signingFunction = sign(matchingAddr)

      // use the secret key that owns the successful box to sign the rewards transactions
      getAttMap = (tx: Transaction.TX) =>
        signingFunction(tx.messageToSign).map(signature => Map(publicKey -> signature)).getOrThrow()

      signedRewards = rawRewards.map {
        case tx: ArbitTransfer[_] => tx.copy(attestation = getAttMap(tx))
        case tx: PolyTransfer[_]  => tx.copy(attestation = getAttMap(tx))
      }

      // calculate the newly forged blocks updated difficulty
      newDifficulty = calcNewBaseDifficulty(
        parent.height + 1,
        parent.difficulty,
        previousBlockTimes :+ forgeTime
      )
      block <- Block
        .createAndSign(
          parent.id,
          forgeTime,
          signedRewards ++ transactionsToInclude,
          box,
          publicKey,
          parent.height + 1,
          newDifficulty,
          blockVersion(parent.height + 1)
        )(signingFunction)
        .toEither
        .leftMap(Forge.ForgingError)
    } yield block
  }
}

object Forge {

  def fromNodeView(nodeView: ReadableNodeView, keyView: KeyView, minTransactionFee: Int128)(implicit
    timeProvider:            TimeProvider,
    networkPrefix:           NetworkPrefix,
    logger:                  Logger
  ): Either[Failure, Forge] =
    for {
      rewardAddress <- keyView.rewardAddr.toRight(NoRewardsAddressSpecified)
      transactions <- pickTransactions(
        minTransactionFee,
        nodeView.memPool,
        nodeView.state,
        nodeView.history.height
      ).map(_.toApply)
      parentBlock = nodeView.history.bestBlock
      forgeTime = timeProvider.time
      rewards <- Rewards(transactions, rewardAddress, parentBlock.id, forgeTime).toEither.leftMap(ForgingError)
      prevTimes = nodeView.history.getTimestampsFrom(parentBlock, nxtBlockNum)
      arbitBox <- LeaderElection
        .getEligibleBox(parentBlock, keyView.addresses, forgeTime, nodeView.state)
        .leftMap(LeaderElectionFailure)
    } yield Forge(
      arbitBox,
      parentBlock,
      prevTimes,
      rewards,
      transactions,
      forgeTime,
      keyView.sign,
      keyView.getPublicKey
    )

  /**
   * Pick a set of transactions from the mempool that result in a valid state when applied to the current state
   *
   * @param memPoolReader the set of pending transactions
   * @param stateReader state to use for semantic validity checking
   * @return a sequence of valid transactions
   */
  private[consensus] def pickTransactions(
    minTransactionFee:      Int128,
    memPoolReader:          MemPoolReader[Transaction.TX],
    stateReader:            StateReader[ProgramId, Address],
    chainHeight:            Long
  )(implicit networkPrefix: NetworkPrefix, log: Logger): Either[Failure, PickTransactionsResult] =
    Try(
      memPoolReader
        .take[Int128](numTxInBlock(chainHeight))(-_.tx.fee) // returns a sequence of transactions ordered by their fee
        .filter(
          _.tx.fee >= minTransactionFee
        ) // default strategy ignores zero fee transactions in mempool
        .foldLeft(PickTransactionsResult(Seq(), Seq())) { case (txAcc, utx) =>
          // ensure that each transaction opens a unique box by checking that this transaction
          // doesn't open a box already being opened by a previously included transaction
          val boxAlreadyUsed = utx.tx.boxIdsToOpen.exists(id => txAcc.toApply.flatMap(_.boxIdsToOpen).contains(id))

          // if any newly created box matches a box already in the UTXO set in state, remove the transaction
          val boxAlreadyExists = utx.tx.newBoxes.exists(b => stateReader.getBox(b.id).isDefined)

          (boxAlreadyUsed, boxAlreadyExists) match {
            case (false, false) =>
              import co.topl.modifier.transaction.validation.implicits._
              utx.tx.semanticValidation(stateReader) match {
                case Validated.Valid(_) => PickTransactionsResult(txAcc.toApply :+ utx.tx, txAcc.toEliminate)
                case Validated.Invalid(ex) =>
                  log.debug(
                    s"${Console.RED}Transaction ${utx.tx.id} failed semantic validation. " +
                    s"Transaction will be removed.${Console.RESET} Failure: $ex"
                  )
                  PickTransactionsResult(txAcc.toApply, txAcc.toEliminate :+ utx.tx)
              }

            case (_, true) =>
              log.debug(
                s"${Console.RED}Transaction ${utx.tx.id} was rejected from the forger transaction queue" +
                s" because a newly created box already exists in state. The transaction will be removed."
              )
              PickTransactionsResult(txAcc.toApply, txAcc.toEliminate :+ utx.tx)

            case (true, _) =>
              log.debug(
                s"${Console.RED}Transaction ${utx.tx.id} was rejected from forger transaction queue" +
                s" because a box was used already in a previous transaction. The transaction will be removed."
              )
              PickTransactionsResult(txAcc.toApply, txAcc.toEliminate :+ utx.tx)
          }
        }
    ).toEither.leftMap(ForgingError)

  private[consensus] case class PickTransactionsResult(toApply: Seq[Transaction.TX], toEliminate: Seq[Transaction.TX])

  sealed abstract class Failure
  case class LeaderElectionFailure(reason: LeaderElection.IneligibilityReason) extends Failure
  case class ForgingError(error: Throwable) extends Failure
  case object NoRewardsAddressSpecified extends Failure
  case object ArbitBoxKeyNotFound extends Failure

}
