package modifier

import attestation.Proposition
import co.topl.crypto.hash.blake2b256
import co.topl.crypto.hash.digest.Digest32
import co.topl.utils.codecs.AsBytes.implicits._
import co.topl.utils.encode.Base58
import com.google.common.primitives.Ints
import io.circe.syntax.EncoderOps
import io.circe.{Decoder, Encoder, KeyDecoder, KeyEncoder}
import io.estatico.newtype.macros.newtype
import modifier.ModifierId.ModifierTypeId
import utils.serialization.{BytesSerializable, GjalSerializer, Reader, Writer}

import scala.language.implicitConversions
import scala.util.{Failure, Success, Try}

/**
 * Helps to create the Id for a transaction.
 * @param value the value (as an array of bytes) to create id for.
 */
class ModifierId(private val value: Array[Byte]) extends BytesSerializable {

  require(value.length == ModifierId.size, s"Invalid size for ModifierId")

  type M = ModifierId
  lazy val serializer: GjalSerializer[ModifierId] = ModifierId

  def getIdBytes: Array[Byte] = value.tail
  def getModType: ModifierTypeId = ModifierTypeId(value.head)

  override def hashCode: Int = Ints.fromByteArray(value)

  override def equals(obj: Any): Boolean = obj match {
    case mId: ModifierId => mId.value sameElements value
    case _               => false
  }

  override def toString: String = Base58.encode(value)
}

object ModifierId extends GjalSerializer[ModifierId] {

  @newtype
  case class ModifierTypeId(value: Byte)

  val size: Int = 1 + Digest32.size // ModifierId's are derived from Blake2b-256
  val empty: ModifierId = new ModifierId(Array.fill(size)(0: Byte))

  val genesisParentId: ModifierId = new ModifierId(
    ModifierTypeId(3: Byte).value +:
    Array.fill(Digest32.size)(1: Byte)
  )

  implicit val ord: Ordering[ModifierId] = Ordering.by(_.toString)

  implicit val jsonEncoder: Encoder[ModifierId] = (id: ModifierId) => id.toString.asJson
  implicit val jsonKeyEncoder: KeyEncoder[ModifierId] = (id: ModifierId) => id.toString
  implicit val jsonDecoder: Decoder[ModifierId] = Decoder.decodeString.emapTry(id => Try(ModifierId(id)))
  implicit val jsonKeyDecoder: KeyDecoder[ModifierId] = (id: String) => Some(ModifierId(id))

  def apply(transferTransaction: TransferTransaction[_ <: Proposition]): ModifierId =
    new ModifierId(
      TransferTransaction.modifierTypeId.value +: blake2b256.hash(transferTransaction.messageToSign).value
    )

  def apply(str: String): ModifierId =
    Base58.decode(str) match {
      case Success(id) => new ModifierId(id)
      case Failure(ex) => throw ex
    }

  def serialize(obj: ModifierId, w: Writer): Unit =
    /* value: Array[Byte] */
    w.putBytes(obj.value)

  def parse(r: Reader): ModifierId = {
    val value: Array[Byte] = r.getBytes(size)
    new ModifierId(value)
  }

}
