package co.topl.attestation

import co.topl.utils.serialization.{ BifrostSerializer, BytesSerializable, Reader, Writer }
import com.google.common.primitives.Ints
import io.circe.syntax.EncoderOps
import io.circe.{ Decoder, Encoder, KeyDecoder, KeyEncoder }
import scorex.util.encode.Base58
import supertagged.TaggedType

import scala.util.{ Failure, Success, Try }

/**
 * Evidence content serves as a fingerprint (or commitment) of a particular proposition that is used to lock a box. Boxes
 * are locked with 'Evidence' which is the concatentation of a typePrefix ++ content. The type prefix denotes what type
 * of proposition the content references and the content serves as the commitment that a proposition will be checked
 * against when a box is being unlocked during a transaction.
 *
 * @param evBytes an array of bytes of length 'contentLength' (currently 32 bytes) generated from a proposition
 */
final class Evidence private (private val evBytes: Array[Byte]) extends BytesSerializable {
  override type M = Evidence
  override def serializer: BifrostSerializer[Evidence] = Evidence

  override def toString: String = Base58.encode(bytes)

  override def equals(obj: Any): Boolean = obj match {
    case ec: Evidence => bytes sameElements ec.bytes
    case _            => false
  }

  override def hashCode(): Int = Ints.fromByteArray(bytes)
}


object Evidence extends BifrostSerializer[Evidence] {
  // below are types and values used enforce the behavior of evidence
  type EvidenceTypePrefix = Byte

  object EvidenceContent extends TaggedType[Array[Byte]]
  type EvidenceContent = EvidenceContent.Type

  val contentLength = 32             //bytes (this is generally the output of a Blake2b-256 bit hash)
  val size: Int = 1 + contentLength  //length of typePrefix + contentLength

  def apply(typePrefix: EvidenceTypePrefix, content: EvidenceContent): Evidence = {
    fromBytes(typePrefix +: content) match {
      case Success(ec) => ec
      case Failure(ex) => throw ex
    }
  }

  def apply(str: String): Evidence =
    Base58.decode(str).flatMap(fromBytes) match {
      case Success(ec) => ec
      case Failure(ex) => throw ex
    }

  private def fromBytes (byteArray: Array[Byte]): Try[Evidence] = Try {
    require(byteArray.length == size, s"Incorrect length of input byte array when constructing evidence")
    new Evidence(byteArray)
  }

  override def serialize ( obj: Evidence, w: Writer ): Unit =
    w.putBytes(obj.evBytes)

  override def parse(r:  Reader): Evidence = {
    val evBytes = r.getBytes(size)
    new Evidence(evBytes)
  }

  // see circe documentation for custom encoder / decoders
  // https://circe.github.io/circe/codecs/custom-codecs.html
  implicit val jsonEncoder: Encoder[Evidence] = ( ec: Evidence) => ec.toString.asJson
  implicit val jsonKeyEncoder: KeyEncoder[Evidence] = ( ec: Evidence) => ec.toString
  implicit val jsonDecoder: Decoder[Evidence] = Decoder.decodeString.map(apply)
  implicit val jsonKeyDecoder: KeyDecoder[Evidence] = (str: String) => Some(apply(str))
}



