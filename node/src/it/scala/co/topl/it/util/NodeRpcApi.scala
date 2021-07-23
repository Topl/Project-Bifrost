package co.topl.it.util

import akka.Done
import akka.actor.{ActorSystem, Scheduler}
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.RawHeader
import akka.stream.scaladsl.{Sink, Source}
import cats.data.EitherT
import cats.implicits._
import co.topl.akkahttprpc.implicits.client.rpcToClient
import co.topl.akkahttprpc.utils.Retry
import co.topl.akkahttprpc.{RequestModifier, Rpc, RpcClientFailure, RpcError}
import co.topl.crypto.hash.blake2b256
import co.topl.crypto.hash.digest.Digest32
import co.topl.crypto.hash.implicits._
import co.topl.modifier.ModifierId
import co.topl.rpc.ToplRpc
import co.topl.rpc.ToplRpc.NodeView.TransactionById
import co.topl.rpc.implicits.client._
import co.topl.utils.NetworkType.NetworkPrefix
import co.topl.utils.codecs.implicits._
import co.topl.utils.encode.Base58
import co.topl.utils.{Int128, NetworkType}
import com.spotify.docker.client.DockerClient
import io.circe._

import scala.concurrent.Future
import scala.concurrent.duration._

case class NodeRpcApi(host: String, rpcPort: Int)(implicit system: ActorSystem, node: BifrostDockerNode) {

  import system.dispatcher

  implicit def scheduler: Scheduler = system.scheduler

  implicit val rpcRequestModifier: RequestModifier =
    RequestModifier(
      _.withMethod(HttpMethods.POST)
        .withUri(s"http://$host:$rpcPort")
        .withHeaders(RawHeader("x-api-key", NodeRpcApi.ApiKey))
    )

  def run[Params: Encoder, Result: Decoder](rpc: Rpc[Params, Result])(
    params:                                      Params
  )(implicit
    futureAwaiter: Future[Either[RpcClientFailure, Result]] => Either[RpcClientFailure, Result]
  ): Either[RpcClientFailure, Result] =
    futureAwaiter(rpc(params).value)

  implicit val networkPrefix: NetworkPrefix = NetworkType.PrivateTestnet.netPrefix

  def waitForStartup(): Future[Either[RpcClientFailure, Done]] =
    Retry(
      () => ToplRpc.Debug.MyBlocks.rpc.call.apply(ToplRpc.Debug.MyBlocks.Params()).map(_ => Done),
      interval = 1.seconds,
      timeout = 30.seconds,
      attempts = 30
    ).value

  def pollUntilHeight(targetHeight: Int128, period: FiniteDuration = 200.milli)(implicit
    scheduler:                      Scheduler
  ): Future[Either[RpcError, Done]] =
    Source
      .tick(Duration.Zero, period, {})
      .mapAsync(1)(_ => ToplRpc.NodeView.Head.rpc.call.apply(ToplRpc.NodeView.Head.Params()).value)
      .collect {
        case Right(response) if response.height >= targetHeight =>
          Right(Done)
      }
      .runWith(Sink.head)

  def pollForTransaction(transactionId: ModifierId, timeout: FiniteDuration = 20.seconds)(implicit
    scheduler:                          Scheduler
  ): EitherT[Future, RpcClientFailure, TransactionById.Response] =
    Retry(
      () => ToplRpc.NodeView.TransactionById.rpc.call.apply(ToplRpc.NodeView.TransactionById.Params(transactionId)),
      interval = 500.milli,
      timeout = timeout,
      attempts = Int.MaxValue
    )
}

object NodeRpcApi {

  val ApiKey = "integration-test-key"
  val ApiKeyHash: Digest32 = blake2b256.hash(ApiKey.getBytes)
  val ApiKeyHashBase58: String = Base58.encode(ApiKeyHash.value)

  def apply(node: BifrostDockerNode)(implicit system: ActorSystem, dockerClient: DockerClient): NodeRpcApi = {
    val host = dockerClient.inspectContainer(node.containerId).networkSettings().ipAddress()
    require(host.nonEmpty, s"Docker container is missing IP address.  containerId=${node.containerId}")
    implicit val n: BifrostDockerNode = node
    new NodeRpcApi(host, BifrostDockerNode.RpcPort)
  }
}
