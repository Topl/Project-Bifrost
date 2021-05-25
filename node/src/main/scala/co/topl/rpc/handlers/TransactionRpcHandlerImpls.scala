package co.topl.rpc.handlers

import cats.implicits._
import co.topl.akkahttprpc.{CustomError, RpcError, ThrowableData}
import co.topl.attestation.{Address, Proposition, PublicKeyPropositionCurve25519, ThresholdPropositionCurve25519}
import co.topl.modifier.box.SimpleValue
import co.topl.modifier.transaction.{ArbitTransfer, AssetTransfer, PolyTransfer, Transaction}
import co.topl.modifier.transaction.validation.implicits._
import co.topl.nodeView.state.State
import co.topl.nodeView.{BroadcastTxFailureException, GetStateFailureException, NodeViewHolderInterface}
import co.topl.rpc.{ToplRpc, ToplRpcErrors}
import co.topl.utils.NetworkType.NetworkPrefix
import io.circe.Encoder
import scorex.util.encode.Base58

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

class TransactionRpcHandlerImpls(
  nodeViewHolderInterface: NodeViewHolderInterface
)(implicit
  ec:               ExecutionContext,
  throwableEncoder: Encoder[ThrowableData],
  networkPrefix:    NetworkPrefix
) extends ToplRpcHandlers.Transaction {

  override val rawAssetTransfer: ToplRpc.Transaction.RawAssetTransfer.rpc.ServerHandler =
    params =>
      for {
        state           <- currentState()
        senderAddresses <- checkAddresses(params.sender.toList, state).toEitherT[Future]
        transferTry = tryCreateAssetTransfer(params, state, senderAddresses)
        transfer <- Either
          .fromTry(transferTry)
          .leftMap[RpcError](ToplRpcErrors.transactionValidationException(_))
          .toEitherT[Future]
        messageToSign = Base58.encode(transfer.messageToSign)
      } yield ToplRpc.Transaction.RawAssetTransfer.Response(transfer, messageToSign)

  override val rawArbitTransfer: ToplRpc.Transaction.RawArbitTransfer.rpc.ServerHandler =
    params =>
      for {
        state           <- currentState()
        senderAddresses <- checkAddresses(params.sender.toList, state).toEitherT[Future]
        transferTry = tryCreateArbitTransfer(params, state, senderAddresses)
        transfer <- Either
          .fromTry(transferTry)
          .leftMap[RpcError](ToplRpcErrors.transactionValidationException(_))
          .toEitherT[Future]
        messageToSign = Base58.encode(transfer.messageToSign)
      } yield ToplRpc.Transaction.RawArbitTransfer.Response(transfer, messageToSign)

  override val rawPolyTransfer: ToplRpc.Transaction.RawPolyTransfer.rpc.ServerHandler =
    params =>
      for {
        state           <- currentState()
        senderAddresses <- checkAddresses(params.sender.toList, state).toEitherT[Future]
        transferTry = tryCreatePolyTransfer(params, state, senderAddresses)
        transfer <- Either
          .fromTry(transferTry)
          .leftMap[RpcError](ToplRpcErrors.transactionValidationException(_))
          .toEitherT[Future]
        messageToSign = Base58.encode(transfer.messageToSign)
      } yield ToplRpc.Transaction.RawPolyTransfer.Response(transfer, messageToSign)

  override val broadcastTx: ToplRpc.Transaction.BroadcastTx.rpc.ServerHandler =
    params =>
      for {
        transaction <- params.tx.syntacticValidation.toEither
          .leftMap(ToplRpcErrors.syntacticValidationFailure)
          .toEitherT[Future]
        _ <- processTransaction(transaction)
      } yield transaction

  private def tryCreateAssetTransfer(
    params:          ToplRpc.Transaction.RawAssetTransfer.Params,
    state:           State,
    senderAddresses: List[Address]
  ): Try[AssetTransfer[Proposition]] = {
    val createRaw = params.propositionType match {
      case PublicKeyPropositionCurve25519.`typeString` => AssetTransfer.createRaw[PublicKeyPropositionCurve25519] _
      case ThresholdPropositionCurve25519.`typeString` => AssetTransfer.createRaw[ThresholdPropositionCurve25519] _
    }

    createRaw(
      state,
      params.recipients.toNonEmptyVector.toVector,
      senderAddresses.toIndexedSeq,
      params.changeAddress,
      params.consolidationAddress,
      params.fee,
      params.data,
      params.minting
    )
      .collect { case p: AssetTransfer[Proposition @unchecked] =>
        p
      }
  }

  private def tryCreateArbitTransfer(
    params:          ToplRpc.Transaction.RawArbitTransfer.Params,
    state:           State,
    senderAddresses: List[Address]
  ): Try[ArbitTransfer[Proposition]] = {
    val createRaw = params.propositionType match {
      case PublicKeyPropositionCurve25519.`typeString` => ArbitTransfer.createRaw[PublicKeyPropositionCurve25519] _
      case ThresholdPropositionCurve25519.`typeString` => ArbitTransfer.createRaw[ThresholdPropositionCurve25519] _
    }

    createRaw(
      state,
      params.recipients.map { case (address, amount) => address -> SimpleValue(amount) }.toNonEmptyVector.toVector,
      senderAddresses.toIndexedSeq,
      params.changeAddress,
      params.consolidationAddress,
      params.fee,
      params.data
    )
      .collect { case p: ArbitTransfer[Proposition @unchecked] =>
        p
      }
  }

  private def tryCreatePolyTransfer(
    params:          ToplRpc.Transaction.RawPolyTransfer.Params,
    state:           State,
    senderAddresses: List[Address]
  ): Try[PolyTransfer[Proposition]] = {
    val f =
      params.propositionType match {
        case PublicKeyPropositionCurve25519.`typeString` =>
          PolyTransfer.createRaw[PublicKeyPropositionCurve25519] _
        case ThresholdPropositionCurve25519.`typeString` =>
          PolyTransfer.createRaw[ThresholdPropositionCurve25519] _
      }

    f(
      state,
      params.recipients.map { case (address, v) => address -> SimpleValue(v) }.toNonEmptyVector.toVector,
      senderAddresses.toIndexedSeq,
      params.changeAddress,
      params.fee,
      params.data
    )
  }.collect { case p: PolyTransfer[Proposition @unchecked] => p }

  private def processTransaction(tx: Transaction.TX) =
    nodeViewHolderInterface.broadcastTransaction(tx).leftMap { case BroadcastTxFailureException(throwable) =>
      CustomError.fromThrowable(throwable): RpcError
    }

  private def currentState() =
    nodeViewHolderInterface.getState().leftMap { case GetStateFailureException(throwable) =>
      CustomError.fromThrowable(throwable): RpcError
    }
}
