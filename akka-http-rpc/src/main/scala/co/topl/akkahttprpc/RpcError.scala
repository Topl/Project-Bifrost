package co.topl.akkahttprpc

import cats.data.NonEmptyChain
import cats.implicits._
import io.circe.syntax._
import io.circe.{DecodingFailure, Encoder, Json}

sealed abstract class RpcError {
  def code: Int
  def message: String
}

case object ParseError extends RpcError {
  override val code: Int = -32700

  override val message: String = "Invalid JSON"
}

case class InvalidRequestError(decodingFailure: DecodingFailure) extends RpcError {

  override def code: Int = InvalidRequestError.Code

  override def message: String = InvalidRequestError.Message
}

object InvalidRequestError {
  val Code: Int = -32600

  val Message: String = "Invalid RPC Request Format"
}

case class MethodNotFoundError(method: String) extends RpcError {

  override def code: Int = MethodNotFoundError.Code

  override def message: String = MethodNotFoundError.Message
}

object MethodNotFoundError {
  case class Data(method: String)

  val Code: Int = -32601

  val Message: String = "RPC Method Not Found"
}

case class InvalidParametersError(parameterErrors: NonEmptyChain[InvalidParametersError.Error]) extends RpcError {

  override def code: Int = InvalidParametersError.Code

  override def message: String = InvalidParametersError.Message
}

object InvalidParametersError {

  def apply(decodingFailure: DecodingFailure): InvalidParametersError =
    InvalidParametersError(
      NonEmptyChain.one(Error(decodingFailure.history.map(_.show), decodingFailure.message, None))
    )

  def adhoc(reason: String, path: String*): InvalidParametersError =
    InvalidParametersError(
      NonEmptyChain.one(Error(path.toList, reason, None))
    )

  case class Error(path: List[String], message: String, data: Option[Json])
  case class Data(errors: NonEmptyChain[Error])

  val Code: Int = -32602

  val Message: String = "Invalid method parameter(s)"
}

case class InternalJsonRpcError(reason: String, throwable: Option[ThrowableData]) extends RpcError {
  override def code: Int = InternalJsonRpcError.Code

  override def message: String = InternalJsonRpcError.Message
}

object InternalJsonRpcError {
  case class Data(reason: String, throwable: Option[ThrowableData])

  val Code: Int = -32603

  val Message: String = "Internal JSON-RPC Error"
}

case class CustomError(code: Int, message: String, data: Json = Json.Null) extends RpcError

object CustomError {

  def fromThrowable(throwable: Throwable)(implicit
    throwableEncoder:          Encoder[ThrowableData]
  ): CustomError =
    CustomError(-32099, "Unknown server error", ThrowableData(throwable).asJson)

  def fromThrowable(code: Int, message: String, throwable: Throwable)(implicit
    throwableEncoder:     Encoder[ThrowableData]
  ): CustomError =
    CustomError(code, message, ThrowableData(throwable).asJson)
}

trait ThrowableData {
  def message: Option[String]
  def stackTrace: Option[NonEmptyChain[String]]
}

object ThrowableData {

  def apply(throwable: Throwable): ThrowableData =
    new ThrowableData {
      override def message: Option[String] = Option(throwable.getMessage)

      override def stackTrace: Option[NonEmptyChain[String]] =
        NonEmptyChain.fromSeq(throwable.getStackTrace.toIndexedSeq.map(_.toString))
    }

  def apply(m: Option[String], s: Option[NonEmptyChain[String]]): ThrowableData =
    new ThrowableData {
      override def message: Option[String] = m

      override def stackTrace: Option[NonEmptyChain[String]] = s
    }
}
