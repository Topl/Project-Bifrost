package modifier

import attestation.AddressEncoder.NetworkPrefix
import attestation.{Evidence, _}
import co.topl.crypto.hash.blake2b256
import com.google.common.primitives.{Ints, Longs}
import crypto.AssetCode
import io.circe.syntax.EncoderOps
import io.circe.{Decoder, Encoder, HCursor, Json}
import modifier.ModifierId.ModifierTypeId
import modifier.TransferTransaction.BoxParams
import utils.{Identifiable, Identifier}

import scala.collection.mutable.{Map => MMap}
import scala.util.Try
import scala.Iterable

/**
 * A transfer transaction which can be a: poly transaction, arbit transaction or asset transaction
 * @param from - list of senders' addresses and box nonce's used for the transaction
 * @param to - list of address and token values to send to
 * @param attestation - mapping of proposition to signature
 * @param fee - transaction fee
 * @param timestamp - the current time (as the number of milliseconds from the epoch)
 * @param data - data for the transaction
 * @param minting - if the transaction is a minting transaction, minting = true (for asset transfers only)
 * @param txType - type of transaction: "PolyTransfer", "AssetTransfer", or "ArbitTransfer"
 * @tparam P - Proposition type (PublicKeyProposition or ThresholdProposition)
 */
case class TransferTransaction[P <: Proposition: EvidenceProducer: Identifiable](
  from:        IndexedSeq[(Address, Long)],
  to:          IndexedSeq[(Address, TokenValueHolder)],
  attestation: Map[P, Proof[P]],
  fee:         Long,
  timestamp:   Long,
  data:        Option[String],
  minting:     Boolean,
  txType:      String
) {

  lazy val id: ModifierId = ModifierId(this)

  /**
   * New boxes created from this transaction
   */
  val newBoxes: Iterable[Box] = {
    val params = TransferTransaction.boxParams(this)

    val feeChangeBox =
      if (fee > 0L) Iterable(Box(params._1.evidence, params._1.nonce, "PolyBox", params._1.value))
      else Iterable()

    val boxes = params._2.map {
      case BoxParams(ev, n, v) =>
        txType match {
          case "PolyTransfer"  => Box(ev, n, "PolyBox", v)
          case "ArbitTransfer" => Box(ev, n, "ArbitBox", v)
          case "AssetTransfer" => Box(ev, n, "AssetBox", v)
        }
      case _ => throw new Error("Attempted application of invalid value holder")
    }

    feeChangeBox ++ boxes
  }

  lazy val boxIdsToOpen: IndexedSeq[BoxId] = from.map { case (addr, nonce) =>
    BoxId.idFromEviNonce(addr.evidence, nonce)
  }

  def getPropIdentifier: Identifier = Identifiable[P].getId

  def messageToSign: Array[Byte] =
    Array(TransferTransaction.identifier(this).typePrefix) ++
    newBoxes.foldLeft(Array[Byte]())((acc, x) => acc ++ x.bytes) ++
    boxIdsToOpen.foldLeft(Array[Byte]())((acc, x) => acc ++ x.hashBytes) ++
    Longs.toByteArray(timestamp) ++
    Longs.toByteArray(fee) ++
    data.fold(Array(0: Byte))(_.getBytes) :+ (if (minting) 1: Byte else 0: Byte)

  def rawValidate(implicit networkPrefix: NetworkPrefix): Try[Unit] =
    TransferTransaction.syntacticValidate(this)

}

object TransferTransaction {

  /**
   * Details needed to create a box
   * @param evidence - the evidence for a particular box
   * @param nonce - nonce value for particular box
   * @param value - token value holder for particular box
   * @tparam T - type of TokenValueHolder
   */
  case class BoxParams[T <: TokenValueHolder](evidence: Evidence, nonce: Long, value: T)

  val modifierTypeId: ModifierTypeId = ModifierTypeId(2: Byte)

  def identifier[P <: Proposition](tx: TransferTransaction[P]): Identifier =
    Identifiable.instance(() => Identifier(tx.txType, typePrefix(tx))).getId

  def typePrefix[P <: Proposition](tx: TransferTransaction[P]): Byte = tx.txType match {
    case "ArbitTransfer" => 1: Byte
    case "PolyTransfer"  => 2: Byte
    case "AssetTransfer" => 3: Byte
  }

  /**
   * Computes a unique nonce value based on the transaction type and
   * inputs and returns the details needed to create the output boxes for the transaction
   * @param tx the transaction to calculate fee change and output boxes for
   * @tparam T type of token value (SimpleValue or AssetValue)
   * @tparam P proposition type (PublicKeyProposition or ThresholdProposition)
   * @return a tuple of the fee change box and list of output boxes
   */
  def boxParams[
    T <: TokenValueHolder,
    P <: Proposition
  ](tx: TransferTransaction[P]): (BoxParams[SimpleValue], Iterable[BoxParams[TokenValueHolder]]) = {
    // known input data (similar to messageToSign but without newBoxes since they aren't known yet)

    val inputBytes =
      Array(typePrefix(tx)) ++
      tx.boxIdsToOpen.foldLeft(Array[Byte]())((acc, x) => acc ++ x.hashBytes) ++
      Longs.toByteArray(tx.timestamp) ++
      Longs.toByteArray(tx.fee)

    def calcNonce(index: Int): Long = {
      val digest = blake2b256.hash(inputBytes ++ Ints.toByteArray(index))
      Longs.fromByteArray(digest.value.take(Longs.BYTES))
    }

    val feeChangeParams = BoxParams(tx.to.head._1.evidence, calcNonce(0), SimpleValue(tx.to.head._2.quantity))

    val outputParams = tx.to.tail
      .filter(_._2.quantity > 0L)
      .zipWithIndex
      .map { case ((addr, value), idx) =>
        BoxParams(addr.evidence, calcNonce(idx + 1), value)
      }

    (feeChangeParams, outputParams)
  }

  /**
   * Retrieves the boxes from state for the specified sequence of senders and
   * filters them based on the type of transaction
   * @param walletBoxes the boxes of the current keys in the keymanager
   * @param sender list of senders
   * @param txType the type of transaction: "PolyTransfer", "ArbitTransfer", or "AssetTransfer"
   * @param assetArgs details for an asset (assetCode, minting)
   * @return the senders' boxes
   *         specifically a map of the box type ("Poly", "Arbit", or "Asset") to a list of the boxes of that type
   */
  private def getSenderBoxesForTx(
    walletBoxes: MMap[Address, MMap[BoxId, Box]],
    sender:      IndexedSeq[Address],
    txType:      String,
    assetArgs:   Option[(AssetCode, Boolean)] = None
  ): Map[String, IndexedSeq[(String, Address, Box)]] =
    sender
      .flatMap { s =>
        val bxs =
          walletBoxes.getOrElse(s, throw new Exception("No boxes found to fund transaction")).values.toIndexedSeq

        bxs.collect {
          case box if box.typeOfBox == "PolyBox" => ("Poly", s, box)

          case box if box.typeOfBox == "ArbitBox" && txType == "ArbitTransfer" => ("Arbit", s, box)

          case box if box.typeOfBox == "AssetBox" && txType == "AssetTransfer" =>
            box.value match {
              case token: AssetValue if assetArgs.forall(_._1 == token.assetCode) =>
                ("Asset", s, box)
              case _ => null
            }
        }
      }
      .groupBy(_._1)

  /**
   * Calculates amount available to send and creates the list of inputs and outputs (senderChangeOut & recipientOut)
   * @param txType type of tx: "PolyTransfer", "ArbitTransfer", or "AssetTransfer"
   * @param polyBalance the amount of poly the sender has available
   * @param fee transaction fee
   * @param senderBoxes boxes the sender has to fund the transaction
   * @param changeAddress the address to send leftover poly's to
   * @param toReceive a list of recipients and amounts to send
   * @param consolidationAddress address to send leftover over arbits/assets to (only applicable for Asset/Arbit transfer)
   * @param assetArgs a tuple of asset specific details (assetCode, minting) for finding the right asset boxes to be sent in a transfer
   * @tparam T type of TokenValueHolder (SimpleValue or AssetValue)
   * @return amount available to spend, list of sender inputs, and list of recipient outputs
   */
  def getInputsOutputs[
    T <: TokenValueHolder
  ](
    txType:               String,
    polyBalance:          Long,
    fee:                  Long,
    senderBoxes:          Map[String, IndexedSeq[(String, Address, Box)]],
    changeAddress:        Address,
    toReceive:            IndexedSeq[(Address, T)],
    consolidationAddress: Option[Address],
    assetArgs:            Option[(AssetCode, Boolean)] = None // (assetCode, minting)
  ): (Long, IndexedSeq[(Address, Long)], IndexedSeq[(Address, TokenValueHolder)]) = {

    // compute the amount of tokens that will be sent to the recipients
    val amtToSpend = toReceive.map(_._2.quantity).sum

    txType match {
      case "PolyTransfer" =>
        (
          polyBalance - fee,
          senderBoxes("Poly").map(bxs => (bxs._2, bxs._3.nonce)),
          (changeAddress, SimpleValue(polyBalance - fee - amtToSpend)) +: toReceive
        )

      case "ArbitTransfer" =>
        val arbitBalance =
          senderBoxes
            .getOrElse("Arbit", throw new Exception(s"No Arbit funds available for the transaction"))
            .map(_._3.value.quantity)
            .sum

        (
          arbitBalance,
          senderBoxes("Arbit").map(bxs => (bxs._2, bxs._3.nonce)) ++
          senderBoxes("Poly").map(bxs => (bxs._2, bxs._3.nonce)),
          IndexedSeq(
            (changeAddress, SimpleValue(polyBalance - fee)),
            (consolidationAddress.getOrElse(changeAddress), SimpleValue(arbitBalance - amtToSpend))
          ) ++
          toReceive
        )

      // case for minting asset transfers
      // todo - JAA - what happens here when I specify a zero fee and use the same timestamp?
      // need to check that unique outputs are generated but I am not sure they will be because the tx
      // bytes will be the same so the nonce will end up being the same?
      case "AssetTransfer" if assetArgs.forall(_._2) =>
        (
          Long.MaxValue,
          senderBoxes("Poly").map(bxs => (bxs._2, bxs._3.nonce)),
          (changeAddress, SimpleValue(polyBalance - fee)) +: toReceive
        )

      // todo: JAA - we need to handle the case where the change output is zero. I think this
      // means we should move these functions to their singleton objects and define the handling there
      case "AssetTransfer" =>
        val assetBalance =
          senderBoxes
            .getOrElse("Asset", throw new Exception(s"No Assets found with assetCode ${assetArgs.get._1}"))
            .map(_._3.value.quantity)
            .sum

        (
          assetBalance,
          senderBoxes("Asset").map(bxs => (bxs._2, bxs._3.nonce)) ++
          senderBoxes("Poly").map(bxs => (bxs._2, bxs._3.nonce)),
          IndexedSeq(
            (changeAddress, SimpleValue(polyBalance - fee)),
            (
              consolidationAddress.getOrElse(changeAddress),
              modifier.AssetValue(assetBalance - amtToSpend, assetArgs.get._1)
            )
          ) ++
          toReceive
        )
    }
  }

  /**
   * Determines the input boxes needed to create a transfer transaction
   * @param walletBoxes the wallet boxes for the keys currently in the KeyManager
   * @param toReceive the recipients of boxes
   * @param sender the set of addresses that will contribute boxes to this transaction
   * @param changeAddress the address to send leftover poly's to
   * @param consolidationAddress address to send leftover over arbits/assets to
   * @param fee the fee to be paid for the transaction
   * @param txType the type of transfer
   * @param assetArgs a tuple of asset specific details for finding the right asset boxes to be sent in a transfer
   * @tparam T type of TokenValueHolder (SimpleValue or AssetValue)
   * @return the input box information and output data needed to create the transaction case class
   */
  def createRawTransferParams[
    T <: TokenValueHolder
  ](
    walletBoxes:          MMap[Address, MMap[BoxId, Box]],
    toReceive:            IndexedSeq[(Address, T)],
    sender:               IndexedSeq[Address],
    changeAddress:        Address,
    consolidationAddress: Option[Address],
    fee:                  Long,
    txType:               String,
    assetArgs:            Option[(AssetCode, Boolean)] = None // (assetCode, minting)
  ): Try[(IndexedSeq[(Address, Long)], IndexedSeq[(Address, TokenValueHolder)])] = Try {

    // Lookup boxes for the given senders
    val senderBoxes = getSenderBoxesForTx(walletBoxes, sender, txType, assetArgs)

    // compute the Poly balance since it is used often
    val polyBalance = senderBoxes
      .getOrElse("Poly", throw new Exception(s"No Poly funds available for the transaction fee payment"))
      .map(_._3.value.quantity)
      .sum
    // ensure there are enough polys to pay the fee
    require(polyBalance >= fee, s"Insufficient funds available to pay transaction fee.")

    // compute the amount of tokens that will be sent to the recipients
    val amtToSpend = toReceive.map(_._2.quantity).sum

    // create the list of inputs and outputs (senderChangeOut & recipientOut)
    val (availableToSpend, inputs, outputs) =
      getInputsOutputs(txType, polyBalance, fee, senderBoxes, changeAddress, toReceive, consolidationAddress, assetArgs)

    // ensure there are sufficient funds from the sender boxes to create all outputs
    require(availableToSpend >= amtToSpend, "Insufficient funds available to create transaction.")

    (inputs, outputs)
  }

  /**
   * Syntactic validation of a transfer transaction
   *
   * @param tx an instance of a transaction to check
   * @param networkPrefix network prefix for the current network
   * @tparam P Proposition type (PublicKeyProposition or ThresholdProposition)
   * @return success or failure indicating the validity of the transaction
   */
  def syntacticValidate[P <: Proposition: EvidenceProducer](
    tx:                     TransferTransaction[P]
  )(implicit networkPrefix: NetworkPrefix): Try[Unit] = Try {
    //enforce transaction specific requirements
    tx.txType match {
      case "ArbitTransfer" if tx.minting => // Arbit block rewards
      case "PolyTransfer" if tx.minting  => // Poly block rewards
      case _                             =>
        // must provide input state to consume in order to generate new state
        if (tx.minting) require(tx.fee > 0L, "Asset minting transactions must have a non-zero positive fee")
        else require(tx.fee >= 0L, "Transfer transactions must have a non-negative fee")

        require(tx.from.nonEmpty, "Non-block reward transactions must specify at least one input box")
        require(tx.to.forall(_._2.quantity > 0L), "Amount sent must be greater than 0")
    }

    require(tx.timestamp >= 0L, "Invalid timestamp")
    require(tx.data.forall(_.getBytes("UTF-8").length <= 128), "Data field must be less than 128 bytes")

    // ensure that the input and output lists of box ids are unique
    require(
      tx.newBoxes.forall(b => !tx.boxIdsToOpen.contains(b.id)),
      "The set of input box ids contains one or more of the output ids"
    )
  }

  /**
   * For passing Longs to front-end/Javascript
   * @param from the from details - address and amount
   * @return converts the long to a string before converting to json
   */
  def encodeFrom(from: IndexedSeq[(Address, Long)]): Json =
    from.map(x => (x._1.asJson, x._2.toString.asJson)).asJson

  implicit def jsonEncoder[P <: Proposition]: Encoder[TransferTransaction[P]] = { tx: TransferTransaction[P] =>
    Map(
      "txId"            -> tx.id.asJson,
      "txType"          -> tx.txType.asJson,
      "propositionType" -> tx.getPropIdentifier.typeString.asJson,
      "newBoxes"        -> tx.newBoxes.toSeq.asJson,
      "boxesToRemove"   -> tx.boxIdsToOpen.asJson,
      "from"            -> encodeFrom(tx.from),
      "to"              -> tx.to.asJson,
      "signatures"      -> tx.attestation.asJson,
      "fee"             -> tx.fee.asJson,
      "timestamp"       -> tx.timestamp.asJson,
      "minting"         -> tx.minting.asJson,
      "data"            -> tx.data.asJson
    ).asJson
  }

  implicit def jsonDecoder(implicit networkPrefix: NetworkPrefix): Decoder[TransferTransaction[_ <: Proposition]] =
    (c: HCursor) =>
      for {
        from      <- c.downField("from").as[IndexedSeq[(Address, Long)]]
        to        <- c.downField("to").as[IndexedSeq[(Address, SimpleValue)]]
        fee       <- c.downField("fee").as[Long]
        timestamp <- c.downField("timestamp").as[Long]
        data      <- c.downField("data").as[Option[String]]
        propType  <- c.downField("propositionType").as[String]
        minting   <- c.downField("txType").as[Boolean]
        txType    <- c.downField("txType").as[String]
      } yield (propType match {
        case PublicKeyPropositionCurve25519.`typeString` =>
          c.downField("signatures").as[Map[PublicKeyPropositionCurve25519, SignatureCurve25519]].map {
            new TransferTransaction[PublicKeyPropositionCurve25519](from, to, _, fee, timestamp, data, minting, txType)
          }

        case ThresholdPropositionCurve25519.`typeString` =>
          c.downField("signatures").as[Map[ThresholdPropositionCurve25519, ThresholdSignatureCurve25519]].map {
            new TransferTransaction[ThresholdPropositionCurve25519](from, to, _, fee, timestamp, data, minting, txType)
          }
      }) match {
        case Right(tx) => tx
        case Left(ex)  => throw ex
      }

}
