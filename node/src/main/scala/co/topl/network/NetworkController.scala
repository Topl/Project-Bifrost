package co.topl.network

import akka.actor.SupervisorStrategy._
import akka.actor._
import akka.io.Tcp
import akka.pattern.ask
import akka.util.Timeout
import co.topl.network.NodeViewSynchronizer.ReceivableMessages.{DisconnectedPeer, HandshakedPeer}
import co.topl.network.PeerConnectionHandler.ReceivableMessages.CloseConnection
import co.topl.network.PeerManager.ReceivableMessages._
import co.topl.network.message.Message
import co.topl.network.peer.{ConnectedPeer, PeerInfo, PenaltyType, _}
import co.topl.settings.{AppContext, AppSettings, Version}
import co.topl.utils.TimeProvider.Time
import co.topl.utils.{Logging, NetworkUtils, TimeProvider}

import java.net._
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

/**
 * Control all network interaction, must be singleton
 * @param settings app settings for Bifrost
 * @param peerManagerRef ActorRef of peerManager
 * @param appContext Info that Bifrost needs based on the settings and user options
 * @param tcpManager a reference to the manager actor for the Tcp IO extension
 */
class NetworkController(
  settings:              AppSettings,
  peerManagerRef:        ActorRef,
  appContext:            AppContext,
  tcpManager:            ActorRef
)(implicit timeProvider: TimeProvider)
    extends Actor
    with Logging {

  import context.dispatcher

  /** Import the types of messages this actor can RECEIVE */
  import NetworkController.ReceivableMessages._

  private lazy val bindAddress = settings.network.bindAddress
  implicit private val system: ActorSystem = context.system

  implicit private val timeout: Timeout = Timeout(settings.network.controllerTimeout.getOrElse(5 seconds))

  override val supervisorStrategy: OneForOneStrategy =
    OneForOneStrategy(
      maxNrOfRetries = NetworkController.ChildActorHandlingRetriesNr,
      withinTimeRange = 1.minute
    ) {
      case _: ActorKilledException => Stop
      case _: DeathPactException   => Stop
      case e: ActorInitializationException =>
        log.warn(s"Stopping child actor failed with: $e")
        Stop
      case e: Exception =>
        log.warn(s"Restarting child actor failed with: $e")
        Restart
    }

  private var messageHandlers = Map.empty[message.Message.MessageCode, ActorRef]
  private var connections = Map.empty[InetSocketAddress, ConnectedPeer]
  private var unconfirmedConnections = Set.empty[InetSocketAddress]

  /** records the time of the most recent incoming message (for checking connectivity) */
  private var lastIncomingMessageTime: TimeProvider.Time = _

  override def preStart(): Unit =
    log.info(s"Declared address: ${appContext.externalNodeAddress}")

  ////////////////////////////////////////////////////////////////////////////////////
  ////////////////////////////// ACTOR MESSAGE HANDLING //////////////////////////////

  // ----------- CONTEXT ----------- //
  override def receive: Receive =
    initialization

  private def operational: Receive =
    businessLogic orElse
    peerCommands orElse
    connectionEvents orElse
    // TODO: Is it okay to do this in the "operational" state?
    registerMessageSpecs orElse
    nonsense

  // ----------- MESSAGE PROCESSING FUNCTIONS ----------- //
  private def initialization: Receive = bindP2P orElse registerMessageSpecs orElse nonsense

  private def bindP2P: Receive = { case BindP2P =>
    /** check own declared address for validity */
    val addrValidationResult =
      if (validateDeclaredAddress()) {

        /**
         * send a bind signal to the TCP manager to designate this actor as the
         * handler to accept incoming connections
         */
        (tcpManager ? Tcp.Bind(self, bindAddress, options = Nil, pullMode = false)).mapTo[Tcp.Event]
      } else {
        throw new Error("Address validation failed. Aborting application startup.")
      }

    sender() ! addrValidationResult
    becomeOperational()
  }

  private def registerMessageSpecs: Receive = { case RegisterMessageSpecs(specs, handler) =>
    log.info(
      s"${Console.YELLOW}Registered ${sender()} as the handler for " +
      s"${specs.map(s => s.messageCode -> s.messageName)}${Console.RESET}"
    )

    /** add the message code and its corresponding handler actorRef to the map */
    messageHandlers ++= specs.map(_.messageCode -> handler)

  }

  private def becomeOperational(): Unit = {
    log.info(s"${Console.YELLOW}Network Controller transitioning to the operational state${Console.RESET}")
    scheduleConnectionToPeer()
    scheduleDroppingDeadConnections()
    context become operational
  }

  private def businessLogic: Receive = {
    /** a message was RECEIVED from a remote peer */
    case msg @ Message(spec, _, Some(remote)) =>
      /** update last seen time for the peer sending us this message */
      updatePeerStatus(remote)
      messageHandlers.get(spec.messageCode) match {
        /** forward the message to the appropriate handler for processing */
        case Some(handler) => handler ! msg
        case None          => log.error(s"No handlers found for message $remote: " + spec.messageCode)
      }

    /** a message to be SENT to a remote peer */
    case SendToNetwork(msg: Message[_], sendingStrategy) =>
      filterConnections(sendingStrategy, msg.spec.version).foreach { connectedPeer =>
        connectedPeer.handlerRef ! msg
      }
  }

  /** methods used to manage peers */
  private def peerCommands: Receive = {
    /** used to periodically connecting to peers */
    case ConnectTo(peer) =>
      connectTo(peer)

    case DisconnectFrom(peer) =>
      log.info(s"Disconnected from ${peer.connectionId}")
      peer.handlerRef ! CloseConnection

    case GetConnectedPeers =>
      sender() ! connections.values.flatMap(_.peerInfo).toSeq

    case PenalizePeer(peerAddress, penaltyType) =>
      penalize(peerAddress, penaltyType)

    case Blacklisted(peerAddress) =>
      closeConnection(peerAddress)
  }

  private def connectionEvents: Receive = {
    case Tcp.Connected(remoteAddress, localAddress) if connectionForPeerAddress(remoteAddress).isEmpty =>
      /** this should be an incoming connection if remoteAddress is not in connections and is not in unconfirmed */
      val connectionDirection: ConnectionDirection =
        if (unconfirmedConnections.contains(remoteAddress)) Outgoing
        else Incoming

      val connectionId =
        ConnectionId(remoteAddress, localAddress, connectionDirection)
      log.info(s"Unconfirmed connection: ($remoteAddress, $localAddress) => $connectionId")

      /** add to connections if we received unconfirmed outgoing connection */
      if (connectionDirection.isOutgoing) createPeerConnectionHandler(connectionId, sender())
      /** confirm connection if the peer of this incoming connection is not blacklisted */
      else peerManagerRef ! ConfirmConnection(connectionId, sender())

    /** close connection if a connection is already established */
    case Tcp.Connected(remoteAddress, _) =>
      log.warn(s"Connection to peer $remoteAddress is already established")
      sender() ! Tcp.Close

    /** this is received once PeerManager makes sure that the peer is not blacklisted */
    case ConnectionConfirmed(connectionId, handlerRef) =>
      log.info(s"Connection confirmed to $connectionId")
      createPeerConnectionHandler(connectionId, handlerRef)

    case ConnectionDenied(connectionId, handlerRef) =>
      log.info(s"Incoming connection from ${connectionId.remoteAddress} denied")
      handlerRef ! Tcp.Close

    /**
     * receive this when a PeerConnnectionHandler received and processed a handshake, then save peer to PeerDatabse
     * [[PeerConnectionHandler.processHandshake]]
     */
    case Handshaked(connectedPeer) =>
      handleHandshake(connectedPeer, sender())

    case f @ Tcp.CommandFailed(c: Tcp.Connect) =>
      unconfirmedConnections -= c.remoteAddress
      f.cause match {
        case Some(t) => log.info("Failed to connect to : " + c.remoteAddress, t)
        case None    => log.info("Failed to connect to : " + c.remoteAddress)
      }

      /**
       * If there is enough live connections, remove unresponsive peer from the database
       * In not enough live connections, maybe connectivity lost but the node has not updated its status, no ban then
       */
      if (connections.size > settings.network.maxConnections / 2) {
        peerManagerRef ! RemovePeer(c.remoteAddress)
      }

    case Terminated(ref) =>
      connectionForHandler(ref).foreach { connectedPeer =>
        val remoteAddress = connectedPeer.connectionId.remoteAddress
        connections -= remoteAddress
        unconfirmedConnections -= remoteAddress
        context.system.eventStream.publish(DisconnectedPeer(remoteAddress))
      }

    case _: Tcp.ConnectionClosed =>
      log.info("Denied connection has been closed")
  }

  private def nonsense: Receive = {
    case Tcp.CommandFailed(cmd: Tcp.Command) =>
      log.info("Failed to execute command : " + cmd)

    case nonsense: Any =>
      log.warn(s"Got unexpected input $nonsense from ${sender()}")
  }

  ////////////////////////////////////////////////////////////////////////////////////
  //////////////////////////////// METHOD DEFINITIONS ////////////////////////////////

  def networkTime(): Time = timeProvider.time

  /** Schedule a periodic connection to a random known peer */
  private def scheduleConnectionToPeer(): Unit =
    context.system.scheduler.scheduleWithFixedDelay(5.seconds, 5.seconds) { () =>
      /** only attempt connections if we are connected or attempting to connect to less than max connection */
      if (connections.size + unconfirmedConnections.size < settings.network.maxConnections) {
        log.debug(s"Looking for a new random connection")

        /** get a set of random peers from the database (excluding connected peers) */
        val randomPeerF = peerManagerRef ? RandomPeerExcluding(connections.values.flatMap(_.peerInfo).toSeq)

        /** send connection attempts to the returned peers */
        randomPeerF.mapTo[Option[PeerInfo]].foreach { peerInfoOpt =>
          peerInfoOpt.foreach(peerInfo => self ! ConnectTo(peerInfo))
        }
      }
    }

  /** Schedule a periodic dropping of connections which seem to be inactive */
  private def scheduleDroppingDeadConnections(): Unit =
    context.system.scheduler.scheduleWithFixedDelay(60.seconds, 60.seconds) { () =>
      val now = networkTime()

      /** Drop connections with peers if they seem to be inactive */
      connections.values.foreach { cp =>
        val lastSeen = cp.peerInfo.map(_.lastSeen).getOrElse(now)
        if ((now - lastSeen) > settings.network.deadConnectionTimeout.toMillis) {
          log.info(s"Dropping connection with ${cp.peerInfo}, last seen ${(now - lastSeen) / 1000} seconds ago")
          cp.handlerRef ! CloseConnection
        }
      }
    }

  /**
   * Connect to peer
   * [[NetworkController.scheduleConnectionToPeer]] uses this function to periodically attempt to connect to peers
   *
   * @param peer - PeerInfo
   */
  private def connectTo(peer: PeerInfo): Unit = {
    log.info(s"Connecting to peer: $peer")
    getPeerAddress(peer) match {
      case Some(remote) =>
        if (connectionForPeerAddress(remote).isEmpty && !unconfirmedConnections.contains(remote)) {
          unconfirmedConnections += remote
          tcpManager ! Tcp.Connect(
            remoteAddress = remote,
            options = Nil,
            timeout = Some(settings.network.connectionTimeout),
            pullMode = true
          )
        } else {
          log.warn(s"Connection to peer $remote is already established")
        }
      case None =>
        log.warn(s"Can't obtain remote address for peer $peer")
    }
  }

  /**
   * Creates a PeerConnectionHandler for the established connection
   *
   * @param connectionId connection detailed info
   * @param connection connection ActorRef
   */
  private def createPeerConnectionHandler(connectionId: ConnectionId, connection: ActorRef): Unit = {
    log.info {
      connectionId.direction match {
        case Incoming =>
          s"New incoming connection from ${connectionId.remoteAddress} " +
            s"established (bound to local ${connectionId.localAddress})"
        case Outgoing =>
          s"New outgoing connection to ${connectionId.remoteAddress} " +
            s"established (bound to local ${connectionId.localAddress})"
      }
    }

    val isLocal = connectionId.remoteAddress.getAddress.isSiteLocalAddress ||
      connectionId.remoteAddress.getAddress.isLoopbackAddress

    val peerFeatures =
      if (isLocal)
        appContext.features :+ LocalAddressPeerFeature(
          new InetSocketAddress(
            connectionId.localAddress.getAddress,
            settings.network.bindAddress.getPort
          )
        )
      else appContext.features

    val selfAddressOpt = getNodeAddressForPeer(connectionId.localAddress)

    if (selfAddressOpt.isEmpty)
      log.warn("Unable to define external address. Specify it manually in `scorex.network.declaredAddress`.")

    val connectionDescription = ConnectionDescription(connection, connectionId, selfAddressOpt, peerFeatures)

    val handler: ActorRef =
      context.actorOf(PeerConnectionHandlerRef.props(self, settings, appContext, connectionDescription))

    context.watch(handler)

    val connectedPeer = peer.ConnectedPeer(connectionId, handler, None)
    connections += connectionId.remoteAddress -> connectedPeer
    unconfirmedConnections -= connectionId.remoteAddress
  }

  /**
   * Updates the local peer information when we receive new messages
   *
   * @param remote a connected peer that sent a message
   */
  private def updatePeerStatus(remote: ConnectedPeer): Unit = {
    val remoteAddress = remote.connectionId.remoteAddress
    connections.get(remoteAddress) match {
      case Some(cp) =>
        cp.peerInfo match {
          case Some(pi) =>
            val now = networkTime()
            lastIncomingMessageTime = now
            connections += remoteAddress -> cp.copy(peerInfo = Some(pi.copy(lastSeen = now)))

          case None =>
            log.warn("Peer info not found for a message received from: " + remoteAddress)
        }

      case None =>
        log.warn("Connection not found for a message received from: " + remoteAddress)
    }
  }

  /**
   * Saves a new peer into the database
   *
   * @param peerInfo connected peer information
   * @param peerHandlerRef dedicated handler actor reference
   */
  private def handleHandshake(peerInfo: PeerInfo, peerHandlerRef: ActorRef): Unit =
    connectionForHandler(peerHandlerRef).foreach { connectedPeer =>
      val remoteAddress = connectedPeer.connectionId.remoteAddress
      val peerAddress = peerInfo.peerSpec.address.getOrElse(remoteAddress)

      /** drop connection to self if occurred or peer already connected */
      val shouldDrop = isSelf(remoteAddress) ||
        connectionForPeerAddress(peerAddress).exists(
          _.handlerRef != peerHandlerRef
        )
      if (shouldDrop) {
        connectedPeer.handlerRef ! CloseConnection
        peerManagerRef ! RemovePeer(peerAddress)
        connections -= connectedPeer.connectionId.remoteAddress
      } else {
        peerManagerRef ! AddOrUpdatePeer(peerInfo)

        /** Use remoteAddress as peer's address, if there is no address info in it's PeerInfo */
        val updatedPeerSpec =
          peerInfo.peerSpec.copy(declaredAddress = Some(peerInfo.peerSpec.address.getOrElse(remoteAddress)))
        val updatedPeerInfo = peerInfo.copy(peerSpec = updatedPeerSpec)
        val updatedConnectedPeer =
          connectedPeer.copy(peerInfo = Some(updatedPeerInfo))

        /** update connections map */
        connections += remoteAddress -> updatedConnectedPeer

        /** publish handshake to all subscribers */
        context.system.eventStream.publish(HandshakedPeer(updatedConnectedPeer))
      }
    }

  /**
   * Returns connections filtered by given SendingStrategy and Version.
   * Exclude all connections with lower version and apply sendingStrategy to remaining connected peers
   *
   * @param sendingStrategy - SendingStrategy
   * @param version         - minimal version required
   * @return sequence of ConnectedPeer instances according SendingStrategy
   */
  private def filterConnections(sendingStrategy: SendingStrategy, version: Version): Seq[ConnectedPeer] =
    sendingStrategy.choose(
      connections.values.toSeq
        .filter(_.peerInfo.exists(_.peerSpec.version >= version))
    )

  /**
   * Returns connection for given PeerConnectionHandler ActorRef
   *
   * @param handler ActorRef on PeerConnectionHandler actor
   * @return Some(ConnectedPeer) when the connection exists for this handler, and None otherwise
   */
  private def connectionForHandler(handler: ActorRef): Option[ConnectedPeer] =
    connections.values.find { connectedPeer =>
      connectedPeer.handlerRef == handler
    }

  /**
   * Returns connection for given address of the peer
   *
   * @param peerAddress - socket address of peer
   * @return Some(ConnectedPeer) when the connection exists for this peer, and None otherwise
   */
  private def connectionForPeerAddress(peerAddress: InetSocketAddress): Option[ConnectedPeer] =
    connections.values.find { connectedPeer =>
      connectedPeer.connectionId.remoteAddress == peerAddress ||
      connectedPeer.peerInfo.exists { peerInfo =>
        getPeerAddress(peerInfo).contains(peerAddress)
      }
    }

  /** Checks the node owns the address */
  private def isSelf(peerAddress: InetSocketAddress): Boolean =
    NetworkUtils.isSelf(
      peerAddress,
      bindAddress,
      appContext.externalNodeAddress
    )

  /**
   * Returns local address of peer for local connections and WAN address of peer for
   * external connections. When local address is not known, try to ask it at the UPnP gateway
   *
   * @param peer - known information about peer
   * @return socket address of the peer
   */
  private def getPeerAddress(peer: PeerInfo): Option[InetSocketAddress] =
    (peer.peerSpec.localAddressOpt, peer.peerSpec.declaredAddress) match {
      case (Some(localAddr), _) =>
        Some(localAddr)

      case (None, Some(declaredAddress))
          if appContext.externalNodeAddress.exists(_.getAddress == declaredAddress.getAddress) =>
        appContext.upnpGateway.flatMap(_.getLocalAddressForExternalPort(declaredAddress.getPort))

      case _ => peer.peerSpec.declaredAddress
    }

  /**
   * Returns the node address reachable from Internet
   *
   * @param localSocketAddress - local socket address of the connection to the peer
   * @return - socket address of the node
   */
  private def getNodeAddressForPeer(localSocketAddress: InetSocketAddress): Option[InetSocketAddress] = {
    val localAddr = localSocketAddress.getAddress
    appContext.externalNodeAddress match {
      case Some(extAddr) =>
        Some(extAddr)

      case None =>
        if (
          !localAddr.isSiteLocalAddress && !localAddr.isLoopbackAddress
          && localSocketAddress.getPort == settings.network.bindAddress.getPort
        ) {
          Some(localSocketAddress)
        } else {
          val listenAddrs = NetworkUtils
            .getListenAddresses(settings.network.bindAddress)
            .filterNot(addr => addr.getAddress.isSiteLocalAddress || addr.getAddress.isLoopbackAddress)

          listenAddrs
            .find(addr => localAddr == addr.getAddress)
            .orElse(listenAddrs.headOption)
        }
    }
  }

  /** Attempts to validate the declared address defined in the settings file */
  private def validateDeclaredAddress(): Boolean =
    settings.network.declaredAddress match {
      case Some(mySocketAddress: InetSocketAddress) =>
        Try {
          val uri = new URI("http://" + mySocketAddress)
          val myHost = uri.getHost
          val myAddress = InetAddress.getAllByName(myHost)

          /** this is a list of your local interface addresses */
          val listenAddresses = NetworkUtils.getListenAddresses(bindAddress)

          /** this is a list of your external address as determined by the upnp gateway */
          val upnpAddress = appContext.upnpGateway.map(_.externalAddress)

          val valid =
            listenAddresses.exists(address => myAddress.contains(address.getAddress)) || upnpAddress.exists(
              myAddress.contains
            )

          if (!valid) {
            log.error(s"""Declared address validation failed:
                 | $mySocketAddress not match any of the listening address: $listenAddresses
                 | or Gateway WAN address: $upnpAddress""".stripMargin)
          }

          valid
        } match {
          /** address was valid */
          case Success(res: Boolean) if res => true
          /** address was not valid */
          case Success(_) => false
          case Failure(ex) =>
            log.error("There was an error while attempting to validate the declared address: ", ex)
            false
        }

      case _ =>
        log.info(s"No declared address was provided. Skipping address validation.")
        true
    }

  /** Close connection with a peer: remove related connections, and send CloseConnection to peer */
  private def closeConnection(peerAddress: InetSocketAddress): Unit =
    connections.get(peerAddress).foreach { peer =>
      connections = connections.filterNot { case (address, _) =>
        /** clear all connections related to banned peer ip */
        Option(peer.connectionId.remoteAddress.getAddress)
          .exists(Option(address.getAddress).contains(_))
      }
      peer.handlerRef ! CloseConnection
    }

  /** Register a new penalty for given peer address */
  private def penalize(peerAddress: InetSocketAddress, penaltyType: PenaltyType): Unit =
    peerManagerRef ! Penalize(peerAddress, penaltyType)
}

////////////////////////////////////////////////////////////////////////////////////
/////////////////////////////// COMPANION SINGLETON ////////////////////////////////

object NetworkController {

  val actorName = "networkController"

  val ChildActorHandlingRetriesNr: Int = 10

  object ReceivableMessages {

    case class Handshaked(peer: PeerInfo)

    case class RegisterMessageSpecs(specs: Seq[message.MessageSpec[_]], handler: ActorRef)

    case class SendToNetwork(message: Message[_], sendingStrategy: SendingStrategy)

    case class ConnectTo(peer: PeerInfo)

    case class DisconnectFrom(peer: ConnectedPeer)

    case class PenalizePeer(address: InetSocketAddress, penaltyType: PenaltyType)

    case class ConnectionConfirmed(connectionId: ConnectionId, handlerRef: ActorRef)

    case class ConnectionDenied(connectionId: ConnectionId, handlerRef: ActorRef)

    case object ShutdownNetwork

    case object GetConnectedPeers

    case object BindP2P
  }
}

////////////////////////////////////////////////////////////////////////////////////
//////////////////////////////// ACTOR REF HELPER //////////////////////////////////

object NetworkControllerRef {

  def props(
    settings:              AppSettings,
    peerManagerRef:        ActorRef,
    appContext:            AppContext,
    tcpManager:            ActorRef
  )(implicit timeProvider: TimeProvider): Props =
    Props(new NetworkController(settings, peerManagerRef, appContext, tcpManager))
}
