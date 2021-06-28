package co.topl.http.api.endpoints

import akka.actor.{ActorRef, ActorSystem}
import akka.dispatch.Dispatchers
import co.topl.attestation.Address
import co.topl.http.api.{ApiEndpointWithView, Namespace, ToplNamespace}
import co.topl.modifier.ModifierId
import co.topl.modifier.box._
import co.topl.nodeView.history.History
import co.topl.nodeView.mempool.MemPool
import co.topl.nodeView.state.State
import co.topl.settings.{AppContext, RPCApiSettings}
import co.topl.utils.Int128
import co.topl.utils.NetworkType.NetworkPrefix
import io.circe.Json
import io.circe.syntax._

import scala.concurrent.{ExecutionContext, Future}

case class NodeViewApiEndpoint(
  override val settings: RPCApiSettings,
  appContext:            AppContext,
  nodeViewHolderRef:     ActorRef
)(implicit val system:   ActorSystem)
    extends ApiEndpointWithView {

  import co.topl.utils.codecs.Int128Codec._

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
  val namespace: Namespace = ToplNamespace

  // partial function for identifying local method handlers exposed by the api
  val handlers: PartialFunction[(String, Vector[Json], String), Future[Json]] = {
    case (method, params, id) if method == s"${namespace.name}_head"            => getBestBlock(params.head, id)
    case (method, params, id) if method == s"${namespace.name}_balances"        => balances(params.head, id)
    case (method, params, id) if method == s"${namespace.name}_transactionById" => transactionById(params.head, id)
    case (method, params, id) if method == s"${namespace.name}_blockById"       => blockById(params.head, id)
    case (method, params, id) if method == s"${namespace.name}_blockByHeight"   => blockByHeight(params.head, id)
    case (method, params, id) if method == s"${namespace.name}_mempool"         => mempool(params.head, id)
    case (method, params, id) if method == s"${namespace.name}_transactionFromMempool" =>
      transactionFromMempool(params.head, id)
    case (method, params, id) if method == s"${namespace.name}_info" => info(params.head, id)
  }

  /**
   * #### Summary
   * Retrieve the best block
   *
   * #### Description
   * Find information about the current state of the chain including height, score, bestBlockId, etc
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
  private def getBestBlock(params: Json, id: String): Future[Json] =
    asyncHistory { hr =>
      Map(
        "height"      -> hr.height.toString.asJson,
        "score"       -> hr.score.asJson,
        "bestBlockId" -> hr.bestBlockId.toString.asJson,
        "bestBlock"   -> hr.bestBlock.asJson
      ).asJson
    }

  /**
   * #### Summary
   * Lookup balances
   *
   * #### Type
   * Remote -- Transaction must be used in conjunction with an external key manager service.
   *
   * #### Description
   * Check balances of specified keys.
   *
   * #### Notes
   * - Requires the Token Box Registry to be active
   *
   * #### Params
   * | Fields    | Data type | Required / Optional | Description                                  |
   * |-----------|-----------|---------------------|----------------------------------------------|
   * | addresses | [String]  | Required            | Addresses whose balances are to be retrieved |
   *
   * @param params input parameters as specified above
   * @param id     request identifier
   * @return
   */
  private def balances(params: Json, id: String): Future[Json] =
    asyncState { hr =>
      // parse arguments from the request
      (for {
        addresses <- params.hcursor.get[Seq[Address]]("addresses")
      } yield {
        // ensure we have the state being asked about
        checkAddress(addresses, hr)

        val boxes: Map[Address, Map[String, Seq[TokenBox[TokenValueHolder]]]] =
          addresses.map { k =>
            val orderedBoxes = hr.getTokenBoxes(k) match {
              case Some(boxes) => boxes.groupBy[String](Box.identifier(_).typeString)
              case _           => Map[String, Seq[TokenBox[TokenValueHolder]]]()
            }
            k -> orderedBoxes
          }.toMap

        val balances: Map[Address, Map[String, Int128]] =
          boxes.map { case (addr, assets) =>
            addr -> assets.map { case (boxType, boxes) =>
              (boxType, boxes.map(_.value.quantity).sum)
            }
          }

        boxes.map { case (addr, boxes) =>
          addr -> Map(
            "Balances" -> Map(
              "Polys"  -> balances(addr).getOrElse(PolyBox.typeString, Int128(0)),
              "Arbits" -> balances(addr).getOrElse(ArbitBox.typeString, Int128(0))
            ).asJson,
            "Boxes" -> boxes.map(b => b._1 -> b._2.asJson).asJson
          )
        }.asJson
      }) match {
        case Right(json) => json
        case Left(ex)    => throw ex
      }
    }

  /**
   * #### Summary
   * Get the first 100 transactions in the mempool (sorted by fee amount)
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
  private def mempool(params: Json, id: String): Future[Json] =
    asyncMempool(_.take(100)(-_.dateAdded).map(_.tx).asJson)

  /**
   * #### Summary
   * Lookup a transaction by its id
   *
   * #### Params
   * | Fields        | Data type | Required / Optional | Description                     |
   * |---------------|-----------|---------------------|---------------------------------|
   * | transactionId | String    | Required            | Base58 encoded transaction hash |
   *
   * @param params input parameters as specified above
   * @param id request identifier
   * @return
   */
  private def transactionById(params: Json, id: String): Future[Json] =
    asyncHistory { hr =>
      (for {
        transactionId <- params.hcursor.get[ModifierId]("transactionId")
      } yield hr.transactionById(transactionId)) match {
        case Right(Some((tx, blockId, height))) =>
          tx.asJson.deepMerge {
            Map(
              "blockNumber" -> height.toString,
              "blockId"     -> blockId.toString
            ).asJson
          }

        case Right(None) => throw new Exception(s"Unable to find confirmed transaction")
        case Left(ex)    => throw ex
      }
    }

  /**
   * #### Summary
   * Lookup a transaction in the mempool by its id
   *
   *  #### Params
   * | Fields        | Data type | Required / Optional | Description                     |
   * |---------------|-----------|---------------------|---------------------------------|
   * | transactionId | String    | Required            | Base58 encoded transaction hash |
   *
   * @param params input parameters as specified above
   * @param id request identifier
   * @return
   */
  private def transactionFromMempool(params: Json, id: String): Future[Json] =
    asyncMempool { mr =>
      (for {
        transactionId <- params.hcursor.get[ModifierId]("transactionId")
      } yield mr.modifierById(transactionId)) match {
        case Right(Some(tx)) => tx.asJson
        case Right(None)     => throw new Exception("Unable to retrieve transaction")
        case Left(ex)        => throw ex
      }
    }

  /**
   * #### Summary
   * Lookup a block by its id
   *
   * #### Params
   * | Fields  | Data type | Required / Optional | Description                     |
   * |---------|-----------|---------------------|---------------------------------|
   * | blockId | String    | Required            | Base58 encoded transaction hash |
   *
   * @param params input parameters as specified above
   * @param id request identifier
   * @return
   */
  private def blockById(params: Json, id: String): Future[Json] =
    asyncHistory { hr =>
      (for {
        blockId <- params.hcursor.get[ModifierId]("blockId")
      } yield hr.modifierById(blockId)) match {
        case Right(Some(block)) => block.asJson
        case Right(None)        => throw new Exception("The requested block could not be found")
        case Left(ex)           => throw ex
      }
    }

  /**
   * #### Summary
   * Lookup a block by its height
   *
   * #### Params
   * | Fields | Data type | Required / Optional | Description                               |
   * |--------|-----------|---------------------|-------------------------------------------|
   * | height | Number    | Required            | Height to retrieve on the canonical chain |
   *
   * @param params input parameters as specified above
   * @param id request identifier
   * @return
   */
  private def blockByHeight(params: Json, id: String): Future[Json] =
    asyncHistory { hr =>
      (for {
        height <- params.hcursor.get[Long]("height")
      } yield hr.modifierByHeight(height)) match {
        case Right(Some(block)) => block.asJson
        case Right(None)        => throw new Exception("The requested block could not be found")
        case Left(ex)           => throw ex
      }
    }

  private def info(params: Json, id: String): Future[Json] =
    asyncHistory { hr =>
      Map(
        "network" -> appContext.networkType.toString,
        "nodeAddress" -> {
          appContext.externalNodeAddress match {
            case Some(address) => address.toString
            case None          => "N/A"
          }
        },
        "version" -> appContext.settings.application.version.toString
      ).asJson
    }
}
