package co.topl.modifier.box

import co.topl.attestation.Address
import co.topl.utils.Extensions.StringOps
import co.topl.utils.Int128
import co.topl.utils.StringDataTypes.Base58Data
import co.topl.utils.codecs.Int128Codec
import co.topl.utils.codecs.implicits._
import co.topl.utils.serialization.{BifrostSerializer, BytesSerializable, Reader, Writer}
import io.circe.syntax.EncoderOps
import io.circe.{Decoder, Encoder, HCursor}

sealed abstract class TokenValueHolder(val quantity: Int128) extends BytesSerializable {
  override type M = TokenValueHolder

  override def serializer: BifrostSerializer[TokenValueHolder] = TokenValueHolder
}

object TokenValueHolder extends BifrostSerializer[TokenValueHolder] {

  implicit val jsonEncoder: Encoder[TokenValueHolder] = {
    case v: SimpleValue => SimpleValue.jsonEncoder(v)
    case v: AssetValue  => AssetValue.jsonEncoder(v)
    case _              => throw new Error(s"No matching encoder found")
  }

  implicit val jsonDecoder: Decoder[TokenValueHolder] = { c: HCursor =>
    c.downField("type").as[String].map {
      case SimpleValue.valueTypeString => SimpleValue.jsonDecoder(c)
      case AssetValue.valueTypeString  => AssetValue.jsonDecoder(c)
    } match {
      case Right(v) => v
      case Left(ex) => throw ex
    }
  }

  override def serialize(obj: TokenValueHolder, w: Writer): Unit =
    obj match {
      case obj: SimpleValue =>
        w.put(SimpleValue.valueTypePrefix)
        SimpleValue.serialize(obj, w)

      case obj: AssetValue =>
        w.put(AssetValue.valueTypePrefix)
        AssetValue.serialize(obj, w)

      case _ => throw new Exception("Unanticipated TokenValueType type")
    }

  override def parse(r: Reader): TokenValueHolder =
    r.getByte() match {
      case SimpleValue.valueTypePrefix => SimpleValue.parse(r)
      case AssetValue.valueTypePrefix  => AssetValue.parse(r)
      case _                           => throw new Exception("Unanticipated Box Type")
    }
}

/* ----------------- */ /* ----------------- */ /* ----------------- */ /* ----------------- */ /* ----------------- */ /* ----------------- */

case class SimpleValue(override val quantity: Int128) extends TokenValueHolder(quantity)

object SimpleValue extends BifrostSerializer[SimpleValue] {
  val valueTypePrefix: Byte = 1: Byte
  val valueTypeString: String = "Simple"

  implicit val jsonEncoder: Encoder[SimpleValue] = { (value: SimpleValue) =>
    Map(
      "type"     -> valueTypeString.asJson,
      "quantity" -> Int128Codec.jsonEncoder(value.quantity)
    ).asJson
  }

  implicit val jsonDecoder: Decoder[SimpleValue] = (c: HCursor) =>
    for {
      quantity <- c.downField("quantity").as[Long]
    } yield SimpleValue(quantity)

  override def serialize(obj: SimpleValue, w: Writer): Unit =
    w.putInt128(obj.quantity)

  override def parse(r: Reader): SimpleValue =
    SimpleValue(r.getInt128())
}

/* ----------------- */ /* ----------------- */ /* ----------------- */ /* ----------------- */ /* ----------------- */
case class AssetValue(
  override val quantity: Int128,
  assetCode:             AssetCode,
  securityRoot:          SecurityRoot = SecurityRoot.empty,
  metadata:              Option[String] = None
) extends TokenValueHolder(quantity) {

  //TODO: Jing - do we keep this check?
  //  require(
  //    metadata
  //      .forall(
  //        _.getValidLatin1Bytes
  //          .getOrElse(throw new Exception("String is not valid Latin-1"))
  //          .length <= AssetValue.metadataLimit
  //      ),
  //    "Metadata string must be less than 128 Latin-1 characters"
  //  )
}

object AssetValue extends BifrostSerializer[AssetValue] {

  val valueTypePrefix: Byte = 2: Byte
  val valueTypeString: String = "Asset"

  // bytes (34 bytes for issuer Address + 8 bytes for asset short name)
  val assetCodeSize: Int = Address.addressSize + 8
  val metadataLimit: Byte = 127 // bytes of Latin-1 encoded string

  implicit val jsonEncoder: Encoder[AssetValue] = { (value: AssetValue) =>
    Map(
      "type"         -> valueTypeString.asJson,
      "quantity"     -> value.quantity.asJson(Int128Codec.jsonEncoder),
      "assetCode"    -> value.assetCode.asJson,
      "securityRoot" -> value.securityRoot.asJson,
      "metadata"     -> value.metadata.asJson
    ).asJson
  }

  implicit val jsonDecoder: Decoder[AssetValue] = (c: HCursor) =>
    for {
      quantity     <- c.get[Int128]("quantity")(Int128Codec.jsonDecoder)
      assetCode    <- c.downField("assetCode").as[AssetCode]
      securityRoot <- c.downField("securityRoot").as[Option[Base58Data]]
      metadata     <- c.downField("metadata").as[Option[String]]
    } yield {
      val sr = securityRoot match {
        case Some(data) => SecurityRoot.fromBase58(data)
        case None       => SecurityRoot.empty
      }

      AssetValue(quantity, assetCode, sr, metadata)
    }

  override def serialize(obj: AssetValue, w: Writer): Unit = {
    w.putInt128(obj.quantity)
    AssetCode.serialize(obj.assetCode, w)
    SecurityRoot.serialize(obj.securityRoot, w)
    w.putOption(obj.metadata) { (writer, metadata) =>
      writer.putByteString(metadata)
    }
  }

  override def parse(r: Reader): AssetValue = {
    val quantity = r.getInt128()
    val assetCode = AssetCode.parse(r)
    val securityRoot = SecurityRoot.parse(r)
    val metadata: Option[String] = r.getOption {
      r.getByteString()
    }

    AssetValue(quantity, assetCode, securityRoot, metadata)
  }
}
