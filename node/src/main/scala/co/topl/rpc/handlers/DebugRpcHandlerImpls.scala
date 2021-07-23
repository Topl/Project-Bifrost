package co.topl.rpc.handlers

import akka.actor.typed.ActorSystem
import cats.implicits._
import co.topl.akkahttprpc.{CustomError, RpcError, ThrowableData}
import co.topl.consensus.{KeyManagerInterface, ListOpenKeyfilesFailureException}
import co.topl.nodeView.history.HistoryDebug
import co.topl.nodeView.{NodeViewHolderInterface, ReadableNodeView}
import co.topl.rpc.{ToplRpc, ToplRpcErrors}
import co.topl.utils.NetworkType.NetworkPrefix
import io.circe.Encoder

class DebugRpcHandlerImpls(
  nodeViewHolderInterface: NodeViewHolderInterface,
  keyManagerInterface:     KeyManagerInterface
)(implicit
  system:           ActorSystem[_],
  throwableEncoder: Encoder[ThrowableData],
  networkPrefix:    NetworkPrefix
) extends ToplRpcHandlers.Debug {

  import system.executionContext

  override val delay: ToplRpc.Debug.Delay.rpc.ServerHandler =
    params =>
      withNodeView(view => new HistoryDebug(view.history).averageDelay(params.blockId, params.numBlocks))
        .subflatMap(
          _.toEither
            .leftMap(CustomError.fromThrowable(_): RpcError)
        )
        .map(delay => ToplRpc.Debug.Delay.Response(s"$delay milliseconds"))

  override val myBlocks: ToplRpc.Debug.MyBlocks.rpc.ServerHandler =
    _ =>
      for {
        myKeys <- keyManagerInterface.listOpenKeyfiles().leftMap { case ListOpenKeyfilesFailureException(throwable) =>
          CustomError.fromThrowable(throwable): RpcError
        }
        blockNum <- withNodeView(view =>
          new HistoryDebug(view.history).count(b => myKeys.map(_.evidence).contains(b.generatorBox.evidence))
        )
      } yield ToplRpc.Debug.MyBlocks.Response(myKeys, blockNum)

  override val generators: ToplRpc.Debug.Generators.rpc.ServerHandler =
    _ =>
      withNodeView(view => new HistoryDebug(view.history).forgerDistribution())
        .map(
          _.map { case (key, count) =>
            key.address -> count
          }
        )

  override val idsFromHeight: ToplRpc.Debug.IdsFromHeight.rpc.ServerHandler =
    params =>
      withNodeView(view =>
        new HistoryDebug(view.history)
          .getIdsFrom(params.height, params.limit)
      )
        .subflatMap(
          _.toRight(ToplRpcErrors.NoBlockIdsAtHeight: RpcError)
            .map(_.toList)
        )

  private def withNodeView[T](f: ReadableNodeView => T) =
    nodeViewHolderInterface
      .withNodeView(f)
      .leftMap { case NodeViewHolderInterface.ReadFailure(throwable) =>
        CustomError.fromThrowable(throwable): RpcError
      }
}
