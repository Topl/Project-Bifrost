package co.topl.network

import akka.actor.{typed, ActorRef, Props}
import akka.util.Timeout
import co.topl.modifier.NodeViewModifier.{idsToString, ModifierTypeId}
import co.topl.modifier.block.{Block, PersistentNodeViewModifier}
import co.topl.modifier.transaction.Transaction
import co.topl.modifier.{ModifierId, NodeViewModifier}
import co.topl.network.ModifiersStatus.Requested
import co.topl.network.NetworkController.ReceivableMessages.{PenalizePeer, RegisterMessageSpecs, SendToNetwork}
import co.topl.network.message.{InvSpec, MessageSpec, ModifiersSpec, RequestModifierSpec, SyncInfo, SyncInfoSpec, _}
import co.topl.network.peer.{ConnectedPeer, PenaltyType}
import co.topl.nodeView.history.GenericHistory._
import co.topl.nodeView.{NodeViewHolder, ReadableNodeView}
import co.topl.settings.{AppContext, AppSettings}
import co.topl.utils.serialization.BifrostSerializer
import co.topl.utils.{Logging, MalformedModifierError, TimeProvider}

import java.net.InetSocketAddress
import scala.annotation.tailrec
import scala.concurrent.Future
import scala.util.{Failure, Success}

/**
 * A component which is synchronizing local node view (locked inside NodeViewHolder) with the p2p network.
 *
 * @param networkControllerRef reference to network controller actor
 * @param viewHolderRef        reference to node view holder actor
 */
class NodeViewSynchronizer(
  networkControllerRef:  ActorRef,
  viewHolderRef:         akka.actor.typed.ActorRef[NodeViewHolder.ReceivableMessage],
  settings:              AppSettings,
  appContext:            AppContext
)(implicit timeProvider: TimeProvider)
    extends Synchronizer
    with Logging {

  /** Import the types of messages this actor may SEND */
  import co.topl.network.NodeViewSynchronizer.ReceivableMessages._
  import context.dispatcher

  /** the maximum number of inventory modifiers to compare with remote peers */
  protected val desiredInvObjects: Int = settings.network.desiredInvObjects

  /** serializers for blocks and transactions */
  protected val modifierSerializers: Map[ModifierTypeId, BifrostSerializer[_ <: NodeViewModifier]] =
    NodeViewModifier.modifierSerializers

  /** convenience variables for accessing the messages specs */
  protected val invSpec: InvSpec = appContext.nodeViewSyncRemoteMessages.invSpec
  protected val requestModifierSpec: RequestModifierSpec = appContext.nodeViewSyncRemoteMessages.requestModifierSpec
  protected val modifiersSpec: ModifiersSpec = appContext.nodeViewSyncRemoteMessages.modifiersSpec
  protected val syncInfoSpec: SyncInfoSpec = appContext.nodeViewSyncRemoteMessages.syncInfoSpec

  /** partial functions for identifying local method handlers for the messages above */
  protected val msgHandlers: PartialFunction[(MessageSpec[_], _, ConnectedPeer), Unit] = {
    case (_: SyncInfoSpec, data: BifrostSyncInfo @unchecked, remote) => gotRemoteSyncInfo(data, remote)
    case (_: InvSpec, data: InvData, remote)                         => gotRemoteInventory(data, remote)
    case (_: RequestModifierSpec, data: InvData, remote)             => gotModifierRequest(data, remote)
    case (_: ModifiersSpec, data: ModifiersData, remote)             => gotRemoteModifiers(data, remote)
  }

  protected val deliveryTracker = new DeliveryTracker(self, context, settings.network)
  protected val statusTracker = new SyncTracker(self, context, settings.network, timeProvider)

  override def preStart(): Unit = {

    /** register as a handler for synchronization-specific types of messages */
    networkControllerRef ! RegisterMessageSpecs(appContext.nodeViewSyncRemoteMessages.toSeq, self)

    /** register as a listener for peers got connected (handshaked) or disconnected */
    context.system.eventStream.subscribe(self, classOf[HandshakedPeer])
    context.system.eventStream.subscribe(self, classOf[DisconnectedPeer])

    /** subscribe for all the node view holder events involving modifiers and transactions */
    context.system.eventStream.subscribe(self, classOf[NodeViewHolder.OutcomeEvent])
    context.system.eventStream.subscribe(self, classOf[NodeViewHolder.Events.DownloadRequest])
    context.system.eventStream.subscribe(self, classOf[ModifiersProcessingResult[Block]])

    log.info(s"${Console.YELLOW}NodeViewSynchronizer transitioning to the operational state${Console.RESET}")

    /** schedules a SendLocalSyncInfo message to be sent at a fixed interval */
    statusTracker.scheduleSendSyncInfo()
  }

  ////////////////////////////////////////////////////////////////////////////////////
  ////////////////////////////// ACTOR MESSAGE HANDLING //////////////////////////////

  // ----------- CONTEXT ----------- //
  override def receive: Receive =
    processDataFromPeer orElse
    processSyncStatus orElse
    manageModifiers orElse
    viewHolderEvents orElse
    peerManagerEvents orElse
    nonsense

  protected def processSyncStatus: Receive = {
    /** send local sync status to a peer */
    case SendLocalSyncInfo =>
      sendSync()
  }

  protected def manageModifiers: Receive = {

    /** Request data from any remote node */
    case NodeViewHolder.Events.DownloadRequest(modifierTypeId, modifierId) =>
      withNodeView(view => deliveryTracker.status(modifierId, view.history))
        .foreach(status =>
          if (status == ModifiersStatus.Unknown) requestDownload(modifierTypeId, Seq(modifierId), None)
        )

    /** Respond with data from the local node */
    case ResponseFromLocal(peer, _, modifiers: Seq[NodeViewModifier]) =>
      /** retrieve the serializer for the modifier and then send to the remote peer */
      modifiers.headOption.foreach { head =>
        val modType = head.modifierTypeId
        modifierSerializers.get(modType) match {
          case Some(serializer: BifrostSerializer[NodeViewModifier]) =>
            sendByParts(peer, modType, modifiers.map(m => m.id -> serializer.toBytes(m)))
          case _ =>
            log.error(s"Undefined serializer for modifier of type $modType")
        }
      }

    /** check whether requested modifiers have been delivered to the local node from a remote peer */
    case CheckDelivery(peerOpt, modifierTypeId, modifierId) =>
      checkDelivery(peerOpt, modifierTypeId, modifierId)
  }

  protected def viewHolderEvents: Receive = {
    /** Update status of the modifier as Held and announce the new valid modifier if a transaction is successful */
    case NodeViewHolder.Events.SuccessfulTransaction(tx) =>
      deliveryTracker.setHeld(tx.id)
      broadcastModifierInv(tx)

    /** Set modifier as invalid and penalize peer if this invalid modifier is the first one from the peer */
    case NodeViewHolder.Events.FailedTransaction(id, _, immediateFailure) =>
      val senderOpt = deliveryTracker.setInvalid(id)

      /** penalize sender only in case transaction was invalidated at first validation. */
      if (immediateFailure) senderOpt.foreach(penalizeMisbehavingPeer)

    case NodeViewHolder.Events.SyntacticallySuccessfulModifier(mod) =>
      deliveryTracker.setHeld(mod.id)

    case NodeViewHolder.Events.SyntacticallyFailedModification(mod, _) =>
      deliveryTracker.setInvalid(mod.id).foreach(penalizeMisbehavingPeer)

    case NodeViewHolder.Events.SemanticallySuccessfulModifier(mod) =>
      broadcastModifierInv(mod)

    case NodeViewHolder.Events.SemanticallyFailedModification(mod, _) =>
      deliveryTracker.setInvalid(mod.id).foreach(penalizeMisbehavingPeer)

    case ModifiersProcessingResult(applied: Seq[Block], cleared: Seq[Block]) =>
      /** stop processing for cleared modifiers */
      /** applied modifiers state was already changed at `SyntacticallySuccessfulModifier` */
      cleared.foreach(m => deliveryTracker.setUnknown(m.id))
      requestMoreModifiers(applied)
  }

  protected def peerManagerEvents: Receive = {
    case HandshakedPeer(remote) =>
      statusTracker.updateStatus(remote, Unknown)

    case DisconnectedPeer(remote) =>
      statusTracker.clearStatus(remote)
  }

  protected def nonsense: Receive = { case nonsense: Any =>
    log.warn(s"NodeViewSynchronizer: got unexpected input $nonsense from ${sender()}")
  }

  ////////////////////////////////////////////////////////////////////////////////////
  //////////////////////////////// METHOD DEFINITIONS ////////////////////////////////

  /**
   * Announce a new modifier
   *
   * @param m the modifier to be broadcast
   * @tparam M the type of modifier
   */
  protected def broadcastModifierInv[M <: NodeViewModifier](m: M): Unit = {
    val msg = Message(invSpec, Right(InvData(m.modifierTypeId, Seq(m.id))), None)
    networkControllerRef ! SendToNetwork(msg, Broadcast)
  }

  /**
   * Application-specific logic to request more modifiers after application if needed to
   * speed-up synchronization process, e.g. send Sync message for unknown or older peers
   * when our modifier is not synced yet, but no modifiers are expected from other peers
   * or request modifiers we need with known ids, that are not applied yet.
   */
  protected def requestMoreModifiers(applied: Seq[Block]): Unit = {}

  /**
   * Handles checking the status of modifiers that we have asked peers for using the `requestDownload` method.
   * If the modifier request targeted a peer, then we will wait for that peer to respond for a fixed interval before
   * transitioning to asking for the modifier from other random connected peers. If we still do not receive the modifier
   * after asking random peers for a fixed interval of time, we will stop requesting it.
   * The truth table implementing this behavior is shown below:
   *
   * | peerOpt? | maxTries? | send new request? | actions                                                         |
   * |----------|-----------|-------------------|-----------------------------------------------------------------|
   * |  Yes     |  No       |  No               |  penalize peer & schedule next check                            |
   * |  Yes     |  Yes      |  Yes              |  request download from random & schedule next check             |
   * |  No      |  No       |  Yes              |  request download from random & schedule next check             |
   * |  No      |  Yes      |  No               |  remove modifiers from Requested tracker and stop requesting    |
   *
   * @param peerOpt optional connected peer that we are expecting the modifier from (None if we asked random peers)
   * @param modifierTypeId type of modifier being asked for
   * @param modifierId unique id of the requested modifier
   */
  protected def checkDelivery(
    peerOpt:        Option[ConnectedPeer],
    modifierTypeId: ModifierTypeId,
    modifierId:     ModifierId
  ): Unit =
    /** Do nothing if the modifier is already in a different state (it might be already received, applied, etc.) */
    if (deliveryTracker.status(modifierId) == ModifiersStatus.Requested) {

      /** update the check count of the modifiers that we are waiting on and schedule the next check */
      deliveryTracker.onStillWaiting(peerOpt, modifierTypeId, modifierId) match {
        /** handle whether we should continue to look for this modifier */
        case Success(underMaxAttempts) =>
          peerOpt match {
            /** this is the case that we are continuing to wait on a specific peer to respond */
            case Some(peer) if underMaxAttempts =>
              /**
               * a remote peer sent `Inv` for this modifier, wait for delivery from that peer until the number of
               * checks exceeds the maximum
               */
              log.info(s"Peer ${peer.toString} has not delivered requested modifier $modifierId on time")
              penalizeNonDeliveringPeer(peer)

            /** this is the case that we are going to start asking anyone for this modifier */
            /** we'll keep hitting this case until no peer is specified and we hit the maximum number of tries again */
            case Some(_) | None =>
              log.info(s"Modifier $modifierId still has not been delivered. Querying random peers")

              /** request must have been sent previously to have scheduled a CheckDelivery */
              requestDownload(modifierTypeId, Seq(modifierId), None, previouslyRequested = true)
          }

        /** we should stop expecting this modifier since we have tried multiple parties several times */
        case Failure(ex) =>
          log.warn(s"Aborting attempts to retrieve modifier - $ex")
          deliveryTracker.setUnknown(modifierId)
      }
    }

  /**
   * Announce the local synchronization status by broadcasting the latest blocks ids
   * from the tip of our chain
   */
  protected def sendSync(): Unit = {
    val peers = statusTracker.peersToSyncWith()
    withNodeView(_.history.syncInfo)
      .foreach(syncInfo =>
        if (peers.nonEmpty)
          networkControllerRef ! SendToNetwork(Message(syncInfoSpec, Right(syncInfo), None), SendToPeers(peers))
      )
  }

  protected def penalizeNonDeliveringPeer(peer: ConnectedPeer): Unit =
    networkControllerRef ! PenalizePeer(peer.connectionId.remoteAddress, PenaltyType.NonDeliveryPenalty)

  override protected def penalizeMaliciousPeer(peer: ConnectedPeer): Unit =
    networkControllerRef ! PenalizePeer(peer.connectionId.remoteAddress, PenaltyType.PermanentPenalty)

  private def withNodeView[T](f: ReadableNodeView => T): Future[T] = {
    import akka.actor.typed.scaladsl.AskPattern._
    import akka.actor.typed.scaladsl.adapter._

    import scala.concurrent.duration._
    implicit val timeout: Timeout = Timeout(10.seconds)
    implicit val typedSystem: akka.actor.typed.ActorSystem[_] = context.system.toTyped
    viewHolderRef.askWithStatus[T](NodeViewHolder.ReceivableMessages.Read(f, _))
  }

  /**
   * Process sync info coming from another node
   *
   * @param syncInfo a set of modifier ids from the tip of the remote peers chain
   * @param remote remote peer that sent the message
   */
  private def gotRemoteSyncInfo(syncInfo: BifrostSyncInfo, remote: ConnectedPeer): Unit =
    withNodeView(state => (state.history.continuationIds(syncInfo, desiredInvObjects), state.history.compare(syncInfo)))
      .foreach { case (ext, comparison) =>
        if (!(ext.nonEmpty || comparison != Younger))
          log.warn("Extension is empty while comparison is younger")

        statusTracker.updateStatus(remote, comparison)

        comparison match {
          case Unknown =>
            //todo: should we ban peer if its status is unknown after getting info from it?
            log.warn("Peer status is still unknown")

          case Nonsense =>
            log.warn("Got nonsense")

          case Younger | Fork =>
            log.debug(
              s"Comparison with $remote having starting points ${idsToString(syncInfo.startingPoints)}. " +
              s"Comparison result is $comparison. Sending extension of length ${ext.length}"
            )
            log.debug(s"Extension ids: ${idsToString(ext)}")

            sendExtension(remote, comparison, ext)

          /** does nothing for `Equal` and `Older` */
          case _ =>
        }
      }

  /**
   * Send history extension to the (less developed) peer 'remote' which does not have it.
   *
   * @param remote remote peer ti send the message to
   * @param status CURRENTLY UNUSED (JAA - 2020.09.06)
   * @param ext the sequence of modifiers to send to the remote peer
   */
  def sendExtension(
    remote: ConnectedPeer,
    status: HistoryComparisonResult,
    ext:    Seq[(ModifierTypeId, ModifierId)]
  ): Unit =
    ext.groupBy(_._2.getModType).view.mapValues(_.map(_._2)).foreach { case (mid, mods) =>
      val msg = Message(invSpec, Right(InvData(mid, mods)), None)
      networkControllerRef ! SendToNetwork(msg, SendToPeer(remote))
    }

  /**
   * Process object ids coming from other node.
   *
   * @param invData inventory data (a sequence of modifier ids)
   * @param remote remote peer that sent the message
   */
  private def gotRemoteInventory(invData: InvData, remote: ConnectedPeer): Unit =
    withNodeView(view =>
      invData.typeId match {
        case Transaction.modifierTypeId =>
          invData.ids.filter(mid => deliveryTracker.status(mid, view.memPool) == ModifiersStatus.Unknown)
        case _ =>
          invData.ids.filter(mid => deliveryTracker.status(mid, view.history) == ModifiersStatus.Unknown)
      }
    )
      .foreach(newModifierIds =>
        if (newModifierIds.nonEmpty) requestDownload(invData.typeId, newModifierIds, Some(remote))
      )

  /**
   * Our node needs modifiers of type `modifierTypeId` with ids `modifierIds`
   * but a peer that can deliver may be unknown
   */
  protected def requestDownload(
    modifierTypeId:      ModifierTypeId,
    modifierIds:         Seq[ModifierId],
    peer:                Option[ConnectedPeer],
    previouslyRequested: Boolean = false
  ): Unit = {

    val msg = Message(requestModifierSpec, Right(InvData(modifierTypeId, modifierIds)), None)
    val sendStrategy = peer match {
      case Some(remote) => SendToPeer(remote)
      case None         => SendToRandom
    }

    /** boolean to control whether there may already be an entry in deliveryTracker for these modifiers */
    if (!previouslyRequested) deliveryTracker.setRequested(modifierIds, modifierTypeId, peer)

    /** send out our request to the network using the determined strategy */
    networkControllerRef ! SendToNetwork(msg, sendStrategy)
  }

  /**
   * Process a remote peer asking for objects by their ids
   *
   * @param invData the set of modifiers ids that the peer would like to have sent to them
   * @param remote remote peer that sent the message
   */
  private def gotModifierRequest(invData: InvData, remote: ConnectedPeer): Unit =
    withNodeView(view =>
      invData.typeId match {
        case Transaction.modifierTypeId => view.memPool.getAll(invData.ids)
        case Block.modifierTypeId       => invData.ids.flatMap(id => view.history.modifierById(id))
      }
    )
      .foreach { objs =>
        log.debug(
          s"Requested ${invData.ids.length} modifiers ${invData.toString}, " +
          s"sending ${objs.length} modifiers ${idsToString(invData.typeId, objs.map(_.id))} "
        )

        self ! ResponseFromLocal(remote, invData.typeId, objs)
      }

  /**
   * Process modifiers received from a remote peer
   *
   * @param data modifier data that was previously requested from a remote peer
   * @param remote remote peer that sent the message
   */
  private def gotRemoteModifiers(data: ModifiersData, remote: ConnectedPeer): Unit = {

    val typeId = data.typeId
    val modifiers = data.modifiers
    log.info(s"Got ${modifiers.size} modifiers of type $typeId from remote connected peer: $remote")
    log.trace(s"Received modifier ids ${modifiers.keySet}")

    /** filter out non-requested modifiers */
    val requestedModifiers = processSpam(remote, typeId, modifiers)
    import akka.actor.typed.scaladsl.adapter._
    implicit val typedSender: typed.ActorRef[Any] = context.self.toTyped
    modifierSerializers.get(typeId) match {
      case Some(serializer: BifrostSerializer[Transaction.TX]) if typeId == Transaction.modifierTypeId =>
        /** parse all transactions and send them to node view holder */
        val parsed = parseModifiers(requestedModifiers, serializer, remote)
        viewHolderRef.tell(NodeViewHolder.ReceivableMessages.WriteTransactions(parsed))

      case Some(serializer: BifrostSerializer[Block]) if typeId == Block.modifierTypeId =>
        /** parse all modifiers and put them to modifiers cache */
        val parsed = parseModifiers(requestedModifiers, serializer, remote)
        withNodeView(view => parsed.filter(validateAndSetStatus(view, remote, _)).collect { case b: Block => b })
          .foreach(valid =>
            if (valid.nonEmpty) viewHolderRef.tell(NodeViewHolder.ReceivableMessages.WriteBlocks(valid))
          )

      case _ =>
        log.error(s"Undefined serializer for modifier of type $typeId")
    }
  }

  /**
   * Parse modifiers using specified serializer, check that its id is equal to the declared one,
   * penalize misbehaving peer for every incorrect modifier,
   * call deliveryTracker.onReceive() for every correct modifier to update its status
   *
   * @return collection of parsed modifiers
   */
  private def parseModifiers[M <: NodeViewModifier](
    modifiers:  Map[ModifierId, Array[Byte]],
    serializer: BifrostSerializer[M],
    remote:     ConnectedPeer
  ): Iterable[M] =
    modifiers.flatMap { case (id, bytes) =>
      serializer.parseBytes(bytes) match {
        case Success(mod) if id == mod.id =>
          Some(mod)
        case _ =>
          /** Penalize peer and do nothing - it will be switched to correct state on CheckDelivery */
          penalizeMisbehavingPeer(remote)
          log.warn(s"Failed to parse modifier with declared id $id from $remote")
          None
      }
    }

  /**
   * Move `pmod` to `Invalid` if it is permanently invalid, to `Received` otherwise
   * @param remote remote peer that sent a block to our node
   * @param pmod a persistent modifier (block) received from a remote peer
   * @return boolean flagging whether the modifier was expected and ensuring it is syntactically valid
   */
  private def validateAndSetStatus(view: ReadableNodeView, remote: ConnectedPeer, pmod: Block): Boolean =
    view.history.applicableTry(pmod) match {
      case Failure(e: MalformedModifierError) =>
        log.warn(s"Modifier ${pmod.id} is permanently invalid", e)
        deliveryTracker.setInvalid(pmod.id)
        penalizeMisbehavingPeer(remote)
        false

      case _ =>
        deliveryTracker.setReceived(pmod.id, remote)
        true
    }

  protected def penalizeMisbehavingPeer(peer: ConnectedPeer): Unit =
    networkControllerRef ! PenalizePeer(peer.connectionId.remoteAddress, PenaltyType.MisbehaviorPenalty)

  /**
   * Get modifiers from remote peer,
   * filter out spam modifiers and penalize peer for spam
   *
   * @return ids and bytes of modifiers that were requested by our node
   */
  private def processSpam(
    remote:    ConnectedPeer,
    typeId:    ModifierTypeId,
    modifiers: Map[ModifierId, Array[Byte]]
  ): Map[ModifierId, Array[Byte]] = {

    val (requested, spam) = modifiers.partition { case (id, _) =>
      deliveryTracker.status(id) == Requested
    }

    if (spam.nonEmpty) {
      log.info(
        s"Spam attempt: peer $remote has sent a non-requested modifiers of type $typeId with ids" +
        s": ${spam.keys}"
      )
      penalizeSpammingPeer(remote)
    }
    requested
  }

  protected def penalizeSpammingPeer(peer: ConnectedPeer): Unit =
    networkControllerRef ! PenalizePeer(peer.connectionId.remoteAddress, PenaltyType.SpamPenalty)

  /**
   * Sends a sequence of local modifiers to a remote peer in chunks determined by the maximum packet size
   *
   * @param modType type of modifier that is being sent
   * @param mods sequence of local modifiers to be sent
   */
  @tailrec
  private def sendByParts(peer: ConnectedPeer, modType: ModifierTypeId, mods: Seq[(ModifierId, Array[Byte])]): Unit = {
    var size = 5

    /** message type id + message size */
    val batch = mods.takeWhile { case (_, modBytes) =>
      size += NodeViewModifier.modifierIdSize + 4 + modBytes.length
      size < settings.network.maxPacketSize
    }

    /** send the chunk of modifiers to the remote */
    val msg = Message(modifiersSpec, Right(ModifiersData(modType, batch.toMap)), None)
    networkControllerRef ! SendToNetwork(msg, SendToPeer(peer))

    /** check if any modifiers are remaining, if so, call this function again */
    val remaining = mods.drop(batch.length)
    if (remaining.nonEmpty) {
      sendByParts(peer, modType, remaining)
    }
  }
}

////////////////////////////////////////////////////////////////////////////////////
/////////////////////////////// COMPANION SINGLETON ////////////////////////////////

object NodeViewSynchronizer {

  val actorName = "nodeViewSynchronizer"

  case class RemoteMessageHandler(
    syncInfoSpec:        SyncInfoSpec,
    invSpec:             InvSpec,
    requestModifierSpec: RequestModifierSpec,
    modifiersSpec:       ModifiersSpec
  ) {

    def toSeq: Seq[MessageSpec[_]] = Seq(syncInfoSpec, invSpec, requestModifierSpec, modifiersSpec)
  }

  object Events {

    trait NodeViewSynchronizerEvent

    case object NoBetterNeighbour extends NodeViewSynchronizerEvent

    case object BetterNeighbourAppeared extends NodeViewSynchronizerEvent
  }

  object ReceivableMessages {

    trait PeerManagerEvent

    case class ResponseFromLocal[M <: NodeViewModifier](
      source:         ConnectedPeer,
      modifierTypeId: ModifierTypeId,
      localObjects:   Seq[M]
    )

    /**
     * Check delivery of modifier with type `modifierTypeId` and id `modifierId`.
     * `source` may be defined if we expect modifier from concrete peer or None if
     * we just need some modifier, but don't know who may it
     */
    case class CheckDelivery(source: Option[ConnectedPeer], modifierTypeId: ModifierTypeId, modifierId: ModifierId)

    case class OtherNodeSyncingStatus[SI <: SyncInfo](
      remote:    ConnectedPeer,
      status:    HistoryComparisonResult,
      extension: Seq[(ModifierTypeId, ModifierId)]
    )

    case class HandshakedPeer(remote: ConnectedPeer) extends PeerManagerEvent

    case class DisconnectedPeer(remote: InetSocketAddress) extends PeerManagerEvent

    /**
     * After application of batch of modifiers from cache to History, NodeViewHolder sends this message,
     * containing all just applied modifiers and cleared from cache
     */
    case class ModifiersProcessingResult[PMOD <: PersistentNodeViewModifier](applied: Seq[PMOD], cleared: Seq[PMOD])

    /** getLocalSyncInfo messages */
    case object SendLocalSyncInfo

    //todo: consider sending info on the rollback
  }
}

////////////////////////////////////////////////////////////////////////////////////
//////////////////////////////// ACTOR REF HELPER //////////////////////////////////

object NodeViewSynchronizerRef {

  def props(
    networkControllerRef:  ActorRef,
    viewHolderRef:         akka.actor.typed.ActorRef[NodeViewHolder.ReceivableMessage],
    settings:              AppSettings,
    appContext:            AppContext
  )(implicit timeProvider: TimeProvider): Props =
    Props(new NodeViewSynchronizer(networkControllerRef, viewHolderRef, settings, appContext))

}
