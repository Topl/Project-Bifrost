package co.topl.rpc.handlers

import akka.actor.typed.ActorSystem
import cats.data.EitherT
import cats.implicits._
import co.topl.akkahttprpc.{CustomError, InvalidParametersError, RpcError, ThrowableData}
import co.topl.attestation.Address
import co.topl.modifier.box._
import co.topl.nodeView.state.StateReader
import co.topl.nodeView.{NodeViewHolderInterface, ReadableNodeView}
import co.topl.rpc.ToplRpc
import co.topl.settings.AppContext
import co.topl.utils.Int128
import co.topl.utils.NetworkType.NetworkPrefix
import io.circe.Encoder

import scala.language.existentials

class NodeViewRpcHandlerImpls(
  appContext:              AppContext,
  nodeViewHolderInterface: NodeViewHolderInterface
)(implicit
  system:           ActorSystem[_],
  throwableEncoder: Encoder[ThrowableData],
  networkPrefix:    NetworkPrefix
) extends ToplRpcHandlers.NodeView {

  import system.executionContext

  override val head: ToplRpc.NodeView.Head.rpc.ServerHandler =
    _ =>
      withNodeView(view =>
        ToplRpc.NodeView.Head.Response(
          view.history.height,
          view.history.score,
          view.history.bestBlockId,
          view.history.bestBlock
        )
      )

  override val balances: ToplRpc.NodeView.Balances.rpc.ServerHandler =
    params =>
      withNodeView(view =>
        checkAddresses(params.addresses, view.state)
          .map(balancesResponse(view.state, _))
      ).subflatMap(identity)

  override val transactionById: ToplRpc.NodeView.TransactionById.rpc.ServerHandler =
    params =>
      withNodeView(_.history.transactionById(params.transactionId))
        .subflatMap(
          _.toRight[RpcError](InvalidParametersError.adhoc("Unable to find confirmed transaction", "modifierId"))
        )
        .map { case (tx, blockId, blockNumber) => ToplRpc.NodeView.TransactionById.Response(tx, blockNumber, blockId) }

  override val blockById: ToplRpc.NodeView.BlockById.rpc.ServerHandler =
    params =>
      withNodeView(_.history.modifierById(params.blockId))
        .subflatMap(
          _.toRight[RpcError](InvalidParametersError.adhoc("The requested block could not be found", "blockId"))
        )

  override val blockByHeight: ToplRpc.NodeView.BlockByHeight.rpc.ServerHandler =
    params =>
      withNodeView(_.history.modifierByHeight(params.height))
        .subflatMap(
          _.toRight[RpcError](InvalidParametersError.adhoc("The requested block could not be found", "height"))
        )

  override val mempool: ToplRpc.NodeView.Mempool.rpc.ServerHandler =
    _ => withNodeView(_.memPool.take(100)(-_.dateAdded).map(_.tx).toList)

  override val transactionFromMempool: ToplRpc.NodeView.TransactionFromMempool.rpc.ServerHandler =
    params =>
      withNodeView(_.memPool.modifierById(params.transactionId))
        .subflatMap(
          _.toRight[RpcError](InvalidParametersError.adhoc("Unable to retrieve transaction", "transactionId"))
        )

  override val info: ToplRpc.NodeView.Info.rpc.ServerHandler =
    _ =>
      EitherT.pure(
        ToplRpc.NodeView.Info.Response(
          appContext.networkType.toString,
          appContext.externalNodeAddress.fold("N/A")(_.toString),
          appContext.settings.application.version.toString
        )
      )

  private def balancesResponse(
    state:     StateReader[_, Address],
    addresses: List[Address]
  ): ToplRpc.NodeView.Balances.Response = {
    val boxes =
      addresses.map { k =>
        val orderedBoxes = state.getTokenBoxes(k) match {
          case Some(boxes) => boxes.groupBy[String](Box.identifier(_).typeString).map { case (k, v) => k -> v.toList }
          case _           => Map[String, List[TokenBox[TokenValueHolder]]]()
        }
        k -> orderedBoxes
      }.toMap

    val balances =
      boxes.map { case (addr, assets) =>
        addr -> assets.map { case (boxType, boxes) =>
          (boxType, boxes.map(_.value.quantity).sum)
        }
      }

    boxes.map { case (addr, boxes) =>
      addr -> ToplRpc.NodeView.Balances.Entry(
        ToplRpc.NodeView.Balances.EntryBalances(
          balances(addr).getOrElse(PolyBox.typeString, Int128(0)),
          balances(addr).getOrElse(ArbitBox.typeString, Int128(0))
        ),
        ToplRpc.NodeView.Balances.EntryBoxes(
          boxes.getOrElse(PolyBox.typeString, Nil).collect { case p: PolyBox => p },
          boxes.getOrElse(ArbitBox.typeString, Nil).collect { case p: ArbitBox => p },
          boxes.getOrElse(AssetBox.typeString, Nil).collect { case p: AssetBox => p }
        )
      )
    }
  }

  private def withNodeView[T](f: ReadableNodeView => T) =
    nodeViewHolderInterface
      .withNodeView(f)
      .leftMap { case NodeViewHolderInterface.ReadFailure(throwable) =>
        CustomError.fromThrowable(throwable): RpcError
      }
}
