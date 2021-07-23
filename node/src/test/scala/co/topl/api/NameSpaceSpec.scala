package co.topl.api

import akka.actor.typed.scaladsl.adapter._
import akka.http.scaladsl.server.Route
import akka.util.{ByteString, Timeout}
import co.topl.akkahttprpc.MethodNotFoundError
import co.topl.akkahttprpc.ThrowableSupport.Standard._
import co.topl.consensus.{ActorForgerInterface, ActorKeyManagerInterface}
import co.topl.http.HttpService
import co.topl.nodeView.ActorNodeViewHolderInterface
import co.topl.rpc.ToplRpcServer
import co.topl.settings.{AppContext, AppSettings, StartupOpts}
import io.circe.parser.parse
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class NameSpaceSpec extends AnyWordSpec with Matchers with RPCMockState {

  def createRoute(args: Boolean*): Route = {
    val newRpcSettings: AppSettings = settings.copy(
      rpcApi = settings.rpcApi.copy(
        namespaceSelector = settings.rpcApi.namespaceSelector.copy(
          topl = args(0),
          util = args(1),
          admin = args(2),
          debug = args(3)
        )
      )
    )

    val newAppContext = new AppContext(newRpcSettings, StartupOpts(), None)

    val rpcServer: ToplRpcServer = {
      implicit val typedSystem: akka.actor.typed.ActorSystem[_] = system.toTyped
      val forgerInterface = new ActorForgerInterface(forgerRef)
      val keyManagerInterface = new ActorKeyManagerInterface(keyManagerRef)
      val nodeViewHolderInterface = new ActorNodeViewHolderInterface(nodeViewHolderRef)
      import co.topl.rpc.handlers._
      new ToplRpcServer(
        ToplRpcHandlers(
          new DebugRpcHandlerImpls(nodeViewHolderInterface, keyManagerInterface),
          new UtilsRpcHandlerImpls,
          new NodeViewRpcHandlerImpls(newAppContext, nodeViewHolderInterface),
          new TransactionRpcHandlerImpls(nodeViewHolderInterface),
          new AdminRpcHandlerImpls(forgerInterface, keyManagerInterface)
        ),
        newAppContext
      )
    }

    val newHttpService = HttpService(newRpcSettings.rpcApi, rpcServer)
    newHttpService.compositeRoute
  }

  val routeTrue: Route = createRoute(true, true, true, true)
  val routeFalse: Route = createRoute(false, false, false, false)
  val routeDefault: Route = createRoute(true, true, true, false)
  val randBools: Seq[Boolean] = (for (i <- 1 to 4) yield scala.util.Random.nextBoolean())
  val routeRandom: Route = createRoute(randBools: _*)

  "debug RPC with namespaces turned on" should {
    "Refuse debug requests with undefined method and return the correct errors" in {
      val requestBody = ByteString(s"""
           |{
           |   "jsonrpc": "2.0",
           |   "id": "1",
           |   "method": "debug_empty",
           |   "params": [{}]
           |}
        """.stripMargin)

      httpPOST(requestBody) ~> routeTrue ~> check {
        val res = parse(responseAs[String]) match { case Right(re) => re; case Left(ex) => throw ex }
        val code = res.hcursor.downField("error").get[Int]("code")
        val message = res.hcursor.downField("error").get[String]("message")

        code shouldEqual Right(MethodNotFoundError.Code)
        message shouldEqual Right("RPC Method Not Found")
      }
    }
  }

  "debug RPC with namespaces turned off" should {
    "Refuse debug requests with correct method and return the correct errors" in {
      val requestBody = ByteString(s"""
           |{
           |   "jsonrpc": "2.0",
           |   "id": "1",
           |   "method": "debug_myBlocks",
           |   "params": [{}]
           |}
        """.stripMargin)

      httpPOST(requestBody) ~> routeFalse ~> check {
        val res = parse(responseAs[String]) match { case Right(re) => re; case Left(ex) => throw ex }
        val code = res.hcursor.downField("error").get[Int]("code")
        val message = res.hcursor.downField("error").get[String]("message")
        val method = res.hcursor.downField("error").downField("data").get[String]("method")

        code shouldEqual Right(MethodNotFoundError.Code)
        message shouldEqual Right(MethodNotFoundError.Message)
        method shouldEqual Right("debug_myBlocks")
      }

      httpPOST(requestBody) ~> routeDefault ~> check {
        val res = parse(responseAs[String]) match { case Right(re) => re; case Left(ex) => throw ex }
        val code = res.hcursor.downField("error").get[Int]("code")
        val message = res.hcursor.downField("error").get[String]("message")
        val method = res.hcursor.downField("error").downField("data").get[String]("method")

        code shouldEqual Right(MethodNotFoundError.Code)
        message shouldEqual Right(MethodNotFoundError.Message)
        method shouldEqual Right("debug_myBlocks")
      }
    }
  }
}
