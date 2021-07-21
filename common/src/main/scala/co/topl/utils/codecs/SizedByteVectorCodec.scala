package co.topl.utils.codecs

import cats.implicits._
import co.topl.utils.SizedByteVector
import co.topl.utils.SizedByteVector.InvalidSize
import co.topl.utils.SizedByteVector.Types._
import co.topl.utils.SizedByteVector.implicits._

object SizedByteVectorCodec {

  trait AsBytesInstances {
    implicit val byteVector128AsBytes: AsBytes[Infallible, ByteVector128] = AsBytes.infallible(_.toArray)
    implicit val byteVector64AsBytes: AsBytes[Infallible, ByteVector64] = AsBytes.infallible(_.toArray)
    implicit val byteVector32AsBytes: AsBytes[Infallible, ByteVector32] = AsBytes.infallible(_.toArray)
    implicit val byteVector28AsBytes: AsBytes[Infallible, ByteVector28] = AsBytes.infallible(_.toArray)
    implicit val byteVector4AsBytes: AsBytes[Infallible, ByteVector4] = AsBytes.infallible(_.toArray)
  }

  trait FromBytesInstances {

    implicit val byteVector128FromBytes: FromBytes[InvalidSize, ByteVector128] =
      bytes => SizedByteVector[ByteVector128].validated(bytes).toValidatedNec

    implicit val byteVector64FromBytes: FromBytes[InvalidSize, ByteVector64] =
      bytes => SizedByteVector[ByteVector64].validated(bytes).toValidatedNec

    implicit val byteVector32FromBytes: FromBytes[InvalidSize, ByteVector32] =
      bytes => SizedByteVector[ByteVector32].validated(bytes).toValidatedNec

    implicit val byteVector28FromBytes: FromBytes[InvalidSize, ByteVector28] =
      bytes => SizedByteVector[ByteVector28].validated(bytes).toValidatedNec

    implicit val byteVector4FromBytes: FromBytes[InvalidSize, ByteVector4] =
      bytes => SizedByteVector[ByteVector4].validated(bytes).toValidatedNec
  }

  trait Instances extends AsBytesInstances with FromBytesInstances

  object implicits extends Instances
}
