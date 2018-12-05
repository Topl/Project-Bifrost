package bifrost.contract

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.util.{ByteString, Timeout}
import bifrost.{BifrostApp, BifrostNodeViewHolder}
import bifrost.api.http.{AssetApiRoute, ContractApiRoute, WalletApiRoute}
import bifrost.forging.ForgingSettings
import bifrost.history.BifrostHistory
import bifrost.mempool.BifrostMemPool
import bifrost.network.BifrostNodeViewSynchronizer
import bifrost.scorexMod.GenericNodeViewHolder.{CurrentView, GetCurrentView}
import bifrost.state.BifrostState
import bifrost.wallet.BWallet
import com.oracle.truffle.api.CompilerDirectives
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.instrumentation.{EventContext, ExecutionEventListener}
import io.circe
import io.circe.Json
import org.graalvm.polyglot.management.ExecutionListener
import org.graalvm.polyglot.{Context, Value}

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success}

case class ValkyrieFunctions() {

  implicit lazy val settings = new ForgingSettings {
    override val settingsJSON: Map[String, circe.Json] = settingsFromFile(BifrostApp.settingsFilename)
  }

  implicit val actorSystem = ActorSystem(settings.agentName)
  implicit val materializer = ActorMaterializer()
  implicit val executionContext = actorSystem.dispatcher
  val nodeViewHolderRef: ActorRef = actorSystem.actorOf(Props(new BifrostNodeViewHolder(settings)))
  nodeViewHolderRef

  val assetRoute = AssetApiRoute(settings, nodeViewHolderRef)(actorSystem).route

  val walletRoute = WalletApiRoute(settings, nodeViewHolderRef)(actorSystem).route

  def assetHttpPOST(jsonRequest: ByteString): HttpRequest = {
    HttpRequest(
      HttpMethods.POST,
      uri = "/asset/",
      entity = HttpEntity(MediaTypes.`application/json`, jsonRequest)
    )
  }

  def walletHttpPOST(jsonRequest: ByteString): HttpRequest = {
    HttpRequest(
      HttpMethods.POST,
      uri = "/transfer/",
      entity = HttpEntity(MediaTypes.`application/json`, jsonRequest)
    )
  }

  implicit val timeout = Timeout(10.seconds)

  private def view() = Await.result((nodeViewHolderRef ? GetCurrentView)
    .mapTo[CurrentView[BifrostHistory, BifrostState, BWallet, BifrostMemPool]], 10.seconds)

  def createAsset(body: String): Unit = {
    val response: Future[HttpResponse] = Http().singleRequest(assetHttpPOST(ByteString(body)))
    response.onComplete {
      case Success(res) => println(res)
      case Failure(_) => println("Error completing request")
    }
  }

  def transferAsset(body: String): Unit = {
    val response: Future[HttpResponse] = Http().singleRequest(assetHttpPOST(ByteString(body)))
    response.onComplete {
      case Success(res) => println(res)
      case Failure(_) => println("Error completing request")
    }
  }

  def transferPoly(body: String): Unit = {
    val response: Future[HttpResponse] = Http().singleRequest(walletHttpPOST(ByteString(body)))
    response.onComplete {
      case Success(res) => println(res)
      case Failure(_) => println("Error completing request")
    }
  }
}

object ValkyrieFunctions {

  val reserved: String =
    s"""
       |var assetCreated, assetTransferred, polyTransferred;
       |
       |this.createAsset = function(publicKey, asset, amount) {
       |  return assetcreated;
       |}
       |
       |this.transferAsset = function(publicKey, asset, amount) {
       |  return assetTransferred;
       |}
       |
       |this.transferPoly = function(publicKey, amount) {
       |  return polyTransferred;
       |}
     """.stripMargin

  def createExecutionListener(context: Context): ExecutionEventListener = {

    val listener: ExecutionListener = ExecutionListener.newBuilder()
      .onEnter({
        e => val protocolFunction = e.getLocation.getCharacters.toString
          protocolFunction match {
            case "createAsset()" => println("success")
          }
      })
      .roots(true)
      .attach(context.getEngine)

    val truffleListener: ExecutionEventListener = new ExecutionEventListener {
      override def onEnter(context: EventContext, frame: VirtualFrame): Unit = ???

      override def onReturnValue(context: EventContext, frame: VirtualFrame, result: Any): Unit = {
        val source: String = context.getInstrumentedSourceSection.getCharacters.toString
        source match {
          case "assetCreated)" => CompilerDirectives.transferToInterpreter(); throw context.createUnwind("ac")
          case "assetTransferred" => CompilerDirectives.transferToInterpreter(); throw context.createUnwind("at")
          case "polyTransferred" => CompilerDirectives.transferToInterpreter(); throw context.createUnwind("pt")
        }
      }

      override def onReturnExceptional(context: EventContext, frame: VirtualFrame, exception: Throwable): Unit = ???

      import com.oracle.truffle.api.frame.VirtualFrame

      override def onUnwind(context: EventContext, frame: VirtualFrame, info: Object): Object = {
        info match {
          case "ac" => "Success"
          case "at" => "Success"
          case "pt" => "Success"
        }
      }
    }
    truffleListener
  }
}