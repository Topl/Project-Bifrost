package co.topl.rpc.handlers

import akka.actor.typed.ActorSystem
import cats.implicits._
import co.topl.akkahttprpc.{CustomError, RpcError, ThrowableData}
import co.topl.attestation._
import co.topl.modifier.box.{ProgramId, SimpleValue}
import co.topl.modifier.transaction.validation.implicits._
import co.topl.modifier.transaction.{ArbitTransfer, AssetTransfer, PolyTransfer, Transaction}
import co.topl.nodeView.state.StateReader
import co.topl.nodeView.{NodeViewHolderInterface, ReadableNodeView}
import co.topl.rpc.{ToplRpc, ToplRpcErrors}
import co.topl.utils.NetworkType.NetworkPrefix
import co.topl.utils.StringDataTypes.implicits._
import co.topl.utils.codecs.implicits._
import io.circe.Encoder

import scala.concurrent.Future
import scala.util.Try

class TransactionRpcHandlerImpls(
  nodeViewHolderInterface: NodeViewHolderInterface
)(implicit
  system:           ActorSystem[_],
  throwableEncoder: Encoder[ThrowableData],
  networkPrefix:    NetworkPrefix
) extends ToplRpcHandlers.Transaction {

  import system.executionContext

  override val rawAssetTransfer: ToplRpc.Transaction.RawAssetTransfer.rpc.ServerHandler =
    params =>
      for {
        transferTry <- withNodeView(view =>
          checkAddresses(params.sender.toList, view.state)
            .map(tryCreateAssetTransfer(params, view.state, _))
        ).subflatMap(identity)
        transfer <- Either
          .fromTry(transferTry)
          .leftMap[RpcError](ToplRpcErrors.transactionValidationException(_))
          .toEitherT[Future]
        messageToSign = transfer.messageToSign.encodeAsBase58
      } yield ToplRpc.Transaction.RawAssetTransfer.Response(transfer, messageToSign.show)

  override val rawArbitTransfer: ToplRpc.Transaction.RawArbitTransfer.rpc.ServerHandler =
    params =>
      for {
        transferTry <- withNodeView(view =>
          checkAddresses(params.sender.toList, view.state)
            .map(tryCreateArbitTransfer(params, view.state, _))
        ).subflatMap(identity)
        transfer <- Either
          .fromTry(transferTry)
          .leftMap[RpcError](ToplRpcErrors.transactionValidationException(_))
          .toEitherT[Future]
        messageToSign = transfer.messageToSign.encodeAsBase58
      } yield ToplRpc.Transaction.RawArbitTransfer.Response(transfer, messageToSign.show)

  override val rawPolyTransfer: ToplRpc.Transaction.RawPolyTransfer.rpc.ServerHandler =
    params =>
      for {
        transferTry <- withNodeView(view =>
          checkAddresses(params.sender.toList, view.state)
            .map(tryCreatePolyTransfer(params, view.state, _))
        ).subflatMap(identity)
        transfer <- Either
          .fromTry(transferTry)
          .leftMap[RpcError](ToplRpcErrors.transactionValidationException(_))
          .toEitherT[Future]
        messageToSign = transfer.messageToSign.encodeAsBase58
      } yield ToplRpc.Transaction.RawPolyTransfer.Response(transfer, messageToSign.show)

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
    state:           StateReader[ProgramId, Address],
    senderAddresses: List[Address]
  ): Try[AssetTransfer[Proposition]] = {
    val createRaw = params.propositionType match {
      case PublicKeyPropositionCurve25519.`typeString` => AssetTransfer.createRaw[PublicKeyPropositionCurve25519] _
      case ThresholdPropositionCurve25519.`typeString` => AssetTransfer.createRaw[ThresholdPropositionCurve25519] _
      case PublicKeyPropositionEd25519.`typeString`    => AssetTransfer.createRaw[PublicKeyPropositionEd25519] _
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
    state:           StateReader[ProgramId, Address],
    senderAddresses: List[Address]
  ): Try[ArbitTransfer[Proposition]] = {
    val createRaw = params.propositionType match {
      case PublicKeyPropositionCurve25519.`typeString` => ArbitTransfer.createRaw[PublicKeyPropositionCurve25519] _
      case ThresholdPropositionCurve25519.`typeString` => ArbitTransfer.createRaw[ThresholdPropositionCurve25519] _
      case PublicKeyPropositionEd25519.`typeString`    => ArbitTransfer.createRaw[PublicKeyPropositionEd25519] _
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
    state:           StateReader[ProgramId, Address],
    senderAddresses: List[Address]
  ): Try[PolyTransfer[Proposition]] = {
    val f =
      params.propositionType match {
        case PublicKeyPropositionCurve25519.`typeString` => PolyTransfer.createRaw[PublicKeyPropositionCurve25519] _
        case ThresholdPropositionCurve25519.`typeString` => PolyTransfer.createRaw[ThresholdPropositionCurve25519] _
        case PublicKeyPropositionEd25519.`typeString`    => PolyTransfer.createRaw[PublicKeyPropositionEd25519] _
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
    nodeViewHolderInterface.applyTransactions(tx).leftMap { case NodeViewHolderInterface.ApplyFailure(throwable) =>
      CustomError.fromThrowable(throwable): RpcError
    }

  private def withNodeView[T](f: ReadableNodeView => T) =
    nodeViewHolderInterface
      .withNodeView(f)
      .leftMap { case NodeViewHolderInterface.ReadFailure(throwable) =>
        CustomError.fromThrowable(throwable): RpcError
      }
}
