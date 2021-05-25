package co.topl.modifier.transaction

import co.topl.attestation.{Evidence, _}
import co.topl.modifier.BoxReader
import co.topl.modifier.block.BloomFilter.BloomTopic
import co.topl.modifier.box.{Box, _}
import co.topl.utils.{Identifiable, Int128}
import com.google.common.primitives.{Ints, Longs}
import io.circe.Json
import io.circe.syntax.EncoderOps
import scorex.crypto.hash.Blake2b256

import scala.util.Try
import scala.Iterable

abstract class TransferTransaction[
  +T <: TokenValueHolder,
  P <: Proposition
](
  val from:                        IndexedSeq[(Address, Box.Nonce)],
  val to:                          IndexedSeq[(Address, T)],
  val attestation:                 Map[P, Proof[P]],
  val fee:                         Int128,
  val timestamp:                   Long,
  val data:                        Option[String],
  val minting:                     Boolean
)(implicit val evidenceProducerEv: EvidenceProducer[P], identifiableEv: Identifiable[P])
    extends Transaction[TokenValueHolder, P] {

  lazy val bloomTopics: IndexedSeq[BloomTopic] = to.map(b => BloomTopic @@ b._1.bytes)

  lazy val boxIdsToOpen: IndexedSeq[BoxId] = from.map { case (addr, nonce) =>
    BoxId.idFromEviNonce(addr.evidence, nonce)
  }

  val (feeOutputParams, coinOutputParams) = TransferTransaction.calculateBoxNonce[T](this, to)

  val feeChangeOutput: PolyBox =
    PolyBox(feeOutputParams.evidence, feeOutputParams.nonce, feeOutputParams.value)

  val coinOutput: Iterable[TokenBox[T]]

  override val newBoxes: Iterable[TokenBox[TokenValueHolder]]

  override def messageToSign: Array[Byte] =
    super.messageToSign ++
    data.fold(Array(0: Byte))(_.getBytes) :+ (if (minting) 1: Byte else 0: Byte)

}

object TransferTransaction {

  case class BoxParams[+T <: TokenValueHolder](evidence: Evidence, nonce: Box.Nonce, value: T)

  case class TransferCreationState(
    senderBoxes: Map[String, IndexedSeq[(String, Address, TokenBox[TokenValueHolder])]],
    polyBalance: Int128
  )

  def encodeFrom(from: IndexedSeq[(Address, Box.Nonce)]): Json =
    from.map(x => (x._1.asJson, x._2.toString.asJson)).asJson

  /**
   * Computes a unique nonce value based on the transaction type and
   * inputs and returns the details needed to create the output boxes for the transaction
   */
  def calculateBoxNonce[T <: TokenValueHolder](
    tx: TransferTransaction[T, _ <: Proposition],
    to: IndexedSeq[(Address, T)]
  ): (BoxParams[SimpleValue], Iterable[BoxParams[T]]) = {

    // known input data (similar to messageToSign but without newBoxes since they aren't known yet)
    val txIdPrefix = Transaction.identifier(tx).typePrefix
    val boxIdsToOpenAccumulator = tx.boxIdsToOpen.foldLeft(Array[Byte]())((acc, x) => acc ++ x.hashBytes)
    val timestampBytes = Longs.toByteArray(tx.timestamp)
    val feeBytes = tx.fee.toByteArray

    val inputBytes =
      Array(txIdPrefix) ++ boxIdsToOpenAccumulator ++ timestampBytes ++ feeBytes

    val calcNonce: Int => Box.Nonce = (index: Int) => {
      val digest = Blake2b256(inputBytes ++ Ints.toByteArray(index))
      Transaction.nonceFromDigest(digest)
    }

    val feeChangeParams = BoxParams(tx.to.head._1.evidence, calcNonce(0), SimpleValue(tx.to.head._2.quantity))

    val coinOutputParams: IndexedSeq[BoxParams[T]] =
      to.tail.zipWithIndex
        .map { case ((addr, value), idx) =>
          BoxParams(addr.evidence, calcNonce(idx + 1), value)
        }
    (feeChangeParams, coinOutputParams)
  }

  /** Retrieves the boxes from state for the specified sequence of senders and filters them based on the type of transaction */
  private def getSenderBoxesForTx(
    boxReader:   BoxReader[ProgramId, Address],
    sender:      IndexedSeq[Address],
    returnBoxes: String,
    assetCode:   Option[AssetCode] = None
  ): Map[String, IndexedSeq[(String, Address, TokenBox[TokenValueHolder])]] =
    sender
      .flatMap { s =>
        boxReader
          .getTokenBoxes(s)
          .getOrElse(
            throw new Exception("No boxes found to fund transaction")
          ) // isn't this just an empty sequence instead of None?
          .collect {
            // always get polys because this is how fees are paid
            case bx: PolyBox =>
              ("Poly", s, bx)

            case bx: ArbitBox if returnBoxes == "Arbits" =>
              ("Arbit", s, bx)

            case bx: AssetBox if returnBoxes == "Assets" && assetCode.forall(_ == bx.value.assetCode) =>
              ("Asset", s, bx)
          }
      }
      .groupBy(_._1)

  /**
   * Determines the input boxes needed to create a transfer transaction
   *
   * @param boxReader a read-only version of the nodes current state
   * @param sender the set of addresses that will contribute boxes to this transaction
   * @param fee the fee to be paid for the transaction
   * @param txType the type of transfer
   * @param assetCode an asset specific detail for finding the right asset boxes to be sent in a transfer
   * @return the input box information and output data needed to create the transaction case class
   */
  def getSenderBoxesAndCheckPolyBalance(
    boxReader: BoxReader[ProgramId, Address],
    sender:    IndexedSeq[Address],
    fee:       Int128,
    txType:    String,
    assetCode: Option[AssetCode] = None // (assetCode)
  ): Try[TransferCreationState] = Try {

    // Lookup boxes for the given senders
    val senderBoxes: Map[String, IndexedSeq[(String, Address, TokenBox[TokenValueHolder])]] =
      getSenderBoxesForTx(boxReader, sender, txType, assetCode)

    // compute the Poly balance since it is used often
    val polyBalance =
      senderBoxes
        .getOrElse("Poly", throw new Exception(s"No Poly funds available for the transaction fee payment"))
        .map(_._3.value.quantity)
        .sum

    // ensure there are enough polys to pay the fee
    require(polyBalance >= fee, s"Insufficient funds available to pay transaction fee.")

    TransferCreationState(senderBoxes, polyBalance)
  }
}
