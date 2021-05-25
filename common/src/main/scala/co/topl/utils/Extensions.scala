package co.topl.utils

import java.nio.charset.{Charset, StandardCharsets}
import scala.collection.BuildFrom
import scala.reflect.ClassTag

object Extensions {

  implicit class ByteOps(val b: Byte) extends AnyVal {

    /**
     * Converts the `Byte` to a `Int` by an unsigned conversion.
     */
    @inline def toUByte: Int = b & 0xff
  }

  implicit class ShortOps(val x: Short) extends AnyVal {

    /**
     * Converts the `Short` value to a `Byte`, checking for lost information.
     * If `Short` value is out of the possible range for a `Byte` result,
     * then a `java.lang.ArithmeticException` is thrown.
     */
    def toByteExact: Byte = {
      if (x < Byte.MinValue || x > Byte.MaxValue)
        throw new ArithmeticException("Byte overflow")
      x.toByte
    }
  }

  implicit class IntOps(val x: Int) extends AnyVal {

    /**
     * Converts the `Int` value to a `Byte`, checking for lost information.
     * If `Int` value is out of the possible range for a `Byte` result,
     * then a `java.lang.ArithmeticException` is thrown.
     */
    def toByteExact: Byte = {
      if (x < Byte.MinValue || x > Byte.MaxValue)
        throw new ArithmeticException("Byte overflow")
      x.toByte
    }

    /**
     * Converts the `Int` value to a `Short`, checking for lost information.
     * If `Int` value is out of the possible range for a `Short` result,
     * then a `java.lang.ArithmeticException` is thrown.
     */
    def toShortExact: Short = {
      if (x < Short.MinValue || x > Short.MaxValue)
        throw new ArithmeticException("Short overflow")
      x.toShort
    }
  }

  implicit class LongOps(val x: Long) extends AnyVal {

    /**
     * Converts the `Long` value to a `Byte`, checking for lost information.
     * If `Long` value is out of the possible range for a `Byte` result,
     * then a `java.lang.ArithmeticException` is thrown.
     */
    def toByteExact: Byte = {
      if (x < Byte.MinValue || x > Byte.MaxValue)
        throw new ArithmeticException("Byte overflow")
      x.toByte
    }

    /**
     * Converts the `Long` value to a `Short`, checking for lost information.
     * If `Long` value is out of the possible range for a `Short` result,
     * then a `java.lang.ArithmeticException` is thrown.
     */
    def toShortExact: Short = {
      if (x < Short.MinValue || x > Short.MaxValue)
        throw new ArithmeticException("Short overflow")
      x.toShort
    }

    /**
     * Converts the `Long` value to a `Int`, checking for lost information.
     * If `Long` value is out of the possible range for a `scala.Int` result,
     * then a `java.lang.ArithmeticException` is thrown.
     */
    def toIntExact: Int = {
      if (x < Int.MinValue || x > Int.MaxValue)
        throw new ArithmeticException("Int overflow")
      x.toInt
    }
  }

  implicit class TraversableOps[A, Source[X] <: Iterable[X]](val xs: Source[A]) extends AnyVal {

    /**
     * Safely casting each element of collection to be type of `B`.
     * If element can not to be cast to `B` then `AssertionError` is thrown
     */
    def cast[B: ClassTag](implicit cbf: BuildFrom[Source[A], B, Source[B]]): Source[B] = {

      for (x <- xs)
        require(
          x match {
            case _: B => true
            case _    => false
          },
          s"Value $x doesn't conform to type ${reflect.classTag[B]}"
        )
      xs.asInstanceOf[Source[B]]
    }
  }

  implicit class StringOps(val s: String) {

    // return the byte array of a string after ensuring valid encoding
    private def getValidBytes(inString: String, charset: Charset): Option[Array[Byte]] = {
      val inBytes = inString.getBytes(charset)
      if (new String(inBytes, charset) == inString) Some(inBytes)
      else None
    }

    // returns the byte array of a string after ensuring latin-1 encoding
    def getValidLatin1Bytes: Option[Array[Byte]] = getValidBytes(s, StandardCharsets.ISO_8859_1)

    // returns the byte array of a string after ensuring utf-8 encoding
    def getValidUTF8Bytes: Option[Array[Byte]] = getValidBytes(s, StandardCharsets.UTF_8)

  }
}
