package co.topl.nodeView.state.box

import co.topl.attestation.Evidence
import co.topl.attestation.proposition.PublicKeyCurve25519Proposition
import co.topl.nodeView.state.box.Box.BoxType
import io.circe.syntax._
import io.circe.{ Decoder, Encoder, HCursor }

case class AssetBox (override val evidence: Evidence,
                     override val nonce   : Box.Nonce,
                     override val value   : TokenBox.Value,
                     assetCode            : String,
                     issuer               : PublicKeyCurve25519Proposition,
                     data                 : String
                    ) extends TokenBox(evidence, nonce, value, AssetBox.boxTypePrefix)

object AssetBox {
  val boxTypePrefix: BoxType = 3: Byte

  implicit val jsonEncoder: Encoder[AssetBox] = { box: AssetBox =>
    (Box.jsonEncode(box) ++ Map(
      "issuer" -> box.issuer.asJson,
      "data" -> box.data.asJson,
      "nonce" -> box.nonce.toString.asJson)
      ).asJson
  }

  implicit val jsonDecoder: Decoder[AssetBox] = ( c: HCursor ) =>
    for {
      b <- Box.jsonDecode(c)
      assetCode <- c.downField("assetCode").as[String]
      issuer <- c.downField("issuer").as[PublicKeyCurve25519Proposition]
      data <- c.downField("data").as[String]
    } yield {
      val (proposition, nonce, value) = b
      AssetBox(proposition, nonce, value, assetCode, issuer, data)
    }
}
