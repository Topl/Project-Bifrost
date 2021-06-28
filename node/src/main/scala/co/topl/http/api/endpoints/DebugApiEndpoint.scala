package co.topl.http.api.endpoints

import akka.actor.{ActorRef, ActorSystem}
import akka.dispatch.Dispatchers
import akka.pattern.ask
import co.topl.attestation.Address
import co.topl.consensus.KeyManager.ReceivableMessages._
import co.topl.http.api.{ApiEndpointWithView, DebugNamespace, ErrorResponse, Namespace}
import co.topl.modifier.ModifierId
import co.topl.nodeView.history.{History, HistoryDebug}
import co.topl.nodeView.mempool.MemPool
import co.topl.nodeView.state.State
import co.topl.settings.{AppContext, RPCApiSettings}
import co.topl.utils.NetworkType.NetworkPrefix
import io.circe.Json
import io.circe.syntax._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

case class DebugApiEndpoint(
  settings:          RPCApiSettings,
  appContext:        AppContext,
  nodeViewHolderRef: ActorRef,
  keyManagerRef:     ActorRef
)(implicit system:   ActorSystem)
    extends ApiEndpointWithView {

  type HIS = History
  type MS = State
  type MP = MemPool

  override protected def blockingExecutionContext: ExecutionContext =
    system.dispatchers.lookup(Dispatchers.DefaultBlockingDispatcherId)

  implicit private val ec: ExecutionContext =
    system.dispatcher

  // Establish the expected network prefix for addresses
  implicit val networkPrefix: NetworkPrefix = appContext.networkType.netPrefix

  // the namespace for the endpoints defined in handlers
  val namespace: Namespace = DebugNamespace

  // partial function for identifying local method handlers exposed by the api
  val handlers: PartialFunction[(String, Vector[Json], String), Future[Json]] = {
    case (method, params, id) if method == s"${namespace.name}_delay"         => delay(params.head, id)
    case (method, params, id) if method == s"${namespace.name}_myBlocks"      => myBlocks(params.head, id)
    case (method, params, id) if method == s"${namespace.name}_generators"    => generators(params.head, id)
    case (method, params, id) if method == s"${namespace.name}_idsFromHeight" => idsFromHeight(params.head, id)
  }

  /**
   * #### Summary
   * Calculate the average delay over a number of blocks
   *
   * #### Description
   * Find the average delay between blocks starting from a specified blockId and till a certain number of blocks
   * forged on top of it
   *
   * #### Params
   * | Fields    | Data type | Required / Optional | Description                                                    |
   * |-----------|-----------|---------------------|----------------------------------------------------------------|
   * | blockId   | String    | Required            | Id of block from which to start average delay computation      |
   * | numBlocks | Number    | Required            | Number of blocks back to consider when computing average delay |
   *
   * @param params input parameters as specified above
   * @param id request identifier
   * @return
   */
  private def delay(params: Json, id: String): Future[Json] =
    asyncHistory { hr =>
      (for {
        blockId <- params.hcursor.get[ModifierId]("blockId")
        count   <- params.hcursor.get[Int]("numBlocks")
      } yield new HistoryDebug(hr).averageDelay(blockId, count)) match {
        case Right(Success(delay)) => Map("delay" -> s"$delay milliseconds").asJson
        case Right(Failure(ex))    => throw ex
        case Left(ex)              => throw ex
      }
    }

  /**
   * #### Summary
   * Find the number of blocks forged by addresses held by the node
   *
   * #### Type
   * Local Only -- An unlocked keyfile must be accessible (in local storage) to fulfill this request
   *
   * #### Params
   * | Fields             | Data type | Required / Optional | Description |
   * |--------------------|-----------|---------------------|-------------|
   * | --None specified-- |           |                     |             |
   *
   * @param params input parameters as specified above
   * @param id request identifier
   * @return
   */
  private def myBlocks(params: Json, id: String): Future[Json] =
    (keyManagerRef ? ListKeys)
      .mapTo[Set[Address]]
      .flatMap { myKeys =>
        asyncHistory { hr =>
          val blockNum = new HistoryDebug(hr).count { b =>
            myKeys.map(_.evidence).contains(b.generatorBox.evidence)
          }

          Map(
            "pubkeys" -> myKeys.asJson,
            "count"   -> blockNum.asJson
          ).asJson
        }
      }

  /**
   * #### Summary
   * Find distribution of block generators from all addresses in the chain's history
   *
   * #### Params
   * | Fields             | Data type | Required / Optional | Description |
   * |--------------------|-----------|---------------------|-------------|
   * | --None specified-- |           |                     |             |
   *
   * @param params input parameters as specified above
   * @param id request identifier
   * @return
   */
  private def generators(params: Json, id: String): Future[Json] =
    asyncHistory { hr =>
      new HistoryDebug(hr)
        .forgerDistribution()
        .map(d => d._1.address -> d._2)
        .asJson
    }

  /**
   * #### Summary
   * Return all block ids from a given height and down to a given limit
   *
   * #### Params
   * | Fields             | Data type | Required / Optional | Description |
   * |--------------------|-----------|---------------------|-------------|
   * | --None specified-- |           |                     |             |
   *
   * @param params input parameters as specified above
   * @param id request identifier
   * @return
   */
  private def idsFromHeight(params: Json, id: String): Future[Json] =
    asyncHistory { hr =>
      (for {
        height <- params.hcursor.get[Long]("height")
        limit  <- params.hcursor.get[Int]("limit")
      } yield new HistoryDebug(hr).getIdsFrom(height, limit)) match {
        case Right(ids) =>
          ids match {
            case Some(ids) => ids.asJson
            case None =>
              ErrorResponse(
                new Exception("No block ids found from that block height"),
                500,
                id
              ).toJson
          }
        case Left(e) => throw e
      }
    }
}
