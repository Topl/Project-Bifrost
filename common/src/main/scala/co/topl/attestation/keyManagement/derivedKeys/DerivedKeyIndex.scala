package co.topl.attestation.keyManagement.derivedKeys

import co.topl.utils.SizedByteVector
import co.topl.utils.SizedByteVector.Types.ByteVector4
import co.topl.utils.SizedByteVector.implicits._

import java.nio.{ByteBuffer, ByteOrder}

trait DerivedKeyIndex {

  /**
   * The size of a `Long` byte representation.
   */
  private val longByteSize = 8

  /**
   * The Index value as a `Long`.
   */
  val value: Long

  /**
   * The index representation as a 4-byte vector.
   */
  val bytes: ByteVector4 =
    // cut off top 4 significant bytes since representation is an unsigned integer
    SizedByteVector[ByteVector4]
      .fit(ByteBuffer.allocate(longByteSize).order(ByteOrder.LITTLE_ENDIAN).putLong(value))
}

case class SoftIndex private (override val value: Long) extends DerivedKeyIndex
case class HardenedIndex private (override val value: Long) extends DerivedKeyIndex

object DerivedKeyIndex {

  /**
   * The lower bound of the hardened index range.
   * Lower bound is `2^31` or 2147483648.
   * Upper bound is `2^32` or 4294967296.
   */
  private val hardenedIndex: Long = 2147483648L

  /**
   * Instantiates a new soft-index value.
   * Any value below 0 will return a 0-value `SoftIndex`.
   * @param value the index value
   * @return a soft index
   */
  def soft(value: Int): SoftIndex =
    if (value >= 0) SoftIndex(value.toLong)
    else SoftIndex(0)

  /**
   * Instantiates a new hardened-index value.
   * Any value below 0 will return a 0-value `HardenedIndex`.
   * @param value the index value
   * @return a hardened index
   */
  def hardened(value: Int): HardenedIndex =
    if (value >= 0) HardenedIndex(value.toLong + hardenedIndex)
    else HardenedIndex(0.toLong + hardenedIndex)

}
