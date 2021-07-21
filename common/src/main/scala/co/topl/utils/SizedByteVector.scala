package co.topl.utils

import io.estatico.newtype.macros.newtype
import io.estatico.newtype.ops._
import scodec.bits.{ByteOrdering, ByteVector}
import simulacrum.typeclass

import java.nio.{ByteBuffer, ByteOrder}

import scala.language.implicitConversions

@typeclass
trait SizedByteVector[T] {
  def size: Int

  def validated(vector: ByteVector): Either[SizedByteVector.InvalidSize, T]

  def validated(array: Array[Byte]): Either[SizedByteVector.InvalidSize, T] = validated(ByteVector.view(array))

  def fit(from: ByteVector, ordering: ByteOrdering): T

  def fit(from: Array[Byte], ordering: ByteOrdering): T = fit(ByteVector.view(from), ordering)

  def fit(from: ByteBuffer): T =
    if (from.order() == ByteOrder.LITTLE_ENDIAN) fit(from.array(), ByteOrdering.LittleEndian)
    else fit(from.array(), ByteOrdering.BigEndian)

  def toVector(t: T): ByteVector

  def toArray(t: T): Array[Byte] = toVector(t).toArray
}

object SizedByteVector {
  case class InvalidSize()

  def instance[T](
    vectorSize:      Int,
    constructorFunc: ByteVector => T,
    toVectorFunc:    T => ByteVector
  ): SizedByteVector[T] =
    new SizedByteVector[T] {
      override val size: Int = vectorSize

      override def validated(vector: ByteVector): Either[InvalidSize, T] =
        if (vector.size == size) Right(constructorFunc(vector)) else Left(InvalidSize())

      override def fit(from: ByteVector, ordering: ByteOrdering): T = (from, ordering) match {
        case (x, ByteOrdering.LittleEndian) if x.size < size => constructorFunc(from.padRight(size))
        case (x, ByteOrdering.BigEndian) if x.size < size    => constructorFunc(from.padLeft(size))
        case (x, ByteOrdering.LittleEndian) if x.size > size => constructorFunc(from.take(size))
        case (x, ByteOrdering.BigEndian) if x.size > size    => constructorFunc(from.takeRight(size))
        case (x, _)                                          => constructorFunc(x)
      }

      override def toVector(t: T): ByteVector = toVectorFunc(t)
    }

  object Types {

    @newtype
    class ByteVector128(val value: ByteVector)

    object ByteVector128 {
      val size: Int = 128

      val sizedByteVector: SizedByteVector[ByteVector128] = SizedByteVector.instance(size, _.coerce, _.value)
    }

    @newtype
    class ByteVector64(val value: ByteVector)

    object ByteVector64 {
      val size: Int = 64

      val sizedByteVector: SizedByteVector[ByteVector64] = SizedByteVector.instance(size, _.coerce, _.value)
    }

    @newtype
    class ByteVector32(val value: ByteVector)

    object ByteVector32 {
      val size: Int = 32

      val sizedByteVector: SizedByteVector[ByteVector32] = SizedByteVector.instance(size, _.coerce, _.value)
    }

    @newtype
    class ByteVector28(val value: ByteVector)

    object ByteVector28 {
      val size: Int = 28

      val sizedByteVector: SizedByteVector[ByteVector28] = SizedByteVector.instance(size, _.coerce, _.value)
    }

    @newtype
    class ByteVector4(val value: ByteVector)

    object ByteVector4 {
      val size: Int = 4

      val sizedByteVector: SizedByteVector[ByteVector4] = SizedByteVector.instance(size, _.coerce, _.value)
    }
  }

  trait Instances {
    import Types._

    implicit val byteVector128: SizedByteVector[ByteVector128] = ByteVector128.sizedByteVector
    implicit val byteVector64: SizedByteVector[ByteVector64] = ByteVector64.sizedByteVector
    implicit val byteVector32: SizedByteVector[ByteVector32] = ByteVector32.sizedByteVector
    implicit val byteVector28: SizedByteVector[ByteVector28] = ByteVector28.sizedByteVector
    implicit val byteVector4: SizedByteVector[ByteVector4] = ByteVector4.sizedByteVector
  }

  object implicits extends Instances with SizedByteVector.ToSizedByteVectorOps
}
