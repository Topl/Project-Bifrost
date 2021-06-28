package co.topl.settings

import akka.actor.ActorSystem
import co.topl.network.message._
import co.topl.network.utils.{NetworkTimeProvider, UPnPGateway}
import co.topl.network.{peer, NodeViewSynchronizer, PeerSynchronizer}
import co.topl.utils.NetworkType

import java.net.InetSocketAddress

/**
 * Info that Bifrost needs based on the settings and user options
 *
 * @param settings application settings
 * @param startupOpts user defined startup options
 * @param upnpGateway Option of Gateway class handling gateway device and port forwarding
 */
class AppContext(
  val settings:    AppSettings,
  startupOpts:     StartupOpts,
  val upnpGateway: Option[UPnPGateway]
)(implicit system: ActorSystem) {

  /** Save your address for sending to others peers */
  val externalNodeAddress: Option[InetSocketAddress] =
    settings.network.declaredAddress orElse {
      upnpGateway.map(u => new InetSocketAddress(u.externalAddress, u.mappedPort))
    }

  /** Save a common time provider to be used */
  val timeProvider = new NetworkTimeProvider(settings.ntp)

  /** Save chosen network for loading genesis config */
  val networkType: NetworkType =
    startupOpts.networkTypeOpt match {
      case Some(network) => network
      case None          => NetworkType.PrivateTestnet
    }

  /** Enumerate features and message specs present for communicating between peers */
  val features: Seq[peer.PeerFeature] = Seq()
  val featureSerializers: peer.PeerFeature.Serializers = features.map(f => f.featureId -> f.serializer).toMap

  /** Instantiate and populate the local message handler for peer management requests from remote peers */
  val peerSyncRemoteMessages: PeerSynchronizer.RemoteMessageHandler = {
    val getPeersSpec = new GetPeersSpec
    val peersSpec = new PeersSpec(featureSerializers, settings.network.maxPeerSpecObjects)

    PeerSynchronizer.RemoteMessageHandler(peersSpec, getPeersSpec)
  }

  /** Instantiate and populate the local message handler for node view management requests from remote peers */
  val nodeViewSyncRemoteMessages: NodeViewSynchronizer.RemoteMessageHandler = {
    val syncInfoSpec = new SyncInfoSpec
    val invSpec = new InvSpec(settings.network.maxInvObjects)
    val requestModifierSpec = new RequestModifierSpec(settings.network.maxInvObjects)
    val modifiersSpec = new ModifiersSpec(settings.network.maxPacketSize)

    NodeViewSynchronizer.RemoteMessageHandler(syncInfoSpec, invSpec, requestModifierSpec, modifiersSpec)
  }

  val messageSpecs: Seq[MessageSpec[_]] = peerSyncRemoteMessages.toSeq ++ nodeViewSyncRemoteMessages.toSeq
}
