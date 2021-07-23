package co.topl.network

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.util.Timeout
import co.topl.network.NetworkController.ReceivableMessages.{PenalizePeer, RegisterMessageSpecs, SendToNetwork}
import co.topl.network.PeerManager.ReceivableMessages.{AddPeerIfEmpty, RecentlySeenPeers}
import co.topl.network.message._
import co.topl.network.peer.{ConnectedPeer, PeerInfo, PeerSpec, PenaltyType}
import co.topl.settings.{AppContext, AppSettings}
import co.topl.utils.Logging
import shapeless.syntax.typeable._

import scala.concurrent.duration._
import scala.language.postfixOps

/** Responsible for discovering and sharing new peers. */
class PeerSynchronizer(
  networkControllerRef: ActorRef,
  peerManager:          ActorRef,
  settings:             AppSettings,
  appContext:           AppContext
) extends Synchronizer
    with Logging {

  import context.dispatcher

  implicit private val timeout: Timeout = Timeout(settings.network.syncTimeout.getOrElse(5 seconds))

  /** types of remote messages to be handled by this synchronizer */
  protected val peersSpec: PeersSpec = appContext.peerSyncRemoteMessages.peersSpec
  protected val getPeersSpec: GetPeersSpec = appContext.peerSyncRemoteMessages.getPeersSpec

  /** partial functions for identifying local method handlers for the messages above */
  protected val msgHandlers: PartialFunction[(MessageSpec[_], _, ConnectedPeer), Unit] = {
    case (_: PeersSpec, data: PeersData, _) => addNewPeers(data.peers)
    case (_: GetPeersSpec, _, remote)       => gossipPeers(remote)
  }

  override def preStart(): Unit = {

    /** register as a handler for synchronization-specific types of messages */
    networkControllerRef ! RegisterMessageSpecs(appContext.peerSyncRemoteMessages.toSeq, self)

    /** register for application initialization message */
    log.info(s"${Console.YELLOW}PeerSynchronizer transitioning to the operational state${Console.RESET}")
    scheduleGetPeers()
  }

  ////////////////////////////////////////////////////////////////////////////////////
  ////////////////////////////// ACTOR MESSAGE HANDLING //////////////////////////////

  // ----------- CONTEXT ----------- //
  override def receive: Receive =
    processDataFromPeer orElse
    nonsense

  ////////////////////////////////////////////////////////////////////////////////////
  //////////////////////////////// METHOD DEFINITIONS ////////////////////////////////
  /** Schedule a message to gossip about our locally known peers */
  private def scheduleGetPeers(): Unit = {
    val msg = Message[Unit](getPeersSpec, Right(()), None)
    context.system.scheduler.scheduleWithFixedDelay(
      2.seconds,
      settings.network.getPeersInterval,
      networkControllerRef,
      SendToNetwork(msg, SendToRandom)
    )
  }

  /**
   * Handles adding new peers to the peer database if they were previously unknown
   *
   * @param peers sequence of peer specs describing a remote peers details
   */
  private def addNewPeers(peers: Seq[PeerSpec]): Unit =
    if (peers.cast[Seq[PeerSpec]].isDefined) {
      peers.foreach(peerSpec => peerManager ! AddPeerIfEmpty(peerSpec))
    }

  /**
   * Handles gossiping about the locally known peer set to a given remote peer
   *
   * @param remote the remote peer to be informed of our local peers
   */
  private def gossipPeers(remote: ConnectedPeer): Unit =
    (peerManager ? RecentlySeenPeers(settings.network.maxPeerSpecObjects))
      .mapTo[Seq[PeerInfo]]
      .foreach { peers =>
        val msg = Message(peersSpec, Right(PeersData(peers.map(_.peerSpec))), None)
        networkControllerRef ! SendToNetwork(msg, SendToPeer(remote))
      }

  override protected def penalizeMaliciousPeer(peer: ConnectedPeer): Unit =
    networkControllerRef ! PenalizePeer(peer.connectionId.remoteAddress, PenaltyType.PermanentPenalty)

  protected def nonsense: Receive = { case nonsense: Any =>
    log.warn(s"NodeViewSynchronizer: got unexpected input $nonsense from ${sender()}")
  }
}

////////////////////////////////////////////////////////////////////////////////////
/////////////////////////////// COMPANION SINGLETON ////////////////////////////////

object PeerSynchronizer {

  val actorName = "peerSynchronizer"

  case class RemoteMessageHandler(peersSpec: PeersSpec, getPeersSpec: GetPeersSpec) {

    def toSeq: Seq[MessageSpec[_]] = Seq(peersSpec, getPeersSpec)
  }
}

////////////////////////////////////////////////////////////////////////////////////
//////////////////////////////// ACTOR REF HELPER //////////////////////////////////

object PeerSynchronizerRef {

  def apply(
    name:                 String,
    networkControllerRef: ActorRef,
    peerManager:          ActorRef,
    settings:             AppSettings,
    appContext:           AppContext
  )(implicit system:      ActorSystem): ActorRef =
    system.actorOf(props(networkControllerRef, peerManager, settings, appContext), name)

  def props(
    networkControllerRef: ActorRef,
    peerManager:          ActorRef,
    settings:             AppSettings,
    appContext:           AppContext
  ): Props =
    Props(new PeerSynchronizer(networkControllerRef, peerManager, settings, appContext))
}
