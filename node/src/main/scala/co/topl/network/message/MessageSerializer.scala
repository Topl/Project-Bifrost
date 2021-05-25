package co.topl.network.message

import akka.util.ByteString
import co.topl.crypto.hash.blake2b256
import co.topl.network.MaliciousBehaviorException
import co.topl.network.peer.ConnectedPeer

import java.nio.ByteOrder
import scala.util.Try

/** Serializer for Message case class */
class MessageSerializer(specs: Seq[MessageSpec[_]], magicBytes: Array[Byte]) {

  import scala.language.existentials

  implicit private val byteOrder: ByteOrder = ByteOrder.BIG_ENDIAN

  private val specsMap = Map(specs.map(s => s.messageCode -> s): _*)
    .ensuring(m => m.size == specs.size, "Duplicate message codes")

  def serialize(obj: Message[_]): ByteString = {
    val builder = ByteString.createBuilder
      .putBytes(magicBytes)
      .putByte(obj.spec.messageCode)
      .putInt(obj.dataLength)

    if (obj.dataLength > 0) {
      val checksum = blake2b256.hash(obj.dataBytes).value.take(Message.ChecksumLength)
      builder.putBytes(checksum).putBytes(obj.dataBytes)
    }

    builder.result()
  }

  /** MAGIC ++ Array(spec.messageCode) ++ Ints.toByteArray(dataLength) ++ dataWithChecksum */
  def deserialize(byteString: ByteString, sourceOpt: Option[ConnectedPeer]): Try[Option[Message[_]]] = Try {
    if (byteString.length < Message.HeaderLength) {
      None
    } else {
      val it = byteString.iterator
      val magic = it.getBytes(Message.MagicLength)
      val msgCode = it.getByte
      val length = it.getInt

      /** peer is from another network */
      if (!java.util.Arrays.equals(magic, magicBytes)) {
        throw MaliciousBehaviorException(s"Wrong magic bytes, expected ${magicBytes.mkString}, got ${magic.mkString}")
      }

      /** peer is trying to cause buffer overflow or breaking the parsing */
      if (length < 0) {
        throw MaliciousBehaviorException("Data length is negative!")
      }

      val spec = specsMap.getOrElse(msgCode, throw new Error(s"No message handler found for $msgCode"))

      if (length != 0 && byteString.length < length + Message.HeaderLength + Message.ChecksumLength) {
        None
      } else {
        val msgData = if (length > 0) {
          val checksum = it.getBytes(Message.ChecksumLength)
          val data = it.getBytes(length)
          val digest = blake2b256.hash(data).value.take(Message.ChecksumLength)

          /** peer reported incorrect checksum */
          if (!java.util.Arrays.equals(checksum, digest)) {
            throw MaliciousBehaviorException(s"Wrong checksum, expected ${checksum.mkString}, got ${checksum.mkString}")
          }
          data
        } else {
          Array.emptyByteArray
        }

        Some(Message(spec, Left(msgData), sourceOpt))
      }
    }
  }
}
