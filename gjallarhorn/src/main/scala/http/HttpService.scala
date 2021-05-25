package http

import akka.http.scaladsl.model.headers.{HttpOrigin, Origin}
import akka.http.scaladsl.model.{ContentTypes, HttpEntity}
import akka.http.scaladsl.server.Route
import akka.util.Timeout
import co.topl.crypto.hash.blake2b256
import co.topl.crypto.hash.digest.Digest32
import co.topl.utils.encode.Base58
import io.circe.Json
import io.circe.parser.parse
import requests.{ApiResponse, ApiRoute, ErrorResponse, SuccessResponse}
import settings.RPCApiSettings

import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success, Try}

/**
 * Handles api requests
 * @param apiServices the set of ApiRoutes to send requests to
 * @param settings the app Api settings
 */
final case class HttpService(apiServices: Seq[ApiRoute], settings: RPCApiSettings) extends CorsSupport {

  private val timeout: Timeout = Timeout(settings.timeout)

  private lazy val apiKeyHash: Option[Array[Byte]] =
    if (settings.apiKeyHash == "") None
    else Base58.decode(settings.apiKeyHash).toOption

  private val apiServiceHandlers: PartialFunction[(String, Vector[Json], String), Future[Json]] =
    apiServices
      .map(_.handlers)
      .reduce(_ orElse _)

  /** the primary route that the HTTP service is bound to in GjallarhornApp */
  val compositeRoute: Route =
    corsHandler {
      status ~ basicRoute
    }

  /** a static route for exposing an HTML webpage with the node status */
  private def status: Route =
    path("status")(getFromResource("ui/build/index.html")) ~
    getFromResourceDirectory("ui/build")

  /** the api controller, this will parse the JSON body and target the appropriate service for handling the request */
  private def basicRoute: Route = path("") {
    entity(as[String]) { body =>
      withAuth {
        postJsonRoute {
          var reqId = ""
          parse(body) match {
            case Left(failure) => throw new Error(s"failed to parse JSON request: $body. Due to ${failure.getCause}")
            case Right(request) =>
              val futureResponse: Try[Future[Json]] = Try {
                val id = (request \\ "id").head.asString.get
                reqId = id
                require((request \\ "jsonrpc").head.asString.get == "2.0")

                val params = (request \\ "params").head.asArray.get
                require(params.size <= 1, s"size of params is ${params.size}")

                val method = (request \\ "method").head.asString.get

                if (apiServiceHandlers.isDefinedAt(method, params, id)) apiServiceHandlers.apply(method, params, id)
                else throw new Exception("Service handler not found for method: " + method)
              }

              // await result of future from handler
              futureResponse map { response =>
                Await.result(response, timeout.duration)
              } match {
                case Success(resp) => SuccessResponse(resp, reqId)
                case Failure(e)    => ErrorResponse(e, 500, reqId, verbose = settings.verboseAPI)
              }
          }
        }
      }
    }
  }

  /**
   * Formats the JSON-RPC post request (this is the primary method of communication)
   * @param fn outgoing response
   * @return route for post request
   */
  private def postJsonRoute(fn: ApiResponse): Route = post {
    complete(
      HttpEntity(ContentTypes.`application/json`, fn.toJson.spaces2)
    )
  }

  /** List of acceptable domains for API route */
  val acceptableDomains: List[HttpOrigin] = List[HttpOrigin](
    HttpOrigin("http://localhost:9085"),
    HttpOrigin("http://localhost:9585"),
    HttpOrigin("http://localhost:3000"),
    HttpOrigin("http://localhost:63342")
  )

  /** Helper route to wrap the handling of origin and API key authentication */
  def withAuth(route: => Route): Route =
    headerValueByType(Origin) { origin =>
      if (acceptableDomains.contains(origin.origins.head)) {
        optionalHeaderValueByName("x-api-key") { keyOpt =>
          if (isValid(keyOpt)) route
          else complete(HttpEntity(ContentTypes.`application/json`, "Provided API key is not correct"))
        }
      } else complete(HttpEntity(ContentTypes.`application/json`, "The request came from an unapproved host."))
    }

  /**
   * Performs the check of an incoming api key
   * @param keyOpt api key specified in header
   * @return true if api key is valid, false otherwise
   */
  private def isValid(keyOpt: Option[String]): Boolean = {
    lazy val keyHash: Option[Digest32] = keyOpt.map(k => blake2b256.hash(k.getBytes))
    (apiKeyHash, keyHash) match {
      case (None, _)                      => true
      case (Some(expected), Some(passed)) => expected sameElements passed.value
      case _                              => false
    }
  }

}
