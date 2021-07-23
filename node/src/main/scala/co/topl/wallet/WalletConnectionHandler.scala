package co.topl.wallet

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.dispatch.Dispatchers
import akka.pattern.pipe
import akka.util.Timeout
import cats.data.Validated.Valid
import co.topl.attestation.Address
import co.topl.attestation.AddressCodec.implicits.Base58DataOps
import co.topl.modifier.block.BloomFilter.BloomTopic
import co.topl.modifier.block.{Block, BloomFilter, PersistentNodeViewModifier}
import co.topl.modifier.transaction._
import co.topl.nodeView.NodeViewHolder
import co.topl.settings.{AppContext, AppSettings, RPCApiSettings}
import co.topl.utils.IdiomaticScalaTransition.implicits.toValidatedOps
import co.topl.utils.Logging
import co.topl.utils.NetworkType.NetworkPrefix
import co.topl.utils.StringDataTypes.Base58Data
import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax._

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success}

/**
 * Manages the communication between Bifrost and a running wallet.
 * @param settings - the current AppSettings from Bifrost.
 * @param ec - the execution context used for futures.
 */
class WalletConnectionHandler[
  PMOD <: PersistentNodeViewModifier
](settings:      RPCApiSettings, appContext: AppContext)(implicit
  networkPrefix: NetworkPrefix
) extends Actor
    with Logging {

  import WalletConnectionHandler._
  import context.dispatcher

  implicit val timeout: Timeout = 10.seconds
  implicit val actorSystem: ActorSystem = context.system

  var remoteWalletActor: Option[ActorRef] = None
  var remoteWalletAddresses: Option[Set[Address]] = None

  override def preStart(): Unit =
    context.system.eventStream.subscribe(self, classOf[NodeViewHolder.Events.SemanticallySuccessfulModifier[PMOD]])

  private val apiServiceHandlers =
    PartialFunction.empty[(String, Vector[Json], String), Future[Json]]
//    NodeViewApiEndpoint(settings, appContext, nodeViewHolderRef).handlers orElse
//      TransactionApiEndpoint(settings, appContext, nodeViewHolderRef).handlers

  ////////////////////////////////////////////////////////////////////////////////////
  ////////////////////////////// ACTOR MESSAGE HANDLING //////////////////////////////

  override def receive: Receive = {

    case msg: String => handleMsgFromRemote(msg)

    case GetRemoteWalletRef => sender() ! remoteWalletActor

    case NodeViewHolder.Events.SemanticallySuccessfulModifier(block: Block) => handleNewBlock(block)

  }

  ////////////////////////////////////////////////////////////////////////////////////
  //////////////////////////////// METHOD DEFINITIONS ////////////////////////////////

  private def handleNewBlock(block: Block): Unit =
    remoteWalletAddresses match {
      case Some(addresses) =>
        log.info(s"Received new block ${block.id}, parsing for transactions for addresses: $addresses")
        remoteWalletActor.map {
          case actorRef: ActorRef if anyRemoteAddressInBloom(block.bloomFilter) =>
            parseBlockForKeys(block).map(txJson => actorRef ! s"new block added: $txJson")
          case actor: ActorRef => // no wallet addresses in new block
        }

      case _ => // Do nothing since there are no addresses registered
    }

  /**
   * @param msg - the message received from Gjallarhorn
   */
  private def handleMsgFromRemote(msg: String): Unit = {
    if (msg.contains("Remote wallet actor initialized")) {
      remoteWalletActor = Some(sender())
      log.info(s"A remote wallet actor has started up: ${sender()}")
      sender() ! s"received new wallet from: ${sender()}. "
    }

    if (msg == "Which network is bifrost running?") {
      sender() ! s"Bifrost is running on ${appContext.networkType.verboseName}"
    }

    if (msg.contains("My addresses are")) {
      val keys = parseKeys(msg.substring("My addresses are: ".length))
      keys match {
        case Some(addrs) =>
          remoteWalletAddresses = keys
          sendRequestApi(balanceRequest(addrs), sender())
        case None => log.error(s"${Console.RED}No keys found!${Console.RESET}")
      }
    }

    if (msg == "Remote wallet actor stopped") {
      remoteWalletActor = None
      remoteWalletAddresses = None
      log.info(s"The remote wallet ${sender()} has been removed from the WalletConnectionHandler in Bifrost")
      sender() ! s"The remote wallet ${sender()} has been removed from the WalletConnectionHandler in Bifrost"
    }

    if (msg.contains("request from gjallarhorn:")) {
      val txString: String = msg.substring("request from gjallarhorn: ".length)
      log.info("Wallet Connection handler received a request from gjallarhorn: " + txString)
      val walletActorRef: ActorRef = sender()
      sendRequestApi(txString, walletActorRef)
    }

    if (msg.contains("New key:")) {
      val addr: String = msg.substring("New key: ".length)
      val decodedAddress = Base58Data.unsafe(addr).decodeAddress.getOrThrow()
      remoteWalletAddresses match {
        case Some(addresses) =>
          val newAddresses: Set[Address] = addresses + decodedAddress
          remoteWalletAddresses = Some(newAddresses)
        case None => remoteWalletAddresses = Some(Set(decodedAddress))
      }
    }
  }

  private def anyRemoteAddressInBloom(bf: BloomFilter): Boolean =
    remoteWalletAddresses match {
      case Some(addresses) => addresses.map(addr => bf.contains(BloomTopic(addr.bytes))).reduce(_ || _)
      case _               => false
    }

  /**
   * Parses a block, looking for the addresses from the remote wallet.
   * @param block - a new block that was just added.
   * @return - returns json of the transactions from the new block if it contains addresses from the remote wallet.
   *         Otherwise, returns None.
   */
  private def parseBlockForKeys(block: Block): Option[Json] = remoteWalletAddresses map { keys =>
    val txs: Seq[Transaction.TX] = block.transactions.filter {
      case tx: TransferTransaction[_, _] if keys.toSeq.intersect(tx.to.map(_._1)).nonEmpty => true
      case _                                                                               => false
    }

    txs.asJson
  }

  /**
   * Handles requests sent from a remote Gjallarhorn instance and sends them to the appropriate API methods
   * @param req parameters to fulfill the request
   * @param actorRef the actor to respond to
   */
  private def processRequest(req: (String, Vector[Json], String), actorRef: ActorRef): Unit =
    if (apiServiceHandlers.isDefinedAt(req)) {
      apiServiceHandlers
        .apply(req)
        .transformWith {
          case Success(resp)      => Future(resp.noSpaces)
          case Failure(exception) => Future("Error: " + exception)
        }
        .pipeTo(actorRef)

    } else throw new Exception("Service handler not found for method: " + req._1)

  /**
   * Parse incoming request parameters and target the service with the appropriate handler function
   * @param params function parameters needed to process the requested message type
   * @param walletRef the actor reference of the Gjallarhorn instance
   */
  private def sendRequestApi(params: String, walletRef: ActorRef): Unit =
    (for {
      tx     <- parse(params)
      id     <- (tx \\ "id").head.as[String]
      params <- (tx \\ "params").head.as[Vector[Json]]
      method <- (tx \\ "method").head.as[String]
    } yield {
      require(params.size <= 1, s"size of params is ${params.size}")
      processRequest((method, params, id), walletRef)
    }) match {
      case Right(tx)   => //
      case Left(error) => throw new Exception(s"error: $error")
    }

  /**
   * Parse the set of keys registered by the Gjallarhorn actor
   * @param keys a stringified set of PublicKeyPropositions to monitor for changes
   */
  private def parseKeys(keys: String): Option[Set[Address]] =
    if (keys == "Set()") {
      log.info("Remote wallet has no keys!")
      None
    } else {
      val keysArr: Array[String] = keys.substring("Set(".length, keys.length - 1).split(",")
      val keystrings = keysArr.map(key => key.trim).toSet

      keystrings.map(Base58Data.validated).foldLeft[Option[Set[Address]]](Some(Set[Address]())) {
        case (Some(keys), Valid(key)) => Some(keys + key.decodeAddress.getOrThrow())
        case _                        => None
      }
    }

  private def balanceRequest(addresses: Set[Address]): String = {
    val params: Json = Map("addresses" -> addresses.map(_.asJson).toList).asJson
    s"""
       |{
       |   "jsonrpc": "2.0",
       |   "id": "2",
       |   "method": "topl_balances",
       |   "params": [$params]
       |}
     """.stripMargin
  }
}

////////////////////////////////////////////////////////////////////////////////////
/////////////////////////////// COMPANION SINGLETON ////////////////////////////////

object WalletConnectionHandler {
  val actorName = "walletConnectionHandler"

  case object GetRemoteWalletRef
}

////////////////////////////////////////////////////////////////////////////////////
//////////////////////////////// ACTOR REF HELPER //////////////////////////////////

object WalletConnectionHandlerRef {

  def props[
    PMOD <: PersistentNodeViewModifier
  ](settings: AppSettings, appContext: AppContext): Props =
    Props(
      new WalletConnectionHandler[PMOD](settings.rpcApi, appContext)(
        appContext.networkType.netPrefix
      )
    )
      .withDispatcher(Dispatchers.DefaultBlockingDispatcherId)

  def apply[
    PMOD <: PersistentNodeViewModifier
  ](name:   String, settings: AppSettings, appContext: AppContext)(implicit
    system: ActorSystem
  ): ActorRef =
    system.actorOf(props[PMOD](settings, appContext), name)
}
