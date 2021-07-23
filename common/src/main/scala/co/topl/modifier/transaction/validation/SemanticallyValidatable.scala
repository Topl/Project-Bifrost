package co.topl.modifier.transaction.validation

import cats.data.{NonEmptyChain, Validated, ValidatedNec}
import cats.implicits._
import co.topl.attestation.{Address, Proof, Proposition}
import co.topl.modifier.BoxReader
import co.topl.modifier.box._
import co.topl.modifier.transaction._
import co.topl.utils.Int128
import co.topl.utils.NetworkType.NetworkPrefix
import simulacrum._

import scala.collection.compat.immutable.LazyList
import scala.language.implicitConversions
import scala.util.{Failure, Success, Try}

@typeclass trait SemanticallyValidatable[T] {

  /**
   * Performs semantic validation on the given T.  Semantic validation needs to consider some sort of context when
   * making decisions.  The context in this case is a view/reader of the boxes in the history of the blockchain.
   *
   * This type of validation is expected to be "computationally expensive"
   *
   * @param t The item to validate
   * @param boxReader The context of this validation
   * @return either a non-empty listt of SemanticValidationFailures, or the given value T
   */
  def semanticValidation(t: T, boxReader: BoxReader[ProgramId, Address])(implicit
    networkPrefix:          NetworkPrefix
  ): ValidatedNec[SemanticValidationFailure, T]

}

trait SemanticallyValidatableInstances {

  implicit def polyTransferTransactionSemanticallyValidatable[P <: Proposition]
    : SemanticallyValidatable[PolyTransfer[P]] = {
    val delegate =
      new TransferTransactionSemanticallyValidatable[SimpleValue, P]

    new SemanticallyValidatable[PolyTransfer[P]] {
      override def semanticValidation(t: PolyTransfer[P], boxReader: BoxReader[ProgramId, Address])(implicit
        networkPrefix:                   NetworkPrefix
      ): ValidatedNec[SemanticValidationFailure, PolyTransfer[P]] =
        delegate.semanticValidation(t, boxReader).map(_ => t)
    }

  }

  implicit def arbitTransferTransactionSemanticallyValidatable[P <: Proposition]
    : SemanticallyValidatable[ArbitTransfer[P]] = {
    val delegate =
      new TransferTransactionSemanticallyValidatable[SimpleValue, P]

    new SemanticallyValidatable[ArbitTransfer[P]] {
      override def semanticValidation(t: ArbitTransfer[P], boxReader: BoxReader[ProgramId, Address])(implicit
        networkPrefix:                   NetworkPrefix
      ): ValidatedNec[SemanticValidationFailure, ArbitTransfer[P]] =
        delegate.semanticValidation(t, boxReader).map(_ => t)
    }
  }

  implicit def assetTransferTransactionSemanticallyValidatable[P <: Proposition]
    : SemanticallyValidatable[AssetTransfer[P]] = {
    val delegate =
      new TransferTransactionSemanticallyValidatable[TokenValueHolder, P]

    new SemanticallyValidatable[AssetTransfer[P]] {
      override def semanticValidation(t: AssetTransfer[P], boxReader: BoxReader[ProgramId, Address])(implicit
        networkPrefix:                   NetworkPrefix
      ): ValidatedNec[SemanticValidationFailure, AssetTransfer[P]] =
        delegate.semanticValidation(t, boxReader).map(_ => t)
    }
  }

  implicit def transferTransactionSemanticallyValidatable[T <: TokenValueHolder, P <: Proposition]
    : TransferTransactionSemanticallyValidatable[T, P] =
    new TransferTransactionSemanticallyValidatable[T, P]

  implicit def transactionSemanticallyValidatable[T, P <: Proposition]: SemanticallyValidatable[Transaction[T, P]] =
    new SemanticallyValidatable[Transaction[T, P]] {

      override def semanticValidation(
        t:                      Transaction[T, P],
        boxReader:              BoxReader[ProgramId, Address]
      )(implicit networkPrefix: NetworkPrefix): ValidatedNec[SemanticValidationFailure, Transaction[T, P]] =
        t match {
          case transaction: TransferTransaction[TokenValueHolder, P] =>
            transferTransactionSemanticallyValidatable[TokenValueHolder, P]
              .semanticValidation(transaction, boxReader)
              .map(_ => t)
          case t => t.validNec
        }
    }
}

class TransferTransactionSemanticallyValidatable[T <: TokenValueHolder, P <: Proposition]
    extends SemanticallyValidatable[TransferTransaction[T, P]] {
  import SemanticValidation._
  import implicits._

  /**
   * Checks the stateful validity of a transaction
   *
   * @param boxReader the state to check the validity against
   * @return a success or failure denoting the result of this check
   */
  def semanticValidation(tx: TransferTransaction[T, P], boxReader: BoxReader[ProgramId, Address])(implicit
    networkPrefix:           NetworkPrefix
  ): ValidatedNec[SemanticValidationFailure, TransferTransaction[T, P]] = {
    import tx._
    // compute transaction values used for validation
    val txOutput: Int128 = tx.newBoxes.map(_.value.quantity).sum
    (Try(BoxUnlocker.generate(tx.from, tx.attestation)) match {
      case Failure(_)     => BoxNotFound.invalidNec
      case Success(value) => value.validNec[SemanticValidationFailure]
    }).andThen { unlockers =>
      val inputBoxes: List[(BoxUnlocker[P, Proof[P]], Option[Box[_]])] =
        unlockers.map(u => u -> boxReader.getBox(u.closedBoxId)).toList

      val sumOfPolyInputs: Int128 = inputBoxes.collect { case (_, Some(PolyBox(_, _, value))) =>
        value.quantity
      }.sum

      syntacticSemanticValidation(tx)
        .andThen(txSpecificValidation(_)(txOutput, sumOfPolyInputs))
        .andThen(accessibleFundsValidation(_)(inputBoxes, txOutput, sumOfPolyInputs))
    }
  }

  private[transaction] def syntacticSemanticValidation(tx: TransferTransaction[T, P])(implicit
    networkPrefix:                                         NetworkPrefix
  ): ValidatedNec[SemanticValidationFailure, TransferTransaction[T, P]] =
    tx.syntacticValidation.toEither.leftMap(SyntacticSemanticValidationFailure).toValidatedNec

  private[transaction] def txSpecificValidation(tx: TransferTransaction[T, P])(
    txOutput:                                       Int128,
    sumOfPolyInputs:                                Int128
  ): ValidatedNec[SemanticValidationFailure, TransferTransaction[T, P]] =
    tx match {
      case _: PolyTransfer[_] if tx.minting =>
        // Poly block rewards (skip enfocring)
        tx.validNec[SemanticValidationFailure]
      case _: ArbitTransfer[_] if tx.minting =>
        // Arbit block rewards (skip enforcing)
        tx.validNec[SemanticValidationFailure]
      case _: PolyTransfer[_] =>
        Validated.condNec(
          sumOfPolyInputs - tx.fee == txOutput,
          tx,
          InputOutputUnequal(sumOfPolyInputs, txOutput, tx.fee, tx.minting)
        )
      case _ =>
        /*  This case enforces that the poly input balance must equal the poly output balance

        This case is special for AssetTransfer and ArbitTransfer (collapsed to one to not duplicate the code)
        It assumes that syntactic validate enforces
          - at least one box in the `from` field
          - at least two boxes in the `to` field [changeOutput,
        then we should have a non-zero value of `sumOfPolyInputs` (if we don't they didn't provide a poly input that is required)
        and we should have the first element of the `to` list that is designated to be the feeChangeOutput (even if that output is zero)
        these two invariants (for these two transaction) allow us to make the requirement below that enforces that
        the sum of the poly inputs equals the sum of the poly outputs.
         */
        Validated.condNec(
          sumOfPolyInputs - tx.fee == tx.feeChangeOutput.value.quantity,
          tx,
          InputFeeChangeOutputUnequalNonMinting(tx.feeChangeOutput.value.quantity, sumOfPolyInputs, tx.fee)
        )
    }

  private[transaction] def accessibleFundsValidation(tx: TransferTransaction[T, P])(
    inputBoxes:                                          List[(BoxUnlocker[P, Proof[P]], Option[Box[_]])],
    txOutput:                                            Int128,
    sumOfPolyInputs:                                     Int128
  ): ValidatedNec[SemanticValidationFailure, TransferTransaction[T, P]] =
    takeWhileInclusive(
      inputBoxes
        .to(LazyList)
        .map {
          case (unlocker, Some(box: TokenBox[_])) if unlocker.boxKey.isValid(unlocker.proposition, tx.messageToSign) =>
            Right(box.value.quantity)
          case (_, Some(box: TokenBox[TokenValueHolder @unchecked])) =>
            Left(InvalidUnlocker(box))
          case (_, Some(box)) =>
            Left(InvalidBoxType(box))
          case _ =>
            Left(BoxNotFound)
        }
    )(_.isRight)
      .foldLeft(Right(0): Either[SemanticValidationFailure, Int128]) { case (a, b) =>
        a.flatMap(i => b.map(i1 => i + i1))
      }
      .flatMap {
        // a normal transfer will fall in this case
        case sum if txOutput == sum - tx.fee =>
          Right(tx)

        // a minting transaction (of either Arbit, Polys, or Assets) will fall in this case
        case _ if tx.minting =>
          Right(tx)

        case sum if !tx.minting && txOutput != sum - tx.fee =>
          Left(
            InputOutputUnequal(
              sumOfPolyInputs,
              txOutput,
              tx.fee,
              tx.minting
            )
          )
      }
      .toValidatedNec
}

object SemanticValidation {

  /**
   * Similar to Iterable#takeWhile, but the first item that fails the condition will be kept instead of discarded
   */
  def takeWhileInclusive[A](items: LazyList[A])(cond: A => Boolean): LazyList[A] = {
    var foundFailure: Boolean = false
    items.takeWhile { t =>
      if (foundFailure) false
      else {
        foundFailure = !cond(t)
        true
      }
    }
  }

}

sealed abstract class SemanticValidationFailure

case class SyntacticSemanticValidationFailure(syntacticValidationFailures: NonEmptyChain[SyntacticValidationFailure])
    extends SemanticValidationFailure

case class InputOutputUnequal(sumOfPolyInputs: Int128, txOutput: Int128, fee: Int128, minting: Boolean)
    extends SemanticValidationFailure

case class InputFeeChangeOutputUnequalNonMinting(feeChangeQuantity: Int128, sumOfPolyInputs: Int128, fee: Int128)
    extends SemanticValidationFailure
case class InvalidUnlocker(box: TokenBox[TokenValueHolder]) extends SemanticValidationFailure
case class InvalidBoxType(box: Box[_]) extends SemanticValidationFailure
case object BoxNotFound extends SemanticValidationFailure
