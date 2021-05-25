package co.topl.modifier.box

import cats.implicits._
import co.topl.attestation.Evidence
import co.topl.crypto.hash.Blake2b256
import co.topl.crypto.hash.digest.Digest32
import co.topl.crypto.hash.implicits._
import co.topl.utils.IdiomaticScalaTransition.implicits.toValidatedOps
import co.topl.utils.codecs.CryptoCodec.implicits._
import co.topl.utils.encode.Base58
import com.google.common.primitives.{Ints, Longs}
import io.circe.syntax.EncoderOps
import io.circe.{Decoder, Encoder, KeyDecoder, KeyEncoder}

import scala.util.{Failure, Success}

case class BoxId(hash: Digest32) {

  override def hashCode: Int = Ints.fromByteArray(hash.value)

  override def equals(obj: Any): Boolean = obj match {
    case obj: BoxId => obj.hash === hash
    case _          => false
  }

  override def toString: String = Base58.encode(hash)
}

object BoxId {

  val size: Int = Digest32.size // boxId is a 32 byte identifier

  def apply[T](box: Box[T]): BoxId = idFromEviNonce(box.evidence, box.nonce)

  def apply(id: String): BoxId =
    Base58.decode(id) match {
      case Success(id) =>
        Digest32.validated(id).map(BoxId(_)).getOrThrow()

      case Failure(ex) => throw ex
    }

  def apply(bytes: Array[Byte]): BoxId =
    Digest32.validated(bytes).map(BoxId(_)).getOrThrow()

  def idFromEviNonce(evidence: Evidence, nonce: Box.Nonce): BoxId =
    BoxId(Blake2b256.hash(evidence.bytes ++ Longs.toByteArray(nonce)).getOrThrow())

  implicit val jsonEncoder: Encoder[BoxId] = (id: BoxId) => id.toString.asJson
  implicit val jsonKeyEncoder: KeyEncoder[BoxId] = (id: BoxId) => id.toString
  implicit val jsonDecoder: Decoder[BoxId] = Decoder.decodeString.map(apply)
  implicit val jsonKeyDecoder: KeyDecoder[BoxId] = (id: String) => Some(apply(id))
}
