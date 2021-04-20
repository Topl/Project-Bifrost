package co.topl.api

import java.io.File
import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.{HttpEntity, HttpMethods, HttpRequest, MediaTypes}
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.testkit.TestActorRef
import akka.util.{ByteString, Timeout}
import co.topl.akkahttprpc.ThrowableSupport.Standard._
import co.topl.consensus.{ActorForgerInterface, ActorKeyManagerInterface, Forger, ForgerRef, KeyManager}
import co.topl.http.HttpService
import co.topl.modifier.block.Block
import co.topl.rpc.ToplRpcServer
import co.topl.network.message.BifrostSyncInfo
import co.topl.nodeView.history.History
import co.topl.nodeView.mempool.MemPool
import co.topl.nodeView.nodeViewHolder.TestableNodeViewHolder
import co.topl.nodeView.state.State
import co.topl.nodeView.ActorNodeViewHolderInterface
import co.topl.settings.{AppContext, AppSettings, StartupOpts}
import co.topl.utils.GenesisGenerators
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.duration.DurationInt

trait RPCMockState extends AnyWordSpec with GenesisGenerators with ScalatestRouteTest {

  type BSI = BifrostSyncInfo
  type PMOD = Block
  type HIS = History
  type MP = MemPool
  type ST = State

  val tempFile: File = createTempFile

  val rpcSettings: AppSettings = settings.copy(
    application = settings.application.copy(
      dataDir = Some(tempFile.getPath + "data")
    )
  )

  val genesisState: State = genesisState(rpcSettings)

  //TODO Fails when using rpcSettings
  override def createActorSystem(): ActorSystem = ActorSystem(settings.network.agentName)
  //override implicit val system: ActorSystem = createActorSystem()

  /* ----------------- */ /* ----------------- */ /* ----------------- */ /* ----------------- */ /* ----------------- */
  // save environment into a variable for reference throughout the application
  protected val appContext = new AppContext(rpcSettings, StartupOpts.empty, None)

  // Create Bifrost singleton actors

  // NOTE: Some of these actors are TestActors in order to access the underlying instance so that we can manipulate
  //       the state of the underlying instance while testing. Use with caution
  protected val keyManagerRef: TestActorRef[KeyManager] = TestActorRef(
    new KeyManager(rpcSettings, appContext)(system.getDispatcher, appContext.networkType.netPrefix)
  )
  protected val forgerRef: ActorRef = ForgerRef[HIS, ST, MP](Forger.actorName, rpcSettings, appContext, keyManagerRef)

  protected val nodeViewHolderRef: TestActorRef[TestableNodeViewHolder] = TestActorRef(
    new TestableNodeViewHolder(settings, appContext)(system.getDispatcher, appContext.networkType.netPrefix)
  )

  // Get underlying references
  private val nvh = nodeViewHolderRef.underlyingActor
  private val km = keyManagerRef.underlyingActor

  // manipulate the underlying actor state
  nvh.updateNodeViewPublicAccessor(updatedState = Some(genesisState))
  km.context.become(km.receive(keyRing, Some(keyRing.addresses.head)))

  /* ----------------- */ /* ----------------- */ /* ----------------- */ /* ----------------- */ /* ----------------- */

  implicit val timeout: Timeout = Timeout(10.seconds)

  val rpcServer: ToplRpcServer = {
    val forgerInterface = new ActorForgerInterface(forgerRef)
    val keyManagerInterface = new ActorKeyManagerInterface(forgerRef)
    val nodeViewHolderInterface = new ActorNodeViewHolderInterface(nodeViewHolderRef)
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

  private val httpService = HttpService(rpcSettings.rpcApi, rpcServer)
  val route: Route = httpService.compositeRoute

  def httpPOST(jsonRequest: ByteString): HttpRequest =
    HttpRequest(
      HttpMethods.POST,
      entity = HttpEntity(MediaTypes.`application/json`, jsonRequest)
    ).withHeaders(RawHeader("x-api-key", "test_key"))

  // this method returns modifiable instances of the node view components
  protected def view(): (History, State, MemPool) = nvh.nodeViewPublicAccessor
}
