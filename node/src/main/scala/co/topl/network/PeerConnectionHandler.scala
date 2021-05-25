package co.topl.network

import akka.actor.{Actor, ActorRef, ActorSystem, Cancellable, Props, SupervisorStrategy}
import akka.io.Tcp
import akka.util.{ByteString, CompactByteString}
import co.topl.network.NetworkController.ReceivableMessages.{Handshaked, PenalizePeer}
import co.topl.network.PeerConnectionHandler.ReceivableMessages._
import co.topl.network.message.{Handshake, HandshakeSpec, Message, MessageSerializer}
import co.topl.network.peer.PenaltyType.PermanentPenalty
import co.topl.network.peer._
import co.topl.settings.{AppContext, AppSettings}
import co.topl.utils.Logging
import co.topl.utils.serialization.BifrostSerializer

import scala.annotation.tailrec
import scala.collection.immutable.TreeMap
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

class PeerConnectionHandler(
  networkControllerRef:  ActorRef,
  settings:              AppSettings,
  appContext:            AppContext,
  connectionDescription: ConnectionDescription
)(implicit ec:           ExecutionContext)
    extends Actor
    with Logging {

  private val connection = connectionDescription.connection
  private val connectionId = connectionDescription.connectionId
  private val direction = connectionDescription.connectionId.direction
  private val ownSocketAddress = connectionDescription.ownSocketAddress
  private val localFeatures = connectionDescription.localFeatures

  private val localPeerSpec = PeerSpec(
    settings.network.agentName,
    settings.application.version,
    settings.network.nodeName,
    ownSocketAddress,
    localFeatures
  )

  private val featureSerializers: PeerFeature.Serializers =
    localFeatures.map(f => f.featureId -> (f.serializer: BifrostSerializer[_ <: PeerFeature])).toMap

  private val handshakeSerializer = new HandshakeSpec(featureSerializers, settings.network.maxHandshakeSize)
  private val messageSerializer = new MessageSerializer(appContext.messageSpecs, settings.network.magicBytes)

  /** there is no recovery for broken connections */
  override val supervisorStrategy: SupervisorStrategy = SupervisorStrategy.stoppingStrategy

  private var selfPeer: Option[ConnectedPeer] = None

  private var handshakeTimeoutCancellableOpt: Option[Cancellable] = None

  private var chunksBuffer: ByteString = CompactByteString.empty

  private var outMessagesBuffer: TreeMap[Long, ByteString] = TreeMap.empty

  private var outMessagesCounter: Long = 0

  override def preStart(): Unit = {

    /** per Akka docs this "signs the death pact: this actor terminates when connection breaks" */
    context watch connection
    connection ! Tcp.Register(self, keepOpenOnPeerClosed = false, useResumeWriting = true)

    /**
     * On instantiation of a PeerConnectionHandler, send ResumeReading to the TCP system since the
     * connection was created with pullMode = true (this is done in the NetworkController)
     * https://doc.akka.io/docs/akka/current/io-tcp.html#read-back-pressure-with-pull-mode
     */
    connection ! Tcp.ResumeReading

    /** transition to create and listen for a handshake from the remote peer */
    context become handshaking
  }

  override def postStop(): Unit = log.info(s"Peer handler to $connectionId destroyed")

////////////////////////////////////////////////////////////////////////////////////
////////////////////////////// ACTOR MESSAGE HANDLING //////////////////////////////

  // ----------- CONTEXTS ----------- //
  /** The following functions are the contexts that a peerConnectionHandler can occupy */
  /** depending on the state of the connection. */

  /** we don't expect the default receive to be used due to prestart context change */
  override def receive: Receive = nonsense

  private def handshaking: Receive = {

    /** When the PCH context becomes handshaking, this will create a handshake that is sent to the remote peer */
    /** NOTE: This process is only executed once and subsequent messages are handled by the partial functions below */
    createHandshakeMessage() // create handshake with timeout and then continue to process messages

    /** receive and processes message from remote peer (prior to sending data) */
    receiveAndHandleHandshake orElse
    handshakeTimeout orElse
    fatalCommands orElse
    nonsense
  }

  private def workingCycleWriting: Receive =
    localInterfaceWriting orElse
    remoteInterface orElse
    fatalCommands orElse
    nonsense

  private def workingCycleBuffering: Receive =
    localInterfaceBuffering orElse
    remoteInterface orElse
    fatalCommands orElse
    nonsense

  private def closingWithNonEmptyBuffer: Receive =
    closeNonEmptyBuffer orElse
    nonsense

  // ----------- MESSAGE PROCESSING FUNCTIONS ----------- //
  /** The following functions are used to process the incoming messages based on the current context */
  /** Some functions are only accessible in certain contexts so be sure to consult the lists above */

  private def receiveAndHandleHandshake: Receive = { case Tcp.Received(data) =>
    handshakeSerializer.parseBytes(data.toArray) match {
      case Success(handshake) =>
        processHandshake(handshake)

      case Failure(t) =>
        log.info(s"Error during parsing a handshake", t)

        /** ban the peer for the wrong handshake message */
        /** peer will be added to the blacklist and the network controller will send CloseConnection */
        selfPeer.foreach(c => networkControllerRef ! PenalizePeer(c.connectionId.remoteAddress, PermanentPenalty))
    }
  }

  private def handshakeTimeout: Receive = { case HandshakeTimeout =>
    log.info(s"Handshake timeout with $connectionId, going to drop the connection")
    connection ! Tcp.Close
  }

  private def localInterfaceWriting: Receive = {
    case msg: Message[_] =>
      log.info("Send message " + msg.spec + " to " + connectionId)
      outMessagesCounter += 1
      connection ! Tcp.Write(messageSerializer.serialize(msg), Ack(outMessagesCounter))

    case Tcp.CommandFailed(Tcp.Write(msg, Ack(id))) =>
      log.warn(s"Failed to write ${msg.length} bytes to $connectionId, switching to buffering mode")
      connection ! Tcp.ResumeWriting
      buffer(id, msg)
      context become workingCycleBuffering

    /** ignore in stable mode */
    case Tcp.WritingResumed =>

    /** ignore ACKs in stable mode */
    case Ack(_) =>

    case CloseConnection =>
      log.info(s"Enforced to abort communication with: " + connectionId + ", switching to closing mode")
      if (outMessagesBuffer.isEmpty) connection ! Tcp.Close else context become closingWithNonEmptyBuffer
  }

  /** operate in ACK mode until all buffered messages are transmitted */
  private def localInterfaceBuffering: Receive = {
    case msg: Message[_] =>
      outMessagesCounter += 1
      buffer(outMessagesCounter, messageSerializer.serialize(msg))

    case Tcp.CommandFailed(Tcp.Write(msg, Ack(id))) =>
      connection ! Tcp.ResumeWriting
      buffer(id, msg)

    /** ignore in ACK mode */
    case Tcp.CommandFailed(Tcp.ResumeWriting) =>

    case Tcp.WritingResumed =>
      writeFirst()

    case Ack(id) =>
      outMessagesBuffer -= id
      if (outMessagesBuffer.nonEmpty) writeFirst()
      else {
        log.info("Buffered messages processed, exiting buffering mode")
        context become workingCycleWriting
      }

    case CloseConnection =>
      log.info(s"Enforced to abort communication with: " + connectionId + s", switching to closing mode")
      writeAll()
      context become closingWithNonEmptyBuffer
  }

  private def closeNonEmptyBuffer: Receive = {
    case Tcp.CommandFailed(_: Tcp.Write) =>
      connection ! Tcp.ResumeWriting
      context.become(
        {
          case Tcp.WritingResumed =>
            writeAll()
            context.unbecome()
          case Ack(id) =>
            outMessagesBuffer -= id
        },
        discardOld = false
      )

    case Ack(id) =>
      outMessagesBuffer -= id
      if (outMessagesBuffer.isEmpty) connection ! Tcp.Close
  }

  private def remoteInterface: Receive = { case Tcp.Received(data) =>
    chunksBuffer ++= data
    processRemoteData()
    connection ! Tcp.ResumeReading
  }

  private def fatalCommands: Receive = { case _: Tcp.ConnectionClosed =>
    log.info(s"Connection closed to $connectionId")
    context stop self
  }

  private def nonsense: Receive = { case nonsense: Any =>
    log.warn(s"PeerConnectionHandler (in context ${context.toString}): got unexpected input $nonsense from ${sender()}")
  }

////////////////////////////////////////////////////////////////////////////////////
//////////////////////////////// METHOD DEFINITIONS ////////////////////////////////

  private def buffer(id: Long, msg: ByteString): Unit =
    outMessagesBuffer += id -> msg

  private def writeFirst(): Unit =
    outMessagesBuffer.headOption.foreach { case (id, msg) =>
      connection ! Tcp.Write(msg, Ack(id))
    }

  private def writeAll(): Unit =
    outMessagesBuffer.foreach { case (id, msg) =>
      connection ! Tcp.Write(msg, Ack(id))
    }

  private def createHandshakeMessage(): Unit = {
    val nodeInfo = message.Handshake(localPeerSpec, appContext.timeProvider.time)

    /**
     * create, save, and schedule a timeout option. The variable lets us cancel the timeout message
     * if a handshake is received
     */
    handshakeTimeoutCancellableOpt = Some(
      context.system.scheduler.scheduleOnce(settings.network.handshakeTimeout)(self ! HandshakeTimeout)
    )

    /** send a handshake message with our node information to the remote peer */
    connection ! Tcp.Write(ByteString(handshakeSerializer.toBytes(nodeInfo)), Tcp.NoAck)
    log.info(s"Handshake sent to $connectionId")
  }

  private def processHandshake(receivedHandshake: Handshake): Unit = {
    log.info(s"Got a Handshake from $connectionId")

    val peerInfo = PeerInfo(
      receivedHandshake.peerSpec,
      appContext.timeProvider.time,
      Some(direction)
    )
    val peer = ConnectedPeer(connectionDescription.connectionId, self, Some(peerInfo))
    selfPeer = Some(peer)

    networkControllerRef ! Handshaked(peerInfo)
    handshakeTimeoutCancellableOpt.map(_.cancel())
    connection ! Tcp.ResumeReading
    context become workingCycleWriting
  }

  @tailrec
  private def processRemoteData(): Unit =
    messageSerializer.deserialize(chunksBuffer, selfPeer) match {
      case Success(Some(message)) =>
        log.info("Received message " + message.spec + " from " + connectionId)
        networkControllerRef ! message
        chunksBuffer = chunksBuffer.drop(message.messageLength)
        processRemoteData()
      case Success(None) =>
      case Failure(e) =>
        e match {
          /** peer is doing bad things, ban it */
          case MaliciousBehaviorException(msg) =>
            log.warn(s"Banning peer for malicious behaviour($msg): ${connectionId.toString}")

            /** peer will be added to the blacklist and the network controller will send CloseConnection */
            networkControllerRef ! PenalizePeer(connectionId.remoteAddress, PenaltyType.PermanentPenalty)

          /** non-malicious corruptions */
          case _ =>
            log.info(s"Corrupted data from ${connectionId.toString}: ${e.getMessage}")
        }
    }

}

////////////////////////////////////////////////////////////////////////////////////
/////////////////////////////// COMPANION SINGLETON ////////////////////////////////

object PeerConnectionHandler {

  object ReceivableMessages {

    private[PeerConnectionHandler] object HandshakeDone

    case object StartInteraction

    case object HandshakeTimeout

    case object CloseConnection

    final case class Ack(id: Long) extends Tcp.Event
  }
}

////////////////////////////////////////////////////////////////////////////////////
//////////////////////////////// ACTOR REF HELPER //////////////////////////////////

object PeerConnectionHandlerRef {

  def props(
    networkControllerRef:  ActorRef,
    settings:              AppSettings,
    appContext:            AppContext,
    connectionDescription: ConnectionDescription
  )(implicit ec:           ExecutionContext): Props =
    Props(new PeerConnectionHandler(networkControllerRef, settings, appContext, connectionDescription))

  def apply(
    networkControllerRef:  ActorRef,
    settings:              AppSettings,
    appContext:            AppContext,
    connectionDescription: ConnectionDescription
  )(implicit system:       ActorSystem, ec: ExecutionContext): ActorRef =
    system.actorOf(props(networkControllerRef, settings, appContext, connectionDescription))
}
