package wallet

import akka.actor.{Actor, ActorRef}
import akka.pattern.ask
import akka.util.Timeout
import attestation.Address
import io.circe.{parser, Json}
import io.circe.parser.parse
import io.circe.syntax.EncoderOps
import utils.Logging
import cats.syntax.show._
import keymanager.KeyManager.{ChangeNetwork, GetAllKeyfiles}
import keymanager.networkPrefix
import modifier.{Box, BoxId, Transaction}
import settings.NetworkType

import scala.collection.mutable.{Map => MMap}
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

/**
 * The WalletManager manages the communication between Bifrost and Gjallarhorn.
 * Mainly, the WalletManager receives new blocks from Bifrost in order to updates its wallet boxes.
 * @param keyManagerRef: the actor ref to communicate with the KeyManager.
 */
class WalletManager(keyManagerRef: ActorRef)(implicit ec: ExecutionContext) extends Actor with Logging {

  import WalletManager._

  implicit val timeout: Timeout = 10.seconds

  var connectedToBifrost: Boolean = false

  /** The remote actor ref for Bifrost's WalletConnectionHandler */
  private var bifrostActorRef: Option[ActorRef] = None

  /**
   * Represents the wallet boxes: as a mapping of addresses to boxes (a map of boxId to box)
   * Ex: address1 -> {id1 -> walletBox1, id2 -> walletBox2, ...}, address2 -> {},...
   */
  var walletBoxes: MMap[Address, MMap[BoxId, Box]] = initializeWalletBoxes()

  /** Holds the most recently received transactions from Bifrost */
  var newestTransactions: Option[List[Transaction]] = None

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    log.debug(s"WalletManagerActor: preRestart ${reason.getMessage}")
    log.debug(s"WalletManagerActor message: ${message.getOrElse("")}")
    super.preRestart(reason, message)
  }

  /**
   * Initializes the wallet boxes to empty maps using the keys from the KeyManager
   * @return mapping of current addresses to empty maps
   */
  def initializeWalletBoxes(): MMap[Address, MMap[BoxId, Box]] = {
    val addresses = Await.result(
      (keyManagerRef ? GetAllKeyfiles)
        .mapTo[Map[Address, String]]
        .map(_.keySet),
      10.seconds
    )

    val wallet: MMap[Address, MMap[BoxId, Box]] = MMap.empty
    addresses.map(key => wallet.put(key, MMap.empty))
    wallet
  }

  ////////////////////////////////////////////////////////////////////////////////////
  ////////////////////////////// ACTOR MESSAGE HANDLING //////////////////////////////

  // ----------- CONTEXT
  override def receive: Receive =
    initialization orElse
    operational orElse
    walletManagement orElse
    nonsense

  // ----------- MESSAGE PROCESSING FUNCTIONS
  private def initialization: Receive = {
    case ConnectToBifrost(bifrostActor, networkName) => setUpConnection(bifrostActor, networkName)

    case msg: String => msgHandling(msg)
  }

  private def operational: Receive = {
    case msg: String => msgHandling(msg)
    case GetNewBlock => sender() ! newestTransactions
    case DisconnectFromBifrost =>
      bifrostActorRef match {
        case Some(actor) =>
          //tell bifrost that this wallet is disconnecting (going offline)
          val response: String = Await.result((actor ? "Remote wallet actor stopped").mapTo[String], 10.seconds)
          sender() ! response

          //set the bifrost actor ref to None to put gjallarhorn in offline mode
          bifrostActorRef = None
        case None => log.warn("Already disconnected from Bifrost")
      }
  }

  private def walletManagement: Receive = {
    case UpdateWallet(updatedBoxes) => sender() ! parseAndUpdate(updatedBoxes)
    case GetWallet                  => sender() ! walletBoxes
    case GetConnection              => sender() ! bifrostActorRef
    case NewKey(address)            =>
      //add new key to walletBoxes
      walletBoxes.put(address, MMap.empty)

      //tell bifrost about new key in order to watch new transactions for this key
      bifrostActorRef match {
        case Some(actor) => actor ! s"New key: $address"
        case None        =>
      }
  }

  private def nonsense: Receive = { case nonsense: Any =>
    log.warn(s"Got unexpected input $nonsense from ${sender()}")
  }

  ////////////////////////////////////////////////////////////////////////////////////
  //////////////////////////////// METHOD DEFINITIONS ////////////////////////////////
  /**
   * Handles messages received from Bifrost
   * @param msg - expected to be either: "received new wallet" or "new block added" messages.
   */
  def msgHandling(msg: String): Unit = {
    if (msg.contains("received new wallet from:")) {
      log.info("Bifrost " + msg)
    }
    if (msg.contains("new block added")) {
      parseNewBlock(msg.substring("new block added: ".length))
    }
  }

  /**
   * Function to set up connection between Gjallarhorn and Bifrost
   * Includes updating or initializing the wallet boxes
   * @param bifrost - the actor ref for bifrost's WalletConnectionHandler
   */
  def setUpConnection(bifrost: ActorRef, networkName: String): Unit = {
    bifrostActorRef = Some(bifrost)

    //tell bifrost about this wallet
    bifrost ! s"Remote wallet actor initialized."

    //get network from bifrost and tell keyManager
    val networkResp: String = Await.result((bifrost ? "Which network is bifrost running?").mapTo[String], 10.seconds)
    val bifrostNetwork = networkResp.split("Bifrost is running on").tail.head.replaceAll("\\s", "")
    if (networkName != bifrostNetwork) {
      throw new Exception(
        s"The network ($networkName) that you're trying to connect to is not " +
        s"running on the given chain provider! Bifrost is currently running with the network: $bifrostNetwork"
      )
    }
    (keyManagerRef ? ChangeNetwork(bifrostNetwork)).onComplete {
      case Success(networkResponse: Try[Json]) =>
        networkResponse match {
          case Success(networkJson) =>
            assert(
              NetworkType.fromString(bifrostNetwork).get.netPrefix.toString ==
                (networkJson \\ "newNetworkPrefix").head.asNumber.get.toString
            )
          case Failure(exception) => throw exception
        }
      case Success(_) | Failure(_) => throw new Exception("was not able to change network")
    }

    //re-initialize walletboxes and grab keys with correct network
    walletBoxes = initializeWalletBoxes()
    val addresses = walletBoxes.keySet

    //get balances from bifrost
    if (addresses.nonEmpty) {
      val balances: Json = Await.result(
        (bifrost ? s"My addresses are: $addresses")
          .mapTo[String]
          .map(_.asJson),
        10.seconds
      )
      parseAndUpdate(removeBackslashes(balances))
    } else {
      log.debug(s"${Console.RED}You do not have any keys in your wallet! ${Console.RESET}")
    }
  }

  //------------------------------------------------------------------------------------
  //Methods for parsing balance response

  /**
   * Removes all of the back-slashes from the json string
   * @param response - response from balances request
   * @return - a correctly formed json
   */
  def removeBackslashes(response: Json): Json = {
    val responseString = response
      .toString()
      .replace("\\", "")
      .replace("\"{", "{")
      .replace("}\"", "}")
    parse(responseString) match {
      case Left(f)          => throw f
      case Right(res: Json) => res
    }
  }

  /**
   * Given the balance response from Bifrost, parse the json and update wallet boxes
   * @param json - the balance response from Bifrost
   * @return - the updated walletBoxes
   */
  def parseAndUpdate(json: Json): MMap[Address, MMap[BoxId, Box]] = {
    val addresses: scala.collection.Set[Address] = walletBoxes.keySet
    addresses.foreach { addr =>
      val info: Json = (json \\ addr.toString).head
      var boxesMap: MMap[BoxId, Box] = MMap.empty
      val boxes = info \\ "Boxes"
      if (boxes.nonEmpty) {
        val allBoxes: List[Json] = boxes.head \\ "AssetBox" ++ boxes.head \\ "PolyBox" ++ boxes.head \\ "ArbitBox"
        allBoxes.foreach { bx =>
          val boxMap = parser.decode[List[Box]](bx.toString()) match {
            case Right(boxes) => MMap(boxes.map(b => b.id -> b).toMap.toSeq: _*)
            case Left(ex)     => throw new Exception(s"Unable to parse boxes from balance response: $ex")
          }
          boxesMap = boxesMap ++ boxMap
        }
        walletBoxes(addr) = boxesMap
      }
    }
    walletBoxes
  }

  //------------------------------------------------------------------------------------
  //Methods for parsing new block from Bifrost:

  /**
   * Parses the transaction from the new block received from Bifrost and updates the walletBoxes accordingly
   * @param blockString - the json of the new block in string form.
   */
  def parseNewBlock(blockString: String): Unit =
    //log.info(s"Wallet Manager received new block with transactions: $blockTxs")
    parser.decode[List[Transaction]](blockString) match {
      case Right(transactions) =>
        newestTransactions = Some(transactions)
        val add: MMap[Address, MMap[BoxId, Box]] = MMap.empty
        var idsToRemove: List[BoxId] = List.empty
        transactions.foreach { tx =>
          tx.newBoxes.foreach { newBox =>
            val address: Address = Address(newBox.evidence)(networkPrefix)
            val idToBox: MMap[BoxId, Box] =
              add.get(address) match {
                case Some(boxesMap) => boxesMap
                case None           => MMap.empty
              }
            idToBox.put(newBox.id, newBox)
            add.put(address, idToBox)
          }
          idsToRemove = tx.boxesToRemove match {
            case Some(seq) => seq.toList
            case None      => List.empty
          }
        }
        addAndRemoveBoxes(add, idsToRemove)
      case Left(ex) => throw new Exception(s"Not able to parse transactions: ${ex.show}")
    }

  /**
   * Given the boxes to add and remove, updates the "walletBoxes" accordingly.
   * @param add - boxes to add in the form: address -> {id1 -> box}, {id2 -> box2}
   * @param remove - list of ids for boxes to remove
   */
  def addAndRemoveBoxes(add: MMap[Address, MMap[BoxId, Box]], remove: List[BoxId]): Unit = {
    val idsToBoxes: MMap[BoxId, Box] = walletBoxes.flatMap(box => box._2)
    remove.foreach { id =>
      idsToBoxes.get(id) match {
        case Some(box) =>
          val address: Address = Address(box.evidence)(networkPrefix)
          walletBoxes.get(address).map(boxes => boxes.remove(id))
        case None => throw new Error(s"no box found with id: $id in $idsToBoxes")
      }
    }
    add.foreach { case (address, newBoxes) =>
      walletBoxes.get(address).map(boxes => newBoxes.foreach(box => boxes.put(box._1, box._2)))
    }
  }

}

////////////////////////////////////////////////////////////////////////////////////
/////////////////////////////// COMPANION SINGLETON ////////////////////////////////

/** An instance of the [[WalletManager]] actor. */
object WalletManager {

  val actorName = "WalletManager"

  case class ConnectToBifrost(bifrostActor: ActorRef, networkName: String)

  case object DisconnectFromBifrost

  case class UpdateWallet(updatedBoxes: Json)

  case object GetNewBlock

  case object GetWallet

  case class NewKey(address: Address)

  case object GetConnection

}
