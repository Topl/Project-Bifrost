package co.topl.program

import io.circe.syntax._
import io.circe.{Decoder, Encoder, HCursor, Json}

import scala.util.{Success, Try}

/**
 * @param core                     The JavaScript program split into state and functions
 * @param terms                    An ExecutionBuilderTerms object that specifies the specific compensation terms
 * @param assetCode                The string identifier for this specific asset to be produced
 */
case class ExecutionBuilder(terms: ExecutionBuilderTerms, assetCode: String, core: ProgramPreprocessor) {

  lazy val json: Json = Map(
    "terms"     -> terms.json,
    "assetCode" -> assetCode.asJson,
    "core"      -> core.asJson,
    "state"     -> core.variables.asJson,
    "code"      -> core.code.asJson
  ).asJson

  override def toString: String = s"ExecutionBuilder(${json.toString})"

}

object ExecutionBuilder {

  implicit val encodeExecutionBuilder: Encoder[ExecutionBuilder] = (a: ExecutionBuilder) => a.json

  implicit val decodeExecutionBuilder: Decoder[ExecutionBuilder] = (c: HCursor) =>
    for {
      terms     <- c.downField("terms").as[ExecutionBuilderTerms]
      assetCode <- c.downField("assetCode").as[String]
      core      <- c.downField("core").as[ProgramPreprocessor]
    } yield ExecutionBuilder(terms, assetCode, core)

  def validate(a: ExecutionBuilder): Try[Unit] = Try {
    Success(())
  }
}
