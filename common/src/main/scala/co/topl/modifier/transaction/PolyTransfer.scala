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
import scala.collection.immutable.ListMap

case class PolyTransfer[
  P <: Proposition: EvidenceProducer: Identifiable
](
  override val from:        IndexedSeq[(Address, Box.Nonce)],
  override val to:          IndexedSeq[(Address, SimpleValue)],
  override val attestation: ListMap[P, Proof[P]],
  override val fee:         Int128,
  override val timestamp:   Long,
  override val data:        Option[String] = None,
  override val minting:     Boolean
) extends TransferTransaction[SimpleValue, P](from, to, attestation, fee, timestamp, data, minting) {

  override val coinOutput: Iterable[PolyBox] =
    coinOutputParams.map { case BoxParams(evi, nonce, value) =>
      PolyBox(evi, nonce, value)
    }

  override val newBoxes: Iterable[TokenBox[SimpleValue]] = {
    // this only creates an output if the value of the output boxes is non-zero
    val recipientCoinOutput: Iterable[PolyBox] = coinOutput.filter(_.value.quantity > 0)
    val hasRecipientOutput: Boolean = recipientCoinOutput.nonEmpty
    val hasFeeChangeOutput: Boolean = feeChangeOutput.value.quantity > 0

    (hasRecipientOutput, hasFeeChangeOutput) match {
      case (false, false) => Iterable()
      case (false, true)  => Iterable(feeChangeOutput) // JAA - only possible because this is Poly TX
      case (true, false)  => recipientCoinOutput
      case (true, true)   => Iterable(feeChangeOutput) ++ recipientCoinOutput
    }
  }
}

object PolyTransfer {
  val typePrefix: TxType = 2: Byte
  val typeString: String = "PolyTransfer"

  implicit def identifier[P <: Proposition]: Identifiable[PolyTransfer[P]] = Identifiable.instance { () =>
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
    boxReader:     BoxReader[ProgramId, Address],
    toReceive:     IndexedSeq[(Address, SimpleValue)],
    sender:        IndexedSeq[Address],
    changeAddress: Address,
    fee:           Int128,
    data:          Option[String]
  ): Try[PolyTransfer[P]] =
    TransferTransaction
      .getSenderBoxesAndCheckPolyBalance(boxReader, sender, fee, "Polys") // you always get Polys back
      .map { txInputState =>
        // compute the amount of tokens that will be sent to the recipients
        val amtToSpend = toReceive.map(_._2.quantity).sum

        // create the list of inputs and outputs (senderChangeOut & recipientOut)
        val (availableToSpend, inputs, outputs) = ioTransfer(txInputState, toReceive, changeAddress, fee, amtToSpend)
        // ensure there are sufficient funds from the sender boxes to create all outputs
        require(availableToSpend >= amtToSpend, "Insufficient funds available to create transaction.")

        PolyTransfer[P](inputs, outputs, ListMap(), fee, Instant.now.toEpochMilli, data, minting = false)
      }

  /** construct input and output box sequence for a transfer transaction */
  private def ioTransfer(
    txInputState:  TransferCreationState,
    toReceive:     IndexedSeq[(Address, SimpleValue)],
    changeAddress: Address,
    fee:           Int128,
    amtToSpend:    Int128
  ): (Int128, IndexedSeq[(Address, Box.Nonce)], IndexedSeq[(Address, SimpleValue)]) = {

    val availableToSpend = txInputState.polyBalance - fee
    val inputs = txInputState.senderBoxes("Poly").map(bxs => (bxs._2, bxs._3.nonce))
    val outputs = (changeAddress, SimpleValue(txInputState.polyBalance - fee - amtToSpend)) +: toReceive
    val filterZeroChange = outputs.filter(_._2.quantity > 0)

    (availableToSpend, inputs, filterZeroChange)
  }

  implicit def jsonEncoder[P <: Proposition]: Encoder[PolyTransfer[P]] = { tx: PolyTransfer[P] =>
    Map(
      "txId"            -> tx.id.asJson,
      "txType"          -> "PolyTransfer".asJson,
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

  implicit def jsonDecoder(implicit networkPrefix: NetworkPrefix): Decoder[PolyTransfer[_ <: Proposition]] =
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
          c.downField("signatures").as[ListMap[PublicKeyPropositionCurve25519, SignatureCurve25519]].map {
            new PolyTransfer[PublicKeyPropositionCurve25519](from, to, _, fee, timestamp, data, minting)
          }

        case ThresholdPropositionCurve25519.`typeString` =>
          c.downField("signatures").as[ListMap[ThresholdPropositionCurve25519, ThresholdSignatureCurve25519]].map {
            new PolyTransfer[ThresholdPropositionCurve25519](from, to, _, fee, timestamp, data, minting)
          }

        case PublicKeyPropositionEd25519.`typeString` =>
          c.downField("signatures").as[ListMap[PublicKeyPropositionEd25519, SignatureEd25519]].map {
            new PolyTransfer[PublicKeyPropositionEd25519](from, to, _, fee, timestamp, data, minting)
          }
      }) match {
        case Right(tx) => tx
        case Left(ex)  => throw ex
      }
}
