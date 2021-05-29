package co.topl.modifier

import cats.implicits._
import co.topl.crypto.hash.digest.implicits._
import co.topl.crypto.hash.blake2b256
import co.topl.crypto.hash.digest.Digest32
import co.topl.crypto.hash.blake2b256
import co.topl.modifier.NodeViewModifier.ModifierTypeId
import co.topl.modifier.block.Block
import co.topl.modifier.transaction.Transaction
import co.topl.utils.IdiomaticScalaTransition.implicits.toEitherOps
import co.topl.utils.codecs.AsBytes.implicits._
import co.topl.utils.encode.Base58
import co.topl.utils.serialization.{BifrostSerializer, BytesSerializable, Reader, Writer}
import com.google.common.primitives.Ints
import io.circe.syntax.EncoderOps
import io.circe.{Decoder, Encoder, KeyDecoder, KeyEncoder}

class ModifierId private (private val value: Array[Byte]) extends BytesSerializable {

  require(value.length == ModifierId.size, s"Invalid size for ModifierId")

  type M = ModifierId
  lazy val serializer: BifrostSerializer[ModifierId] = ModifierId

  def getIdBytes: Array[Byte] = value.tail
  def getModType: ModifierTypeId = ModifierTypeId(value.head)

  override def hashCode: Int = Ints.fromByteArray(value)

  override def equals(obj: Any): Boolean = obj match {
    case mId: ModifierId => mId.value sameElements value
    case _               => false
  }

  override def toString: String = Base58.encode(value)
}

object ModifierId extends BifrostSerializer[ModifierId] {

  val size: Int = 1 + Digest32.size // ModifierId's are derived from Blake2b-256
  val empty: ModifierId = new ModifierId(Array.fill(size)(0: Byte))

  val genesisParentId: ModifierId =
    new ModifierId(Block.modifierTypeId.value +: Array.fill(Digest32.size)(1: Byte))

  implicit val ord: Ordering[ModifierId] = Ordering.by(_.toString)

  sealed trait CreateModifierIdFailure
  case object InvalidModifierFailure extends CreateModifierIdFailure
  case class Base58DecodeFailure(failure: Throwable) extends CreateModifierIdFailure

  implicit val jsonEncoder: Encoder[ModifierId] = (id: ModifierId) => id.toString.asJson
  implicit val jsonKeyEncoder: KeyEncoder[ModifierId] = (id: ModifierId) => id.toString

  implicit val jsonDecoder: Decoder[ModifierId] =
    Decoder.decodeString.emap(id => ModifierId.create(id).leftMap(err => s"Failed to create modifier ID: $err"))
  implicit val jsonKeyDecoder: KeyDecoder[ModifierId] = (id: String) => ModifierId.create(id).toOption

  /**
   * Creates a modifier ID from a node view modifier.
   * @param nodeViewModifier the modifier to create an ID from
   * @return a create result with a CreateModifierIdFailure if a failure occurred
   */
  def create(nodeViewModifier: NodeViewModifier): Either[CreateModifierIdFailure, ModifierId] = nodeViewModifier match {
    case mod: Block =>
      new ModifierId(
        Block.modifierTypeId.value +: blake2b256.hash(mod.messageToSign).bytes
      ).asRight[CreateModifierIdFailure]
    case mod: Transaction.TX =>
      new ModifierId(Transaction.modifierTypeId.value +: blake2b256.hash(mod.messageToSign).bytes)
        .asRight[CreateModifierIdFailure]
    case _ => Left(InvalidModifierFailure)
  }

  /**
   * Creates a modifier ID from a string.
   * @param str the string to turn into a modifier ID
   * @return a create result with a CreateModifierIdFailure is a failure occurred
   */
  def create(str: String): Either[CreateModifierIdFailure, ModifierId] =
    Base58.decode(str).toEither.map(new ModifierId(_)).leftMap(Base58DecodeFailure)

  @deprecated
  def apply(nodeViewModifier: NodeViewModifier): ModifierId = nodeViewModifier match {
    case mod: Block =>
      new ModifierId(Block.modifierTypeId.value +: blake2b256.hash(mod.messageToSign).bytes)
    case mod: Transaction.TX =>
      new ModifierId(Transaction.modifierTypeId.value +: blake2b256.hash(mod.messageToSign).bytes)
    case _ => throw new Error("Only blocks and transactions generate a modifierId")
  }

  @deprecated
  def apply(str: String): ModifierId =
    Base58.decode(str).map(new ModifierId(_)).getOrThrow()

  def serialize(obj: ModifierId, w: Writer): Unit =
    /* value: Array[Byte] */
    w.putBytes(obj.value)

  def parse(r: Reader): ModifierId = {
    val value: Array[Byte] = r.getBytes(size)
    new ModifierId(value)
  }

}
