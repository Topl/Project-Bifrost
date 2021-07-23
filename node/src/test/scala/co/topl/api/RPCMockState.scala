package co.topl.api

import akka.actor.ActorSystem
import akka.actor.typed.scaladsl.adapter._
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.{HttpEntity, HttpMethods, HttpRequest, MediaTypes}
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.{RouteTestTimeout, ScalatestRouteTest}
import akka.pattern.ask
import akka.testkit.TestActorRef
import akka.util.{ByteString, Timeout}
import co.topl.akkahttprpc.ThrowableSupport.Standard._
import co.topl.consensus.KeyManager.{KeyView, StartupKeyView}
import co.topl.consensus._
import co.topl.http.HttpService
import co.topl.modifier.block.Block
import co.topl.network.message.BifrostSyncInfo
import co.topl.network.utils.NetworkTimeProvider
import co.topl.nodeView.history.History
import co.topl.nodeView.mempool.MemPool
import co.topl.nodeView.state.State
import co.topl.nodeView.{ActorNodeViewHolderInterface, NodeView, NodeViewHolder, TestableNodeViewHolder}
import co.topl.rpc.ToplRpcServer
import co.topl.utils.{DiskKeyFileTestHelper, NodeGenerators, TimeProvider}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.util.Try

trait RPCMockState
    extends AnyWordSpec
    with NodeGenerators
    with ScalatestRouteTest
    with BeforeAndAfterAll
    with DiskKeyFileTestHelper
    with ScalaFutures {

  type BSI = BifrostSyncInfo
  type PMOD = Block
  type HIS = History
  type MP = MemPool
  type ST = State

  implicit val timeout: Timeout = Timeout(10.seconds)

  implicit protected val routeTestTimeout: RouteTestTimeout = RouteTestTimeout(5.seconds)

  //TODO Fails when using rpcSettings
  override def createActorSystem(): ActorSystem = ActorSystem(settings.network.agentName)

  // Create Bifrost singleton actors

  // NOTE: Some of these actors are TestActors in order to access the underlying instance so that we can manipulate
  //       the state of the underlying instance while testing. Use with caution
  protected var keyManagerRef: TestActorRef[KeyManager] = _
  protected var forgerRef: akka.actor.typed.ActorRef[Forger.ReceivableMessage] = _

  protected var nodeViewHolderRef: akka.actor.typed.ActorRef[NodeViewHolder.ReceivableMessage] = _

  protected var km: KeyManager = _

  implicit protected var timeProvider: TimeProvider = _

  var rpcServer: ToplRpcServer = _

  var route: Route = _

  override def beforeAll(): Unit = {
    super.beforeAll()

    timeProvider = new NetworkTimeProvider(settings.ntp)(system.toTyped)

    keyManagerRef = TestActorRef(
      new KeyManager(settings, appContext)(appContext.networkType.netPrefix)
    )

    nodeViewHolderRef = system.toTyped.systemActorOf(
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
      NodeViewHolder.ActorName
    )

    forgerRef = system.toTyped.systemActorOf(
      Forger.behavior(
        settings.forging.blockGenerationDelay,
        settings.forging.minTransactionFee,
        settings.forging.forgeOnStartup,
        () => (keyManagerRef ? KeyManager.ReceivableMessages.GetKeyView).mapTo[KeyView],
        () =>
          (keyManagerRef ? KeyManager.ReceivableMessages.GenerateInitialAddresses)
            .mapTo[Try[StartupKeyView]]
            .flatMap(Future.fromTry),
        new ActorNodeViewHolderInterface(nodeViewHolderRef)(system.toTyped, implicitly[Timeout])
      ),
      Forger.ActorName
    )

    km = keyManagerRef.underlyingActor

    // manipulate the underlying actor state
    TestableNodeViewHolder.setNodeView(
      nodeViewHolderRef,
      _.copy(state = genesisState)
    )(system.toTyped)
    km.context.become(km.receive(keyRingCurve25519, Some(keyRingCurve25519.addresses.head)))

    rpcServer = {
      implicit val typedSystem: akka.actor.typed.ActorSystem[_] = system.toTyped
      val forgerInterface = new ActorForgerInterface(forgerRef)
      val keyManagerInterface = new ActorKeyManagerInterface(keyManagerRef)
      val nodeViewHolderInterface =
        new ActorNodeViewHolderInterface(nodeViewHolderRef)
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

    val httpService = HttpService(settings.rpcApi, rpcServer)
    route = httpService.compositeRoute
  }

  def httpPOST(jsonRequest: ByteString): HttpRequest =
    HttpRequest(
      HttpMethods.POST,
      entity = HttpEntity(MediaTypes.`application/json`, jsonRequest)
    ).withHeaders(RawHeader("x-api-key", "test_key"))

  protected def view(): NodeView =
    TestableNodeViewHolder.nodeViewOf(nodeViewHolderRef)(system.toTyped)
}
