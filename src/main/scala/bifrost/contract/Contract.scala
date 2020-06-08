package bifrost.contract

import java.time.Instant

import bifrost.contract.modules.BaseModuleWrapper
import bifrost.exceptions.{InvalidProvidedContractArgumentsException, JsonParsingException}
import io.circe._
import io.circe.parser._
import io.circe.syntax._
import jdk.nashorn.api.scripting.{NashornScriptEngine, NashornScriptEngineFactory}
import scorex.core.transaction.box.proposition.PublicKey25519Proposition
import scorex.crypto.encode.Base58

import scala.collection.mutable
import scala.util.{Failure, Success, Try}


case class Contract(parties: Map[PublicKey25519Proposition, String],
                    lastUpdated: Long,
                    id: Array[Byte],
                    agreement: Json) {

  val MIN_PARTIES: Int = 2
  val MAX_PARTIES: Int = 1024

  if (parties.size < MIN_PARTIES || parties.size > MAX_PARTIES) {
    throw new InvalidProvidedContractArgumentsException("An invalid number of parties was specified for the contract " +
      "(must be between 2 and 1024).")
  }

  lazy val jsre: NashornScriptEngine = new NashornScriptEngineFactory()
    .getScriptEngine
    .asInstanceOf[NashornScriptEngine]

  val agreementObj: Agreement = agreement.as[Agreement] match {
    case Right(a) => a
    case Left(_) => throw new JsonParsingException("Was unable to parse a valid agreement from provided JSON")
  }

  jsre.eval(BaseModuleWrapper.objectAssignPolyfill)

  //noinspection ScalaStyle
  def applyFunction(methodName: String)(params: Array[String]): Try[(Contract, Option[Json])] = Try {

    jsre.eval(agreementObj.core.initjs)
    jsre.eval(s"var c = ${agreementObj.core.name}.fromJSON('${agreementObj.core.state.noSpaces}')")

    val parameterString: String = params
      .tail
      .foldLeft(params
        .headOption
        .getOrElse(""))((agg, cur) => s"$agg, $cur")

    val update =
      s"""
         |var res = c["$methodName"]($parameterString);
         |if(res instanceof ${agreementObj.core.name}) {
         |  JSON.stringify({
         |    "__returnedUpdate": true,
         |    "contract": ${agreementObj.core.name}.toJSON(res)
         |  })
         |} else {
         |  JSON.stringify({
         |    "__returnedUpdate": false,
         |    "contract": ${agreementObj.core.name}.toJSON(c),
         |    "functionResult": res
         |  })
         |}
    """.stripMargin

    val result = parse(jsre.eval(update).asInstanceOf[String]).right.get

    val resultingContract = this.copy(
      agreement = agreementObj
        .copy(core = agreementObj
          .core
          .copy(state = (result \\ "contract").head))
        .asJson,
      lastUpdated = Instant.now.toEpochMilli
    )

    val functionReturnedUpdate = (result \\ "__returnedUpdate")
      .head
      .asBoolean
      .get

    val functionResult: Option[Json] = if (!functionReturnedUpdate) {
      Some((result \\ "functionResult").head)
    } else {
      None
    }

    (resultingContract, functionResult)
  }

  def getFromContract(property: String): Try[Json] = Try {
    val core = agreementObj.core
    jsre.eval(core.initjs)
    jsre.eval(s"var c = ${core.name}.fromJSON('${core.state.noSpaces}')")

    val update = s"c.$property"

    val res = jsre.eval(s"JSON.stringify($update)").asInstanceOf[String]

    parse(res) match {
      case Right(json: Json) => json
      case Left(_) => Json.Null
    }
  }

  lazy val json: Json = Map(
    "agreement" -> agreement,
    "parties" -> parties
      .map(p => {
        Base58.encode(p._1.pubKeyBytes) -> p._2.asJson
      })
      .asJson,
    "lastUpdated" -> lastUpdated.asJson,
    "id" -> Base58.encode(id).asJson
  ).asJson

}

object Contract {

  def apply(contractJson: Json, id: Array[Byte]): Contract = {
    val jsonMap: Map[String, Json] = contractJson
      .asObject
      .map(_.toMap)
      .get

    val parties: Map[PublicKey25519Proposition, String] = jsonMap("parties").asObject match {
      case Some(partiesObject) =>
        partiesObject
          .toMap
          .map {
            party =>
              val publicKey = Base58.decode(party._1).get
              val role = party._2.asString.get
              new PublicKey25519Proposition(publicKey) -> role
          }
      case None => throw new JsonParsingException(s"Error: ${jsonMap("parties")}")
    }

    new Contract(
      parties, // TODO #22 new PublicKey25519Proposition(Base58.decode(jsonMap("producer").asString.get).get),
      jsonMap("lastUpdated").asNumber.get.toLong.getOrElse(0L),
      id,
      jsonMap("agreement")
    )
  }

  def execute(c: Contract, methodName: String)
             (party: PublicKey25519Proposition)
             (args: JsonObject): Try[Either[Contract, Json]] = Try {

    val methodAttempt: Option[mutable.LinkedHashSet[String]] =
      ((c.agreement \\ "registry").head \\ methodName)
        .headOption
        .flatMap(_.as[mutable.LinkedHashSet[String]].toOption)

    methodAttempt match {
      case Some(params: mutable.LinkedHashSet[String]) =>

        val neededArgs: Array[String] = args
          .toMap
          .filterKeys(k => params.contains(k))
          .values
          .toArray
          .map(_.noSpaces)

        val res: Try[(Contract, Option[Json])] = c.applyFunction(methodName)(neededArgs)

        res match {
          case Success((c: Contract, None)) => Left(c)
          case Success((_, Some(j: Json))) => Right(j)
          case f: Failure[_] => throw f.exception
        }

      case _ => throw new MatchError(s"Could not find method <$methodName>")
    }
  }

  object Status extends Enumeration {
    type Status = Value
    val EXPIRED: Status = Value("expired")
    val COMPLETE: Status = Value("complete")
    val INITIALISED: Status = Value("initialised")
    val UNKNOWN: Status = Value("unknown")

    implicit val decodeStatus: Decoder[Status.Value] = Decoder.enumDecoder(Status)
    implicit val encodeStatus: Encoder[Status.Value] = Encoder.enumEncoder(Status)
  }

}