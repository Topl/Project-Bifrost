package bifrost.modifier.box

import bifrost.crypto.FastCryptographicHash
import bifrost.modifier.box.proposition.PublicKey25519Proposition
import bifrost.state.ProgramId
import com.google.common.primitives.Longs
import io.circe.syntax._
import io.circe.{ Decoder, HCursor, Json }
import scorex.util.encode.Base58
import scorex.crypto.signatures.PublicKey

case class StateBox(override val proposition: PublicKey25519Proposition,
                    override val nonce: Long,
                    override val value: ProgramId,
                    state: Json //  JSON representation of JS Variable Declarations
                    ) extends ProgramBox(proposition, nonce, value) {

  override lazy val typeOfBox: String = "StateBox"

  override lazy val id: Array[Byte] = StateBox.idFromBox(proposition, nonce)

  override lazy val json: Json = Map(
    "id" -> Base58.encode(id).asJson,
    "type" -> typeOfBox.asJson,
    "proposition" -> Base58.encode(proposition.pubKeyBytes).asJson,
    "programId" -> value.toString.asJson,
    "state" -> state.asJson,
    "nonce" -> nonce.toString.asJson,
  ).asJson
}

object StateBox {

  def idFromBox[proposition <: PublicKey25519Proposition](prop: proposition, nonce: Long): Array[Byte] =
    FastCryptographicHash(prop.pubKeyBytes ++ "state".getBytes ++ Longs.toByteArray(nonce))

  // TODO: Jing - Check if this is used anywhere
  implicit val decodeStateBox: Decoder[StateBox] = (c: HCursor) => for {
    proposition <- c.downField("proposition").as[String]
    value <- c.downField("programId").as[String]
    state <- c.downField("state").as[Json]
    nonce <- c.downField("nonce").as[Long]
  } yield {
    val prop = PublicKey25519Proposition(proposition).get
    val programId = new ProgramId(Base58.decode(value).get)
    StateBox(prop, nonce, programId, state)
  }
}
