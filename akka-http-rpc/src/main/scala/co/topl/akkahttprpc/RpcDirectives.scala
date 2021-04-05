package co.topl.akkahttprpc

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import cats.implicits._
import co.topl.akkahttprpc.RpcErrorCodecs._
import de.heikoseeberger.akkahttpcirce.ErrorAccumulatingCirceSupport._
import io.circe.generic.semiauto._
import io.circe.syntax._
import io.circe.{Decoder, Encoder, _}

import java.util.UUID
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.language.implicitConversions

trait RpcDirectives {

  import RpcEncoders._

  implicit def rejectionHandler(implicit throwableEncoder: Encoder[ThrowableData]): RejectionHandler =
    RejectionHandler
      .newBuilder()
      .handle { case RpcErrorRejection(e: RpcError[_]) =>
        complete(rpcErrorToFailureResponse(e).asJson)
      }
      .result()

  private[akkahttprpc] def recoverToRpcError(implicit
    throwableEncoder: Encoder[ThrowableData]
  ): PartialFunction[Throwable, CustomError] = { case e =>
    CustomError.fromThrowable(-32099, "Unknown server error", e)
  }

  implicit def rpcErrorAsRejection(rpcError: RpcError[_]): Rejection =
    RpcErrorRejection(rpcError)

  def rpcRoute[RpcParams: Decoder, SuccessResult: Encoder](
    method:                    String,
    handler:                   Rpc.Handler[RpcParams, SuccessResult]
  )(implicit throwableEncoder: Encoder[ThrowableData]): Route =
    rpcContextWithParams[RpcParams](method).tapply { case (context, params) =>
      implicit val c: RpcContext = context
      extractExecutionContext { implicit ec: ExecutionContext =>
        onComplete(
          handler(params)
            .map(r => SuccessRpcResponse(context.id, context.jsonrpc, r.asJson))
            .value
        )(_.toEither.leftMap(recoverToRpcError).flatMap(identity).fold(reject(_), completeRpc))
      }
    }

  def rpcContextWithParams[RpcParams: Decoder](method: String): Directive[(RpcContext, RpcParams)] =
    rpcContext.flatMap(ctx => filterRpcMethod(method)(ctx).tmap(_ => ctx)).flatMap { implicit ctx =>
      rpcParameters[RpcParams].map((ctx, _))
    }

  def filterRpcMethod(method: String)(implicit rpcContext: RpcContext): Directive0 =
    if (rpcContext.method == method) pass else reject(MethodNotFoundError(rpcContext.method))

  def completeRpc(rawRpcResponse: RpcResponse): StandardRoute =
    complete(rawRpcResponse)

  def completeRpc(
    error:               RpcError[_]
  )(implicit rpcContext: RpcContext, throwableEncoder: Encoder[ThrowableData]): StandardRoute =
    completeRpc(rpcErrorToFailureResponse(rpcContext, error))

  def rpcParameters[RpcParams: Decoder](implicit context: RpcContext): Directive1[RpcParams] =
    context.params
      .as[RpcParams]
      .leftMap(InvalidParametersError(_): Rejection)
      .fold(reject(_), provide)

  def rpcContext: Directive1[RpcContext] =
    post
      .tflatMap(_ =>
        extractStrictEntity(5.seconds)
          .map(_.data.utf8String)
          .flatMap(
            parser
              .parse(_)
              .leftMap(_ => ParseError: RpcError[_])
              .filterOrElse(_.isObject, ParseError)
              .flatMap(_.as[RpcContext].leftMap(InvalidRequestError.apply))
              .fold(reject(_), provide)
          )
      )

  private def rpcErrorToFailureResponse(
    error:                     RpcError[_]
  )(implicit throwableEncoder: Encoder[ThrowableData]): FailureRpcResponse =
    FailureRpcResponse(
      UUID.randomUUID().toString,
      "2.0",
      FailureRpcResponse.Error(error.code, error.message, Some(error.asJson))
    )

  private def rpcErrorToFailureResponse(request: RpcContext, error: RpcError[_])(implicit
    throwableEncoder:                            Encoder[ThrowableData]
  ): FailureRpcResponse =
    FailureRpcResponse(
      request.id,
      request.jsonrpc,
      FailureRpcResponse.Error(error.code, error.message, (Some(error.asJson)))
    )
}

object RpcDirectives extends RpcDirectives

object RpcEncoders {

  implicit val decodeRpcContext: Decoder[RpcContext] =
    deriveDecoder[RpcContext]
      .map(context =>
        context.copy(params =
          context.params.arrayOrObject[Json](context.params, _.headOption.getOrElse(Json.Null), _ => context.params)
        )
      )

  implicit val encodeFailureRpcResponseError: Encoder[FailureRpcResponse.Error] =
    deriveEncoder[FailureRpcResponse.Error]

  implicit val encodeSuccessResponse: Encoder[SuccessRpcResponse] =
    deriveEncoder[SuccessRpcResponse]

  implicit val encodeFailureResponse: Encoder[FailureRpcResponse] =
    deriveEncoder[FailureRpcResponse]

  implicit val encodeRawRpcResponse: Encoder[RpcResponse] =
    Encoder.instance {
      case s: SuccessRpcResponse => s.asJson
      case f: FailureRpcResponse => f.asJson
    }
}

case class RpcErrorRejection(rpcError: RpcError[_]) extends Rejection

trait ToRpcError[T] {
  def toRpcError(t: T): RpcError[_]
}