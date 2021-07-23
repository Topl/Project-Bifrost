package co.topl.network

import akka.actor.{Actor, ActorRef, Props}
import co.topl.network.NetworkController.ReceivableMessages._
import co.topl.network.peer.{InMemoryPeerDatabase, PeerInfo, PeerSpec, PenaltyType}
import co.topl.settings.{AppContext, AppSettings}
import co.topl.utils.{Logging, NetworkUtils, TimeProvider}

import java.net.{InetAddress, InetSocketAddress}
import scala.util.Random

/**
 * Peer manager takes care of peers connected and in process, and also chooses a random peer to connect
 * Must be singleton
 */
class PeerManager(settings: AppSettings, appContext: AppContext)(implicit timeProvider: TimeProvider)
    extends Actor
    with Logging {

  /** Import the types of messages this actor can RECEIVE */
  import PeerManager.ReceivableMessages._

  private val peerDatabase = new InMemoryPeerDatabase(settings.network, timeProvider)

  override def preStart(): Unit =
    /** register for application initialization message */
    log.info(s"${Console.YELLOW}PeerManager transitioning to the operational state${Console.RESET}")

  /** fill database with peers from config file if empty */
  if (peerDatabase.isEmpty) {
    settings.network.knownPeers.foreach { address =>
      if (!isSelf(address)) peerDatabase.addOrUpdateKnownPeer(PeerInfo.fromAddress(address))
    }
  }

////////////////////////////////////////////////////////////////////////////////////
////////////////////////////// ACTOR MESSAGE HANDLING //////////////////////////////

  // ----------- CONTEXT ----------- //
  override def receive: Receive =
    peersManagement orElse
    nonsense

  private def peersManagement: Receive = {

    case ConfirmConnection(connectionId, handlerRef) =>
      log.info(s"Connection confirmation request: $connectionId")
      if (peerDatabase.isBlacklisted(connectionId.remoteAddress)) sender() ! ConnectionDenied(connectionId, handlerRef)
      else sender() ! ConnectionConfirmed(connectionId, handlerRef)

    case AddOrUpdatePeer(peerInfo) =>
      /** We have connected to a peer and got peerInfo from them */
      if (!isSelf(peerInfo.peerSpec)) peerDatabase.addOrUpdateKnownPeer(peerInfo)

    case Penalize(peer, penaltyType) =>
      log.info(s"$peer penalized, penalty: $penaltyType")
      if (peerDatabase.penalize(peer, penaltyType)) {
        log.info(s"$peer blacklisted")
        peerDatabase.addToBlacklist(peer, penaltyType)
        sender() ! Blacklisted(peer)
      }

    case AddPeerIfEmpty(peerSpec) =>
      /** We have received peer data from other peers. It might be modified and should not affect existing data if any */
      if (peerSpec.address.forall(a => peerDatabase.get(a).isEmpty) && !isSelf(peerSpec)) {
        val peerInfo: PeerInfo = PeerInfo(peerSpec, 0, None)
        log.info(s"New discovered peer: $peerInfo")
        peerDatabase.addOrUpdateKnownPeer(peerInfo)
      }

    case PeerSeen(peerInfo) => peerDatabase.peerSeen(peerInfo)

    case RemovePeer(address) =>
      log.info(s"$address removed")
      peerDatabase.remove(address)

    case get: GetPeers[_] =>
      sender() ! get.choose(peerDatabase.knownPeers, peerDatabase.blacklistedPeers, appContext)
  }

  private def nonsense: Receive = { case nonsense: Any =>
    log.warn(s"PeerManager: got unexpected input $nonsense from ${sender()}")
  }

////////////////////////////////////////////////////////////////////////////////////
//////////////////////////////// METHOD DEFINITIONS ////////////////////////////////

  /** Given a peer's address, returns `true` if the peer is the same is this node. */
  private def isSelf(peerAddress: InetSocketAddress): Boolean =
    NetworkUtils.isSelf(peerAddress, settings.network.bindAddress, appContext.externalNodeAddress)

  private def isSelf(peerSpec: PeerSpec): Boolean =
    peerSpec.declaredAddress.exists(isSelf) || peerSpec.localAddressOpt.exists(isSelf)

}

////////////////////////////////////////////////////////////////////////////////////
/////////////////////////////// COMPANION SINGLETON ////////////////////////////////

object PeerManager {

  val actorName = "peerManager"

  object ReceivableMessages {

    case class ConfirmConnection(connectionId: ConnectionId, handlerRef: ActorRef)

    case class Penalize(remote: InetSocketAddress, penaltyType: PenaltyType)

    case class Blacklisted(remote: InetSocketAddress)

    /** peerListOperations messages */
    case class AddOrUpdatePeer(data: PeerInfo)

    case class PeerSeen(peerInfo: PeerInfo)

    case class AddPeerIfEmpty(data: PeerSpec)

    case class RemovePeer(address: InetSocketAddress)

    /** Message to get peers from known peers map filtered by `choose` function */
    trait GetPeers[T] {

      def choose(
        knownPeers:       Map[InetSocketAddress, PeerInfo],
        blacklistedPeers: Seq[InetAddress],
        appContext:       AppContext
      ): T
    }

    /**
     * Choose at most `howMany` random peers, which were connected to our peer and weren't blacklisted.
     * Used in peer propagation: peers chosen are recommended to a peer asking our node about more peers.
     */
    case class RecentlySeenPeers(howMany: Int) extends GetPeers[Seq[PeerInfo]] {

      override def choose(
        knownPeers:       Map[InetSocketAddress, PeerInfo],
        blacklistedPeers: Seq[InetAddress],
        sc:               AppContext
      ): Seq[PeerInfo] = {
        val recentlySeenNonBlacklisted = knownPeers.values.toSeq
          .filter { p =>
            (p.connectionType.isDefined || p.lastSeen > 0) &&
            !blacklistedPeers.exists(ip => p.peerSpec.declaredAddress.exists(_.getAddress == ip))
          }
        Random.shuffle(recentlySeenNonBlacklisted).take(howMany)
      }
    }

    case object GetAllPeers extends GetPeers[Map[InetSocketAddress, PeerInfo]] {

      override def choose(
        knownPeers:       Map[InetSocketAddress, PeerInfo],
        blacklistedPeers: Seq[InetAddress],
        sc:               AppContext
      ): Map[InetSocketAddress, PeerInfo] = knownPeers
    }

    case class RandomPeerExcluding(excludedPeers: Seq[PeerInfo]) extends GetPeers[Option[PeerInfo]] {

      override def choose(
        knownPeers:       Map[InetSocketAddress, PeerInfo],
        blacklistedPeers: Seq[InetAddress],
        sc:               AppContext
      ): Option[PeerInfo] = {
        val candidates = knownPeers.values.filterNot { p =>
          excludedPeers.exists(_.peerSpec.address == p.peerSpec.address) &&
          blacklistedPeers.exists(addr => p.peerSpec.address.map(_.getAddress).contains(addr))
        }.toSeq
        if (candidates.nonEmpty) Some(candidates(Random.nextInt(candidates.size)))
        else None
      }
    }

    case object GetBlacklistedPeers extends GetPeers[Seq[InetAddress]] {

      override def choose(
        knownPeers:       Map[InetSocketAddress, PeerInfo],
        blacklistedPeers: Seq[InetAddress],
        appContext:       AppContext
      ): Seq[InetAddress] = blacklistedPeers
    }

  }

}

////////////////////////////////////////////////////////////////////////////////////
//////////////////////////////// ACTOR REF HELPER //////////////////////////////////

object PeerManagerRef {

  def props(
    settings:              AppSettings,
    appContext:            AppContext
  )(implicit timeProvider: TimeProvider): Props =
    Props(new PeerManager(settings, appContext))
}
