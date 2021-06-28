package co.topl.http.api.endpoints

import akka.actor.{ActorRef, ActorSystem}
import akka.dispatch.Dispatchers
import co.topl.attestation.{Address, Proposition, PublicKeyPropositionCurve25519, ThresholdPropositionCurve25519}
import co.topl.http.api.{ApiEndpointWithView, Namespace, ToplNamespace}
import co.topl.modifier.box.{AssetValue, SimpleValue}
import co.topl.modifier.transaction.{ArbitTransfer, AssetTransfer, PolyTransfer, Transaction}
import co.topl.nodeView.NodeViewHolder.ReceivableMessages.LocallyGeneratedTransaction
import co.topl.nodeView.history.History
import co.topl.nodeView.mempool.MemPool
import co.topl.nodeView.state.State
import co.topl.settings.{AppContext, RPCApiSettings}
import co.topl.utils.Int128
import co.topl.utils.NetworkType.NetworkPrefix
import co.topl.utils.codecs.Int128Codec
import io.circe.Json
import io.circe.syntax._
import scorex.util.encode.Base58

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/**
 * Class route for managing assets using JSON-RPC requests
 *
 * @param nodeViewHolderRef actor reference to inform of new transactions
 * @param settings the settings for HTTP REST API
 */
case class TransactionApiEndpoint(
  settings:          RPCApiSettings,
  appContext:        AppContext,
  nodeViewHolderRef: ActorRef
)(implicit system:   ActorSystem)
    extends ApiEndpointWithView {

  import Int128Codec._

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
    case (method, params, id) if method == s"${namespace.name}_rawAssetTransfer" => rawAssetTransfer(params.head, id)
    case (method, params, id) if method == s"${namespace.name}_rawArbitTransfer" => rawArbitTransfer(params.head, id)
    case (method, params, id) if method == s"${namespace.name}_rawPolyTransfer"  => rawPolyTransfer(params.head, id)
    case (method, params, id) if method == s"${namespace.name}_broadcastTx"      => broadcastTx(params.head, id)
  }

  /**
   * #### Summary
   * Transfer Assets from an account to a specified recipient
   *
   * #### Type
   * Remote -- Transaction must be used in conjunction with an external key manager service.
   *
   * #### Description
   * Default behavior of the wallet is to find the first unlocked address which hold the targetted Asset.
   * The protocols default behavior is to combine multiple UTXOs of the same type into a single UTXO when it can.
   *
   * #### Notes
   * - `AssetCode` in `AssetValue` can be generated using `util_generateAssetCode`
   * - `fee` and `quantity` in `AssetValue` need to be strings, they will be converted into Int128 which can go up to
   * 178 undecillion(2^127-1)
   *
   * #### Params
   * | Fields               | Data type              | Required / Optional | Description                                                                       |
   * |----------------------|------------------------|---------------------|-----------------------------------------------------------------------------------|
   * | propositionType      | String                 | Required            | Type of proposition, eg., PublicKeyCurve25519, ThresholdCurve25519                |
   * | recipients           | [[String, AssetValue]] | Required            | Array of addresses and assetValues for the transfer recipients(check table below) |
   * | sender               | [String]               | Required            | Array of addresses from which Assets should be sent                               |
   * | changeAddress        | String                 | Required            | Address for recipient of unspent Polys                                            |
   * | consolidationAddress | String                 | Optional            | Address for recipient of unspent Assets                                           |
   * | fee                  | String                 | Required            | Fee for the transfer. Minting AssetTransfer requires fee to be greater than 0     |
   * | minting              | Boolean                | Required            | If this is a minting AssetTransfer or not                                         |
   * | data                 | String                 | Optional            | Data string which can be associated with this transaction(may be empty)           |
   *
   * ###### AssetValue
   * | Fields       | Data type | Required / Optional | Description                                                                                     |
   * |--------------|-----------|---------------------|-------------------------------------------------------------------------------------------------|
   * | type         | String    | Required            | Type of transfer, should be "Asset" for AssetTransfer                                           |
   * | quantity     | String    | Required            | Number of tokens in String                                                                      |
   * | assetCode    | String    | Required            | Unique identifier for user issued Assets, generated from version, issuer address, and shortName |
   * | securityRoot | String    | Optional            | Optional 32 byte commitment to instance of the AssetBox                                         |
   * | metadata     | String    | Optional            | String must be less than 128 Latin-1 encoded characters                                                   |
   *
   * @param params input parameter as specified above
   * @param id request identifier
   * @return
   */
  private def rawAssetTransfer(implicit params: Json, id: String): Future[Json] =
    asyncState { stateReader =>
      val p = params.hcursor
      // parse arguments from the request
      (for {
        propType          <- p.get[String]("propositionType")
        recipients        <- p.get[IndexedSeq[(Address, AssetValue)]]("recipients")
        sender            <- p.get[IndexedSeq[Address]]("sender")
        changeAddr        <- p.get[Address]("changeAddress")
        consolidationAddr <- p.get[Address]("consolidationAddress")
        fee               <- p.get[Int128]("fee")(Int128Codec.jsonDecoder)
        minting           <- p.get[Boolean]("minting")
        data              <- p.get[Option[String]]("data")
      } yield {

        // check that the state is available
        checkAddress(sender, stateReader)

        // construct the transaction
        propType match {
          case PublicKeyPropositionCurve25519.`typeString` =>
            AssetTransfer
              .createRaw[PublicKeyPropositionCurve25519](
                stateReader,
                recipients,
                sender,
                changeAddr,
                consolidationAddr,
                fee,
                data,
                minting
              )

          case ThresholdPropositionCurve25519.`typeString` =>
            AssetTransfer
              .createRaw[ThresholdPropositionCurve25519](
                stateReader,
                recipients,
                sender,
                changeAddr,
                consolidationAddr,
                fee,
                data,
                minting
              )
        }
      }) match {
        case Right(Success(tx)) =>
          // validate and update nodeView with new TX
          tx.rawValidate.toEither match {
            case Right(_) =>
              Map(
                "rawTx"         -> tx.asJson,
                "messageToSign" -> Base58.encode(tx.messageToSign).asJson
              ).asJson

            case Left(e) =>
              throw new Exception(s"Could not validate transaction: ${e.head}")
          }

        case Right(Failure(ex)) => throw new Exception(s"Failed to create raw AssetTransfer with error: $ex")
        case Left(ex)           => throw ex
      }
    }

  /**
   * #### Summary
   * Transfer Polys from an account to a specified recipient.
   *
   * #### Type
   * Remote -- Transaction must be used in conjunction with an external key manager service.
   *
   * #### Description
   * Default behavior of the wallet is to find the first unlocked address which hold Polys.
   * The protocols default behavior is to combine multiple UTXOs of the same type into a single UTXO when it can.
   *
   * #### Notes
   * - `fee` and Poly amounts in `recipients` need to be strings, they will be converted into Int128 which can go up
   * to 178 undecillion(2^127-1)
   *
   * #### Params
   * | Fields          | Data type          | Required / Optional | Description                                                              |
   * |-----------------|--------------------|---------------------|--------------------------------------------------------------------------|
   * | propositionType | String             | Required            | Type of proposition, eg., PublicKeyCurve25519, ThresholdCurve25519       |
   * | recipients      | [[String, String]] | Required            | Array of addresses and Poly amounts for the corresponding recipients     |
   * | sender          | [String]           | Required            | Array of addresses from which Polys should be sent                       |
   * | changeAddress   | String             | Required            | Address for recipient of unspent Polys                                   |
   * | fee             | String             | Required            | Fee for the transfer                                                     |
   * | data            | String             | Optional            | Data string which can be associated with this transaction (may be empty) |
   *
   * @param params input parameters as specified above
   * @param id     request identifier
   * @return
   */
  private def rawPolyTransfer(implicit params: Json, id: String): Future[Json] =
    asyncState { stateReader =>
      val p = params.hcursor

      // parse arguments from the request
      (for {
        propType   <- p.get[String]("propositionType")
        recipients <- p.get[IndexedSeq[(Address, Int128)]]("recipients")
        sender     <- p.get[IndexedSeq[Address]]("sender")
        changeAddr <- p.get[Address]("changeAddress")
        fee        <- p.get[Int128]("fee")(Int128Codec.jsonDecoder)
        data       <- p.get[Option[String]]("data")
      } yield {

        // check that the state is available
        checkAddress(sender, stateReader)

        // convert to simple value type
        val to = recipients.map(r => r._1 -> SimpleValue(r._2))

        // construct the transaction
        propType match {
          case PublicKeyPropositionCurve25519.`typeString` =>
            PolyTransfer
              .createRaw[PublicKeyPropositionCurve25519](stateReader, to, sender, changeAddr, fee, data)

          case ThresholdPropositionCurve25519.`typeString` =>
            PolyTransfer
              .createRaw[ThresholdPropositionCurve25519](stateReader, to, sender, changeAddr, fee, data)
        }
      }) match {
        case Right(Success(tx)) =>
          // validate and update nodeView with new TX
          tx.rawValidate.toEither match {
            case Right(_) =>
              Map(
                "rawTx"         -> tx.asJson,
                "messageToSign" -> Base58.encode(tx.messageToSign).asJson
              ).asJson

            case Left(e) =>
              throw new Exception(s"Could not validate transaction: ${e.head}")
          }

        case Right(Failure(ex)) => throw new Exception(s"Failed to create raw PolyTransfer with error: $ex")
        case Left(ex)           => throw ex
      }
    }

  /**
   * #### Summary
   * Transfer Arbits from an account to a specified recipient.
   *
   * #### Type
   * Remote -- Transaction must be used in conjunction with an external key manager service.
   *
   * #### Description
   * Default behavior of the wallet is to find the first unlocked address which hold Arbits.
   * The protocols default behavior is to combine multiple UTXOs of the same type into a single UTXO when it can.
   *
   * #### Notes
   * - `fee` and Arbit amounts in `recipients` need to be strings, they will be converted into Int128 which can go up
   * to 178 undecillion(2^127-1)
   *
   * #### Params
   * | Fields               | Data type          | Required / Optional | Description                                                              |
   * |----------------------|--------------------|---------------------|--------------------------------------------------------------------------|
   * | propositionType      | String             | Required            | Type of proposition, eg., PublicKeyCurve25519, ThresholdCurve25519       |
   * | recipients           | [[String, String]] | Required            | Array of addresses and Arbit amounts for the corresponding recipients    |
   * | sender               | [String]           | Required            | Array of addresses from which Arbits should be sent                      |
   * | changeAddress        | String             | Required            | Address for recipient of changes                                         |
   * | consolidationAddress | String             | Optional            | Address for recipient of unspent Arbits                                  |
   * | fee                  | String             | Required            | Fee for the transfer                                                     |
   * | data                 | String             | Optional            | Data string which can be associated with this transaction (may be empty) |
   *
   * @param params input parameters as specified above
   * @param id     request identifier
   * @return
   */
  private def rawArbitTransfer(implicit params: Json, id: String): Future[Json] =
    asyncState { stateReader =>
      val p = params.hcursor

      // parse arguments from the request
      (for {
        propType          <- p.get[String]("propositionType")
        recipients        <- p.get[IndexedSeq[(Address, Int128)]]("recipients")
        sender            <- p.get[IndexedSeq[Address]]("sender")
        changeAddr        <- p.get[Address]("changeAddress")
        consolidationAddr <- p.get[Address]("consolidationAddress")
        fee               <- p.get[Int128]("fee")(Int128Codec.jsonDecoder)
        data              <- p.get[Option[String]]("data")
      } yield {

        // check that the state is available
        checkAddress(sender, stateReader)

        // convert to simple value type
        val to = recipients.map(r => r._1 -> SimpleValue(r._2))

        // construct the transaction
        propType match {
          case PublicKeyPropositionCurve25519.`typeString` =>
            ArbitTransfer
              .createRaw[PublicKeyPropositionCurve25519](
                stateReader,
                to,
                sender,
                changeAddr,
                consolidationAddr,
                fee,
                data
              )

          case ThresholdPropositionCurve25519.`typeString` =>
            ArbitTransfer
              .createRaw[ThresholdPropositionCurve25519](
                stateReader,
                to,
                sender,
                changeAddr,
                consolidationAddr,
                fee,
                data
              )
        }
      }) match {
        case Right(Success(tx)) =>
          // validate and update nodeView with new TX
          tx.rawValidate.toEither match {
            case Right(_) =>
              Map(
                "rawTx"         -> tx.asJson,
                "messageToSign" -> Base58.encode(tx.messageToSign).asJson
              ).asJson

            case Left(e) =>
              throw new Exception(s"Could not validate transaction: ${e.head}")
          }

        case Right(Failure(ex)) => throw new Exception(s"Failed to create raw ArbitTransfer with error: $ex")
        case Left(ex)           => throw ex
      }
    }

  /**
   * #### Summary
   * Broadcast transaction
   *
   * #### Type
   * Remote -- Route must be used in conjunction with an external key manager service.
   *
   * #### Description
   * Place specified signed transaction into the mempool and broadcast to other nodes
   *
   * #### Notes
   * - Currently only enabled for `AssetCreation` and `AssetTransfer` transactions
   *
   * #### Params
   * | Fields | Data type | Required / Optional | Description                                                                   |
   * |--------|-----------|---------------------|-------------------------------------------------------------------------------|
   * | tx     | object    | Required            | A full formatted transaction JSON object (prototype transaction + signatures) |
   *
   * @param params input parameters as specified above
   * @param id     request identifier
   * @return
   */
  private def broadcastTx(params: Json, id: String): Future[Json] = Future {
    (for {
      tx <- params.hcursor.get[Transaction[_, _ <: Proposition]]("tx")
    } yield tx.syntacticValidate.map { _ =>
      nodeViewHolderRef ! LocallyGeneratedTransaction(tx)
      tx.asJson
    }.toEither) match {
      case Right(Right(json)) => json
      case Right(Left(ex))    => throw new Exception(ex.head.toString)
      case Left(ex)           => throw ex
    }
  }
}
