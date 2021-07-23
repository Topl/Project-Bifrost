package co.topl

import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, ActorSystem, Behavior, DispatcherSelector, PostStop}
import akka.actor.{ActorRef => CActorRef}
import akka.http.scaladsl.Http
import akka.io.{IO, Tcp}
import akka.pattern.ask
import akka.util.Timeout
import co.topl.akkahttprpc.{ThrowableData, ThrowableSupport}
import co.topl.consensus.KeyManager.{KeyView, StartupKeyView}
import co.topl.consensus._
import co.topl.http.HttpService
import co.topl.network._
import co.topl.network.utils.NetworkTimeProvider
import co.topl.nodeView._
import co.topl.rpc.ToplRpcServer
import co.topl.settings.{AppContext, AppSettings}
import co.topl.utils.NetworkType.NetworkPrefix
import co.topl.utils.TimeProvider
import io.circe.Encoder

import java.net.InetSocketAddress
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

/**
 * Heimdall is the guardian of Bifrost.  More specifically, it is the "guardian actor"
 * for the Bifrost application's ActorSystem.
 */
object Heimdall {

  sealed abstract class ReceivableMessage

  private case object NodeViewHolderReady extends ReceivableMessage

  private case object BindExternalTraffic extends ReceivableMessage
  private case class P2PTrafficBound(address: InetSocketAddress) extends ReceivableMessage

  private case class RPCTrafficBound(httpService: HttpService, binding: Http.ServerBinding) extends ReceivableMessage
  case class Fail(throwable: Throwable) extends ReceivableMessage

  /**
   * A guardian behavior which creates all of the child actors needed to run Bifrost.
   */
  def apply(settings: AppSettings, appContext: AppContext): Behavior[ReceivableMessage] =
    Behaviors.setup { implicit context =>
      implicit def system: ActorSystem[_] = context.system
      implicit val timeout: Timeout = Timeout(10.minutes)

      context.log.info("Initializing ProtocolVersioner and ConsensusStorage")
      protocolMngr = ProtocolVersioner(settings.application.version, settings.forging.protocolVersions)
      consensusStorage = ConsensusStorage(settings, appContext.networkType)

      context.log.info("Initializing KeyManager and NodeViewHolder")
      val (keyManagerRef, nodeViewHolderRef, timeProvider) = prepareNodeViewActor(settings, appContext)
      context.watch(keyManagerRef)
      context.watch(nodeViewHolderRef)
      context.pipeToSelf(new ActorNodeViewHolderInterface(nodeViewHolderRef).onReady()) {
        case Failure(exception) => Fail(exception)
        case Success(_)         => NodeViewHolderReady
      }
      awaitingNodeViewReady(settings, appContext, keyManagerRef, nodeViewHolderRef)(timeProvider)
    }

  /**
   * The state in which a KeyManager and NodeViewHolder exist, but the NodeViewHolder is still loading and can't
   * handle any traffic yet.  Heimdall delays creating the rest of the actors until the NodeViewHolder is ready.
   */
  private def awaitingNodeViewReady(
    settings:              AppSettings,
    appContext:            AppContext,
    keyManagerRef:         CActorRef,
    nodeViewRef:           ActorRef[NodeViewHolder.ReceivableMessage]
  )(implicit timeProvider: TimeProvider): Behavior[ReceivableMessage] =
    Behaviors.receivePartial {
      case (context, NodeViewHolderReady) =>
        implicit def ctx: ActorContext[ReceivableMessage] = context
        context.log.info(
          "Initializing PeerManager, NetworkController, Forger, MemPoolAuditor, PeerSynchronizer, and NodeViewSynchronizer"
        )
        val state = prepareActors(settings, appContext, keyManagerRef, nodeViewRef)

        context.watch(state.forger)
        context.watch(state.networkController)
        context.watch(state.peerSynchronizer)
        context.watch(state.nodeViewSynchronizer)
        context.watch(state.mempoolAuditor)

        context.self.tell(BindExternalTraffic)

        withActors(settings, appContext, state)
      case (_, Fail(throwable)) =>
        throw throwable
    }

  /**
   * The Heimdall state in which the child actors have been created but more initialization is needed.
   */
  private def withActors(
    settings:   AppSettings,
    appContext: AppContext,
    state:      ChildActorState
  ): Behavior[ReceivableMessage] =
    Behaviors.receivePartial {
      case (context, BindExternalTraffic) =>
        implicit val bindTimeout: Timeout = Timeout(10.seconds)
        context.pipeToSelf(
          state.networkController.ask(NetworkController.ReceivableMessages.BindP2P).mapTo[Future[Tcp.Event]].flatten
        ) {
          case Success(Tcp.Bound(address)) =>
            P2PTrafficBound(address)
          case Success(f: Tcp.CommandFailed) =>
            Fail(f.cause.getOrElse(new IllegalArgumentException(f.toString())))
          case Success(f) =>
            Fail(new IllegalArgumentException(f.toString))
          case Failure(exception) =>
            Fail(exception)
        }
        Behaviors.same

      case (context, P2PTrafficBound(p2pAddress)) =>
        context.log.info(s"${Console.YELLOW}P2P protocol bound to $p2pAddress${Console.RESET}")
        val service =
          httpService(settings, appContext, state.keyManager, state.forger, state.nodeViewHolder)(context.system)
        val httpHost = settings.rpcApi.bindAddress.getHostName
        val httpPort = settings.rpcApi.bindAddress.getPort

        /** trigger the HTTP server bind and check that the bind is successful. Terminate the application on failure */
        implicit val system: ActorSystem[_] = context.system
        context.pipeToSelf(Http().newServerAt(httpHost, httpPort).bind(service.compositeRoute)) {
          case Success(binding)   => RPCTrafficBound(service, binding)
          case Failure(exception) => Fail(exception)
        }
        Behaviors.same

      case (context, RPCTrafficBound(_, binding)) =>
        context.log.info(s"${Console.YELLOW}HTTP server bound to ${binding.localAddress}${Console.RESET}")

        context.log.info("Bifrost initialized")
        running(State(state, binding))

      case (_, Fail(reason)) =>
        throw reason
    }

  /**
   * The Heimdall state in which all of the child actors have been created, network traffic is bound, and
   * the application is in an operational state.
   */
  private def running(state: State): Behavior[ReceivableMessage] =
    Behaviors
      .receivePartial[ReceivableMessage] { case (_, Fail(throwable)) =>
        throw throwable
      }
      .receiveSignal { case (_, PostStop) =>
        state.httpBinding.unbind()
        Behaviors.same
      }

  private case class ChildActorState(
    peerManager:          CActorRef,
    networkController:    CActorRef,
    keyManager:           CActorRef,
    forger:               ActorRef[Forger.ReceivableMessage],
    nodeViewHolder:       ActorRef[NodeViewHolder.ReceivableMessage],
    mempoolAuditor:       CActorRef,
    peerSynchronizer:     CActorRef,
    nodeViewSynchronizer: CActorRef
  )

  private case class State(
    childActorState: ChildActorState,
    httpBinding:     Http.ServerBinding
  )

  private def httpService(
    settings:          AppSettings,
    appContext:        AppContext,
    keyManagerRef:     CActorRef,
    forgerRef:         ActorRef[Forger.ReceivableMessage],
    nodeViewHolderRef: ActorRef[NodeViewHolder.ReceivableMessage]
  )(implicit system:   ActorSystem[_]): HttpService = {
    import system.executionContext

    implicit val networkPrefix: NetworkPrefix =
      appContext.networkType.netPrefix

    implicit val throwableEncoder: Encoder[ThrowableData] =
      ThrowableSupport.verbose(settings.rpcApi.verboseAPI)

    implicit val askTimeout: Timeout =
      Timeout(settings.rpcApi.timeout)

    val forgerInterface = new ActorForgerInterface(forgerRef)
    val keyManagerInterface = new ActorKeyManagerInterface(keyManagerRef)

    val nodeViewHolderInterface =
      new ActorNodeViewHolderInterface(nodeViewHolderRef)

    val bifrostRpcServer: ToplRpcServer = {
      import co.topl.rpc.handlers._
      new ToplRpcServer(
        ToplRpcHandlers(
          new DebugRpcHandlerImpls(nodeViewHolderInterface, keyManagerInterface),
          new UtilsRpcHandlerImpls,
          new NodeViewRpcHandlerImpls(appContext, nodeViewHolderInterface),
          new TransactionRpcHandlerImpls(nodeViewHolderInterface),
          new AdminRpcHandlerImpls(forgerInterface, keyManagerInterface)
        ),
        appContext
      )
    }

    HttpService(settings.rpcApi, bifrostRpcServer)
  }

  private def prepareNodeViewActor(settings: AppSettings, appContext: AppContext)(implicit
    context:                                 ActorContext[ReceivableMessage]
  ): (CActorRef, ActorRef[NodeViewHolder.ReceivableMessage], TimeProvider) = {
    import context.executionContext
    implicit val networkPrefix: NetworkPrefix = appContext.networkType.netPrefix
    implicit val timeProvider: NetworkTimeProvider = new NetworkTimeProvider(settings.ntp)(context.system)

    val keyManagerRef = context.actorOf(KeyManagerRef.props(settings, appContext), KeyManager.actorName)

    val nodeViewHolderRef = {
      implicit val getKeyViewAskTimeout: Timeout = Timeout(10.seconds)
      context.spawn(
        NodeViewHolder(
          settings,
          () =>
            NodeView.persistent(
              settings,
              appContext.networkType,
              () =>
                (keyManagerRef ? KeyManager.ReceivableMessages.GenerateInitialAddresses)
                  .mapTo[Try[StartupKeyView]]
                  .flatMap(Future.fromTry)
            )
        ),
        NodeViewHolder.ActorName,
        DispatcherSelector.fromConfig("bifrost.application.node-view.dispatcher")
      )
    }

    (keyManagerRef, nodeViewHolderRef, timeProvider)

  }

  private def prepareActors(
    settings:          AppSettings,
    appContext:        AppContext,
    keyManagerRef:     CActorRef,
    nodeViewHolderRef: ActorRef[NodeViewHolder.ReceivableMessage]
  )(implicit
    context:      ActorContext[ReceivableMessage],
    timeProvider: TimeProvider
  ): ChildActorState = {

    import context.executionContext

    implicit val system: ActorSystem[_] = context.system
    implicit val networkPrefix: NetworkPrefix = appContext.networkType.netPrefix

    val peerManager = context.actorOf(PeerManagerRef.props(settings, appContext), PeerManager.actorName)
    val networkController = context.actorOf(
      NetworkControllerRef.props(settings, peerManager, appContext, IO(Tcp)(context.system.toClassic))
    )

    val forgerRef = {
      implicit val timeout: Timeout = Timeout(10.seconds)
      context.spawn(
        Forger.behavior(
          settings.forging.blockGenerationDelay,
          settings.forging.minTransactionFee,
          settings.forging.forgeOnStartup,
          () => (keyManagerRef ? KeyManager.ReceivableMessages.GetKeyView).mapTo[KeyView],
          () =>
            (keyManagerRef ? KeyManager.ReceivableMessages.GenerateInitialAddresses)
              .mapTo[Try[StartupKeyView]]
              .flatMap(Future.fromTry),
          new ActorNodeViewHolderInterface(nodeViewHolderRef)
        ),
        Forger.ActorName
      )
    }

    val mempoolAuditor = context.actorOf(
      MempoolAuditorRef.props(settings, appContext, nodeViewHolderRef, networkController),
      MempoolAuditor.actorName
    )

    val peerSynchronizer = context.actorOf(
      PeerSynchronizerRef.props(networkController, peerManager, settings, appContext),
      PeerSynchronizer.actorName
    )

    val nodeViewSynchronizer = context.actorOf(
      NodeViewSynchronizerRef.props(networkController, nodeViewHolderRef, settings, appContext),
      NodeViewSynchronizer.actorName
    )

    ChildActorState(
      peerManager,
      networkController,
      keyManagerRef,
      forgerRef,
      nodeViewHolderRef,
      mempoolAuditor,
      peerSynchronizer,
      nodeViewSynchronizer
    )
  }

}
