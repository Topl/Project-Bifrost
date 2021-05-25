package co.topl.modifier.transaction

import co.topl.attestation._
import co.topl.modifier.BoxReader
import co.topl.modifier.box._
import co.topl.modifier.transaction.Transaction.TxType
import co.topl.modifier.transaction.TransferTransaction.{encodeFrom, BoxParams, TransferCreationState}
import co.topl.utils.NetworkType.NetworkPrefix
import co.topl.utils.codecs.Int128Codec
import co.topl.utils.{Identifiable, Identifier, Int128}
import io.circe.syntax.EncoderOps
import io.circe.{Decoder, Encoder, HCursor}

import java.time.Instant
import scala.util.Try
import scala.Iterable

case class ArbitTransfer[
  P <: Proposition: EvidenceProducer: Identifiable
](
  override val from:        IndexedSeq[(Address, Box.Nonce)],
  override val to:          IndexedSeq[(Address, SimpleValue)],
  override val attestation: Map[P, Proof[P]],
  override val fee:         Int128,
  override val timestamp:   Long,
  override val data:        Option[String] = None,
  override val minting:     Boolean
) extends TransferTransaction[SimpleValue, P](from, to, attestation, fee, timestamp, data, minting) {

  override val coinOutput: Iterable[ArbitBox] =
    coinOutputParams.map { case BoxParams(evi, nonce, value) =>
      ArbitBox(evi, nonce, value)
    }

  override val newBoxes: Iterable[TokenBox[SimpleValue]] = {
    // this only creates an output if the value of the output boxes is non-zero
    val recipientCoinOutput: Iterable[ArbitBox] = coinOutput.filter(_.value.quantity > 0)
    val hasRecipientOutput: Boolean = recipientCoinOutput.nonEmpty
    val hasFeeChangeOutput: Boolean = feeChangeOutput.value.quantity > 0

    (hasRecipientOutput, hasFeeChangeOutput) match {
      case (false, _)    => Iterable()
      case (true, false) => recipientCoinOutput
      case (true, true)  => Iterable(feeChangeOutput) ++ recipientCoinOutput
    }
  }
}

object ArbitTransfer {
  val typePrefix: TxType = 1: Byte
  val typeString: String = "ArbitTransfer"

  implicit def identifier[P <: Proposition]: Identifiable[ArbitTransfer[P]] = Identifiable.instance { () =>
    Identifier(typeString, typePrefix)
  }

  /**
   * @param boxReader
   * @param toReceive
   * @param sender
   * @param fee
   * @param data
   * @return
   */
  def createRaw[
    P <: Proposition: EvidenceProducer: Identifiable
  ](
    boxReader:            BoxReader[ProgramId, Address],
    toReceive:            IndexedSeq[(Address, SimpleValue)],
    sender:               IndexedSeq[Address],
    changeAddress:        Address,
    consolidationAddress: Address,
    fee:                  Int128,
    data:                 Option[String]
  ): Try[ArbitTransfer[P]] =
    TransferTransaction
      .getSenderBoxesAndCheckPolyBalance(boxReader, sender, fee, "Arbits")
      .map { txState =>
        // compute the amount of tokens that will be sent to the recipients
        val amtToSpend = toReceive.map(_._2.quantity).sum

        // create the list of inputs and outputs (senderChangeOut & recipientOut)
        val (availableToSpend, inputs, outputs) =
          ioTransfer(txState, toReceive, changeAddress, consolidationAddress, fee, amtToSpend)

        // ensure there are sufficient funds from the sender boxes to create all outputs
        require(availableToSpend >= amtToSpend, "Insufficient funds available to create transaction.")

        ArbitTransfer[P](inputs, outputs, Map(), fee, Instant.now.toEpochMilli, data, minting = false)
      }

  /** construct input and output box sequence for a transfer transaction */
  private def ioTransfer(
    txInputState:         TransferCreationState,
    toReceive:            IndexedSeq[(Address, SimpleValue)],
    changeAddress:        Address,
    consolidationAddress: Address,
    fee:                  Int128,
    amtToSpend:           Int128
  ): (Int128, IndexedSeq[(Address, Box.Nonce)], IndexedSeq[(Address, SimpleValue)]) = {

    val availableToSpend =
      txInputState.senderBoxes
        .getOrElse("Arbit", throw new Exception(s"No Arbit funds available for the transaction"))
        .map(_._3.value.quantity)
        .sum

    val inputs =
      txInputState.senderBoxes("Arbit").map(bxs => (bxs._2, bxs._3.nonce)) ++
      txInputState.senderBoxes("Poly").map(bxs => (bxs._2, bxs._3.nonce))

    val outputs = IndexedSeq(
      (changeAddress, SimpleValue(txInputState.polyBalance - fee)),
      (consolidationAddress, SimpleValue(availableToSpend - amtToSpend))
    ) ++ toReceive

    (availableToSpend, inputs, outputs)
  }

  implicit def jsonEncoder[P <: Proposition]: Encoder[ArbitTransfer[P]] = { tx: ArbitTransfer[P] =>
    Map(
      "txId"            -> tx.id.asJson,
      "txType"          -> "ArbitTransfer".asJson,
      "propositionType" -> tx.getPropIdentifier.typeString.asJson,
      "newBoxes"        -> tx.newBoxes.toSeq.asJson,
      "boxesToRemove"   -> tx.boxIdsToOpen.asJson,
      "from"            -> encodeFrom(tx.from),
      "to"              -> tx.to.asJson,
      "signatures"      -> tx.attestation.asJson,
      "fee"             -> tx.fee.asJson(Int128Codec.jsonEncoder),
      "timestamp"       -> tx.timestamp.asJson,
      "minting"         -> tx.minting.asJson,
      "data"            -> tx.data.asJson
    ).asJson
  }

  implicit def jsonDecoder(implicit networkPrefix: NetworkPrefix): Decoder[ArbitTransfer[_ <: Proposition]] =
    (c: HCursor) =>
      for {
        from      <- c.downField("from").as[IndexedSeq[(Address, Box.Nonce)]]
        to        <- c.downField("to").as[IndexedSeq[(Address, SimpleValue)]]
        fee       <- c.get[Int128]("fee")(Int128Codec.jsonDecoder)
        timestamp <- c.downField("timestamp").as[Long]
        data      <- c.downField("data").as[Option[String]]
        propType  <- c.downField("propositionType").as[String]
        minting   <- c.downField("minting").as[Boolean]
      } yield (propType match {
        case PublicKeyPropositionCurve25519.`typeString` =>
          c.downField("signatures").as[Map[PublicKeyPropositionCurve25519, SignatureCurve25519]].map {
            new ArbitTransfer[PublicKeyPropositionCurve25519](from, to, _, fee, timestamp, data, minting)
          }

        case ThresholdPropositionCurve25519.`typeString` =>
          c.downField("signatures").as[Map[ThresholdPropositionCurve25519, ThresholdSignatureCurve25519]].map {
            new ArbitTransfer[ThresholdPropositionCurve25519](from, to, _, fee, timestamp, data, minting)
          }
      }) match {
        case Right(tx) => tx
        case Left(ex)  => throw ex
      }
}
