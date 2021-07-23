package co.topl.modifier.transaction.validation

import cats.Semigroup
import cats.data.{Validated, ValidatedNec}
import cats.implicits._
import co.topl.attestation.EvidenceProducer.Syntax._
import co.topl.attestation.Proposition
import co.topl.modifier.box._
import co.topl.modifier.transaction._
import co.topl.utils.Extensions.StringOps
import co.topl.utils.NetworkType.NetworkPrefix
import simulacrum._

import scala.language.implicitConversions

@typeclass trait SyntacticallyValidatable[T] {

  /**
   * Performs syntactic validation on the given T.  Semantic validation performs context-free checks on the given value.
   * This validation only verifies that the given T is "grammatically valid", but it does not consider the history of
   * the blockchain or any other variable data.
   *
   * This type of validation is expected to be "computationally inexpensive"
   *
   * @param t The item to validate
   * @return either a non-empty listt of SyntacticValidationFailures, or the given value T
   */
  def syntacticValidation(t: T)(implicit
    networkPrefix:           NetworkPrefix
  ): ValidatedNec[SyntacticValidationFailure, T]

  /**
   * Performs most syntactic validation of the given value T, but without a specification of a NetworkPrefix.  This
   * generally means it does not perform signature or address verifications.
   * @param t The item to validate
   * @return either a non-empty listt of SyntacticValidationFailures, or the given value T
   */
  def rawSyntacticValidation(t: T): ValidatedNec[SyntacticValidationFailure, T]
}

trait SyntacticallyValidatableInstances {

  implicit def polyTransferTransactionSyntacticallyValidatable[P <: Proposition]
    : SyntacticallyValidatable[PolyTransfer[P]] = {
    val delegate =
      new TransferTransactionSyntacticallyValidatable[SimpleValue, P]

    new SyntacticallyValidatable[PolyTransfer[P]] {
      override def syntacticValidation(t: PolyTransfer[P])(implicit
        networkPrefix:                    NetworkPrefix
      ): ValidatedNec[SyntacticValidationFailure, PolyTransfer[P]] =
        delegate.syntacticValidation(t).map(_ => t)

      override def rawSyntacticValidation(
        t: PolyTransfer[P]
      ): ValidatedNec[SyntacticValidationFailure, PolyTransfer[P]] =
        delegate.rawSyntacticValidation(t).map(_ => t)
    }
  }

  implicit def arbitTransferTransactionSyntacticallyValidatable[P <: Proposition]
    : SyntacticallyValidatable[ArbitTransfer[P]] = {
    val delegate =
      new TransferTransactionSyntacticallyValidatable[SimpleValue, P]

    new SyntacticallyValidatable[ArbitTransfer[P]] {
      override def syntacticValidation(t: ArbitTransfer[P])(implicit
        networkPrefix:                    NetworkPrefix
      ): ValidatedNec[SyntacticValidationFailure, ArbitTransfer[P]] =
        delegate.syntacticValidation(t).map(_ => t)

      override def rawSyntacticValidation(
        t: ArbitTransfer[P]
      ): ValidatedNec[SyntacticValidationFailure, ArbitTransfer[P]] =
        delegate.rawSyntacticValidation(t).map(_ => t)
    }
  }

  implicit def assetTransferTransactionSyntacticallyValidatable[P <: Proposition]
    : SyntacticallyValidatable[AssetTransfer[P]] = {
    val delegate =
      new TransferTransactionSyntacticallyValidatable[TokenValueHolder, P]

    new SyntacticallyValidatable[AssetTransfer[P]] {
      override def syntacticValidation(t: AssetTransfer[P])(implicit
        networkPrefix:                    NetworkPrefix
      ): ValidatedNec[SyntacticValidationFailure, AssetTransfer[P]] =
        delegate.syntacticValidation(t).map(_ => t)

      override def rawSyntacticValidation(
        t: AssetTransfer[P]
      ): ValidatedNec[SyntacticValidationFailure, AssetTransfer[P]] =
        delegate.rawSyntacticValidation(t).map(_ => t)
    }
  }

  implicit def transferTransactionSyntacticallyValidatable[T <: TokenValueHolder, P <: Proposition]
    : TransferTransactionSyntacticallyValidatable[T, P] =
    new TransferTransactionSyntacticallyValidatable[T, P]

  implicit def transactionSyntacticallyValidatable[T, P <: Proposition]: SyntacticallyValidatable[Transaction[T, P]] =
    new SyntacticallyValidatable[Transaction[T, P]] {

      override def syntacticValidation(
        t:                      Transaction[T, P]
      )(implicit networkPrefix: NetworkPrefix): ValidatedNec[SyntacticValidationFailure, Transaction[T, P]] =
        t match {
          case transaction: TransferTransaction[TokenValueHolder, P] =>
            transferTransactionSyntacticallyValidatable[TokenValueHolder, P]
              .syntacticValidation(transaction)
              .map(_ => t)
          case t => t.validNec
        }

      override def rawSyntacticValidation(
        t: Transaction[T, P]
      ): ValidatedNec[SyntacticValidationFailure, Transaction[T, P]] =
        t match {
          case transaction: TransferTransaction[TokenValueHolder, P] =>
            transferTransactionSyntacticallyValidatable[TokenValueHolder, P]
              .rawSyntacticValidation(transaction)
              .map(_ => t)
          case t => t.validNec
        }
    }
}

class TransferTransactionSyntacticallyValidatable[T <: TokenValueHolder, P <: Proposition]
    extends SyntacticallyValidatable[TransferTransaction[T, P]] {

  implicit private val txSemigroup: Semigroup[TransferTransaction[T, P]] = (_, b) => b

  def syntacticValidation(tx: TransferTransaction[T, P])(implicit
    networkPrefix:            NetworkPrefix
  ): ValidatedNec[SyntacticValidationFailure, TransferTransaction[T, P]] =
    rawSyntacticValidation(tx).combine(attestationValidation(tx))

  def rawSyntacticValidation(
    tx: TransferTransaction[T, P]
  ): ValidatedNec[SyntacticValidationFailure, TransferTransaction[T, P]] =
    validationByTransactionType(tx)
      .combine(feeValidation(tx))
      .combine(timestampValidation(tx))
      .combine(dataValidation(tx))
      .combine(inputOutputBoxesUniqueValidation(tx))

  private[transaction] def validationByTransactionType(
    tx: TransferTransaction[T, P]
  ): ValidatedNec[SyntacticValidationFailure, TransferTransaction[T, P]] =
    tx match {
      case _: ArbitTransfer[_] | _: PolyTransfer[_] if tx.minting =>
        tx.validNec
      case _: PolyTransfer[_] =>
        Validated.condNec(tx.from.nonEmpty, tx, NoInputBoxesSpecified)
      case _: ArbitTransfer[_] =>
        Validated
          .condNec(tx.from.nonEmpty, tx, NoInputBoxesSpecified)
          .combine(Validated.condNec(tx.to.size >= 2, tx, NonPolyTxInsufficientOutputs))
      case _: AssetTransfer[_] =>
        Validated
          .condNec(tx.from.nonEmpty, tx, NoInputBoxesSpecified)
          .combine(Validated.condNec(tx.to.size >= 2, tx, NonPolyTxInsufficientOutputs))
          .combine(
            Validated.condNec(
              tx.to.forall {
                _._2 match {
                  case assetValue: AssetValue =>
                    assetValue.metadata.forall(_.getValidLatin1Bytes match {
                      case Some(_) => true
                      case None    => false
                    })
                  case _ => true
                }
              },
              tx,
              DataNotLatin1
            )
          )
          .combine(
            Validated.condNec(
              tx.to.forall {
                _._2 match {
                  case assetValue: AssetValue =>
                    assetValue.metadata.forall(_.getValidLatin1Bytes match {
                      case Some(bytes) => bytes.length <= 127
                      case None        => false
                    })
                  case _ => true
                }
              },
              tx,
              DataTooLong
            )
          )
    }

  private[transaction] def dataValidation(
    tx: TransferTransaction[T, P]
  ): ValidatedNec[SyntacticValidationFailure, TransferTransaction[T, P]] =
    tx.data.fold(tx.validNec[SyntacticValidationFailure])(data =>
      Validated
        .fromOption(data.getValidLatin1Bytes, DataNotLatin1)
        .toValidatedNec[SyntacticValidationFailure, Array[Byte]]
        .andThen(bytes => Validated.condNec(bytes.length <= 127, tx, DataTooLong))
    )

  private[transaction] def propositionSatisfiedValidation(
    tx: TransferTransaction[T, P]
  ): ValidatedNec[SyntacticValidationFailure, TransferTransaction[T, P]] =
    Validated
      .condNec(
        tx.attestation.forall { case (prop, proof) =>
          proof.isValid(prop, tx.messageToSign)
        },
        tx,
        UnsatisfiedProposition
      )

  private[transaction] def propositionEvidenceMatchValidation(
    tx: TransferTransaction[T, P]
  ): ValidatedNec[SyntacticValidationFailure, TransferTransaction[T, P]] = {
    import tx.evidenceProducerEv
    Validated.condNec(
      tx.from.forall { case (addr, _) =>
        tx.attestation.keys.map(_.generateEvidence).toSeq.contains(addr.evidence)
      },
      tx,
      PropositionEvidenceMismatch
    )
  }

  private[transaction] def mintingIssuersValidation(tx: TransferTransaction[T, P])(implicit
    networkPrefix:                                      NetworkPrefix
  ): ValidatedNec[SyntacticValidationFailure, TransferTransaction[T, P]] =
    tx match {
      case _: AssetTransfer[_] if tx.minting =>
        tx.to
          .map {
            case (_, asset: AssetValue) =>
              Validated.condNec(
                tx.attestation.keys.map(_.address).toSeq.contains(asset.assetCode.issuer),
                tx,
                MintingMissingIssuersSignature
              )
            case _ => tx.validNec[SyntacticValidationFailure]
          }
          .foldLeft(tx.validNec[SyntacticValidationFailure]) { case (acc, v) => acc.combine(v) }
      case tx =>
        tx.validNec[SyntacticValidationFailure]
    }

  private[transaction] def attestationValidation(tx: TransferTransaction[T, P])(implicit
    networkPrefix:                                   NetworkPrefix
  ): ValidatedNec[SyntacticValidationFailure, TransferTransaction[T, P]] =
    propositionSatisfiedValidation(tx)
      .combine(propositionEvidenceMatchValidation(tx))
      .combine(mintingIssuersValidation(tx))

  private[transaction] def inputOutputBoxesUniqueValidation(
    tx: TransferTransaction[T, P]
  ): ValidatedNec[SyntacticValidationFailure, TransferTransaction[T, P]] =
    Validated.condNec(
      tx.newBoxes.size == tx.newBoxes.toSet.size &&
      tx.boxIdsToOpen.size == tx.boxIdsToOpen.toSet.size &&
      tx.newBoxes.map(_.id).toSet.intersect(tx.boxIdsToOpen.toSet).isEmpty,
      tx,
      InputOutputBoxesNotUnique
    )

  private[transaction] def feeValidation(
    tx: TransferTransaction[T, P]
  ): ValidatedNec[SyntacticValidationFailure, TransferTransaction[T, P]] =
    Validated.condNec(tx.fee >= 0L, tx, NegativeFeeFailure)

  private[transaction] def timestampValidation(
    tx: TransferTransaction[T, P]
  ): ValidatedNec[SyntacticValidationFailure, TransferTransaction[T, P]] =
    Validated.condNec(tx.timestamp >= 0L, tx, InvalidTimestamp)
}

sealed abstract class SyntacticValidationFailure
case object NegativeFeeFailure extends SyntacticValidationFailure
case object NoInputBoxesSpecified extends SyntacticValidationFailure
case object InvalidSendAmount extends SyntacticValidationFailure
case object InvalidTimestamp extends SyntacticValidationFailure
case object DataNotLatin1 extends SyntacticValidationFailure
case object DataTooLong extends SyntacticValidationFailure
case object UnsatisfiedProposition extends SyntacticValidationFailure
case object PropositionEvidenceMismatch extends SyntacticValidationFailure
case object MintingMissingIssuersSignature extends SyntacticValidationFailure
case object InputOutputBoxesNotUnique extends SyntacticValidationFailure
case object NonPolyTxInsufficientOutputs extends SyntacticValidationFailure
