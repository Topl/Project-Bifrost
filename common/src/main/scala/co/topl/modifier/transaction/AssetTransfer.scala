package co.topl.modifier.transaction

import co.topl.attestation._
import co.topl.modifier.BoxReader
import co.topl.modifier.box._
import co.topl.modifier.transaction.Transaction.TxType
import co.topl.modifier.transaction.TransferTransaction.{encodeFrom, BoxParams, TransferCreationState}
import co.topl.utils.NetworkType.NetworkPrefix
import co.topl.utils.codecs.Int128Codec
import co.topl.utils.{Identifiable, Identifier, Int128}
import io.circe.syntax._
import io.circe.{Decoder, Encoder, HCursor}

import java.time.Instant
import scala.util.Try
import scala.Iterable
import scala.collection.immutable.ListMap

case class AssetTransfer[
  P <: Proposition: EvidenceProducer: Identifiable
](
  override val from:        IndexedSeq[(Address, Box.Nonce)],
  override val to:          IndexedSeq[(Address, TokenValueHolder)],
  override val attestation: ListMap[P, Proof[P]],
  override val fee:         Int128,
  override val timestamp:   Long,
  override val data:        Option[String] = None,
  override val minting:     Boolean = false
) extends TransferTransaction[TokenValueHolder, P](from, to, attestation, fee, timestamp, data, minting) {

  override val coinOutput: Iterable[AssetBox] =
    coinOutputParams.map { case BoxParams(evi, nonce, value: AssetValue) =>
      AssetBox(evi, nonce, value)
    }

  override val newBoxes: Iterable[TokenBox[TokenValueHolder]] = {
    // this only creates an output if the value of the output boxes is non-zero
    val recipientCoinOutput: Iterable[AssetBox] = coinOutput.filter(_.value.quantity > 0)
    val hasRecipientOutput: Boolean = recipientCoinOutput.nonEmpty
    val hasFeeChangeOutput: Boolean = feeChangeOutput.value.quantity > 0

    (hasRecipientOutput, hasFeeChangeOutput) match {
      case (false, _)    => Iterable()
      case (true, false) => recipientCoinOutput
      case (true, true)  => Iterable(feeChangeOutput) ++ recipientCoinOutput
    }
  }
}

object AssetTransfer {
  val typePrefix: TxType = 3: Byte
  val typeString: String = "AssetTransfer"

  implicit def identifier[P <: Proposition]: Identifiable[AssetTransfer[P]] = Identifiable.instance { () =>
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
    P <: Proposition
  ](
    boxReader:                   BoxReader[ProgramId, Address],
    toReceive:                   IndexedSeq[(Address, AssetValue)],
    sender:                      IndexedSeq[Address],
    changeAddress:               Address,
    consolidationAddress:        Address,
    fee:                         Int128,
    data:                        Option[String],
    minting:                     Boolean
  )(implicit evidenceProducerEv: EvidenceProducer[P], identifiableEv: Identifiable[P]): Try[AssetTransfer[P]] = {

    val assetCode =
      toReceive
        .map(_._2.assetCode)
        .toSet
        .ensuring(_.size == 1, s"Found multiple asset codes when only one was expected")
        .head

    TransferTransaction
      .getSenderBoxesAndCheckPolyBalance(boxReader, sender, fee, "Assets", Some(assetCode))
      .map { txState =>
        // compute the amount of tokens that will be sent to the recipients
        val amtToSpend = toReceive.map(_._2.quantity).sum

        // create the list of inputs and outputs (senderChangeOut & recipientOut)
        val (availableToSpend, inputs, outputs) =
          if (minting) ioMint(txState, toReceive, changeAddress, fee)
          else ioTransfer(txState, toReceive, changeAddress, consolidationAddress, fee, amtToSpend, assetCode)

        // ensure there are sufficient funds from the sender boxes to create all outputs
        require(availableToSpend >= amtToSpend, "Insufficient funds available to create transaction.")

        AssetTransfer[P](inputs, outputs, ListMap(), fee, Instant.now.toEpochMilli, data, minting)
      }
  }

  /** Construct input and output box sequences for a minting transaction */
  private def ioMint(
    txInputState:  TransferCreationState,
    toReceive:     IndexedSeq[(Address, AssetValue)],
    changeAddress: Address,
    fee:           Int128
  ): (Int128, IndexedSeq[(Address, Box.Nonce)], IndexedSeq[(Address, TokenValueHolder)]) = {
    val availableToSpend = Int128.MaxValue // you cannot mint more than the max number we can represent
    val inputs = txInputState.senderBoxes("Poly").map(bxs => (bxs._2, bxs._3.nonce))
    val outputs = (changeAddress, SimpleValue(txInputState.polyBalance - fee)) +: toReceive

    (availableToSpend, inputs, outputs)
  }

  /** construct input and output box sequence for a transfer transaction */
  private def ioTransfer(
    txInputState:         TransferCreationState,
    toReceive:            IndexedSeq[(Address, AssetValue)],
    changeAddress:        Address,
    consolidationAddress: Address,
    fee:                  Int128,
    amtToSpend:           Int128,
    assetCode:            AssetCode
  ): (Int128, IndexedSeq[(Address, Box.Nonce)], IndexedSeq[(Address, TokenValueHolder)]) = {

    val availableToSpend =
      txInputState.senderBoxes
        .getOrElse("Asset", throw new Exception(s"No Assets found with assetCode $assetCode"))
        .map(_._3.value.quantity)
        .sum

    // create the list of inputs and outputs (senderChangeOut & recipientOut)
    val inputs = txInputState.senderBoxes("Asset").map(bxs => (bxs._2, bxs._3.nonce)) ++
      txInputState.senderBoxes("Poly").map(bxs => (bxs._2, bxs._3.nonce))

    val outputs = IndexedSeq(
      (changeAddress, SimpleValue(txInputState.polyBalance - fee)),
      (consolidationAddress, AssetValue(availableToSpend - amtToSpend, assetCode))
    ) ++ toReceive

    (availableToSpend, inputs, outputs)
  }

  implicit def jsonEncoder[P <: Proposition]: Encoder[AssetTransfer[P]] = { tx: AssetTransfer[P] =>
    Map(
      "txId"            -> tx.id.asJson,
      "txType"          -> "AssetTransfer".asJson,
      "propositionType" -> tx.getPropIdentifier.typeString.asJson,
      "newBoxes"        -> tx.newBoxes.toSeq.asJson,
      "boxesToRemove"   -> tx.boxIdsToOpen.asJson,
      "from"            -> encodeFrom(tx.from),
      "to"              -> tx.to.asJson,
      "signatures"      -> tx.attestation.asJson,
      "fee"             -> tx.fee.asJson(Int128Codec.jsonEncoder),
      "timestamp"       -> tx.timestamp.asJson,
      "data"            -> tx.data.asJson,
      "minting"         -> tx.minting.asJson
    ).asJson
  }

  implicit def jsonDecoder(implicit networkPrefix: NetworkPrefix): Decoder[AssetTransfer[_ <: Proposition]] =
    (c: HCursor) =>
      for {
        from      <- c.downField("from").as[IndexedSeq[(Address, Box.Nonce)]]
        to        <- c.downField("to").as[IndexedSeq[(Address, TokenValueHolder)]]
        fee       <- c.get[Int128]("fee")(Int128Codec.jsonDecoder)
        timestamp <- c.downField("timestamp").as[Long]
        data      <- c.downField("data").as[Option[String]]
        minting   <- c.downField("minting").as[Boolean]
        propType  <- c.downField("propositionType").as[String]
      } yield (propType match {
        case PublicKeyPropositionCurve25519.`typeString` =>
          c.downField("signatures").as[ListMap[PublicKeyPropositionCurve25519, SignatureCurve25519]].map {
            new AssetTransfer[PublicKeyPropositionCurve25519](from, to, _, fee, timestamp, data, minting)
          }

        case ThresholdPropositionCurve25519.`typeString` =>
          c.downField("signatures").as[ListMap[ThresholdPropositionCurve25519, ThresholdSignatureCurve25519]].map {
            new AssetTransfer[ThresholdPropositionCurve25519](from, to, _, fee, timestamp, data, minting)
          }

        case PublicKeyPropositionEd25519.`typeString` =>
          c.downField("signatures").as[ListMap[PublicKeyPropositionEd25519, SignatureEd25519]].map {
            new AssetTransfer[PublicKeyPropositionEd25519](from, to, _, fee, timestamp, data, minting)
          }
      }) match {
        case Right(tx) => tx
        case Left(ex)  => throw ex
      }
}
