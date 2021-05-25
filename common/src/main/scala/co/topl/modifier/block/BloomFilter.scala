package co.topl.modifier.block

import co.topl.crypto.hash.Blake2b256
import co.topl.modifier.block.BloomFilter.BloomTopic
import co.topl.utils.IdiomaticScalaTransition.implicits.toEitherOps
import co.topl.utils.codecs.AsBytes.implicits._
import co.topl.utils.codecs.{AsBytes, FromBytes, Infallible}
import co.topl.utils.encode.Base58
import co.topl.utils.serialization.{BifrostSerializer, BytesSerializable, Reader, Writer}
import com.google.common.primitives.Longs
import io.circe.syntax.EncoderOps
import io.circe.{Decoder, Encoder, KeyDecoder, KeyEncoder}
import io.estatico.newtype.macros.newtype

import scala.language.implicitConversions
import scala.util.Try

/**
 * This implementation of Bloom filter is inspired from the Ethereum Yellow Paper
 * for more information, visit: http://gavwood.com/paper.pdf (as of 2020.11.20 there is an error in footnote 3 - 2^11^ = 2048)
 * Another explanation can be found here (but he messes denoting the byte pairs, this stuff is tricky)
 * https://ethereum.stackexchange.com/questions/59203/what-exactly-does-the-m-function-in-the-formal-bloom-filter-specifications-do
 * A calculator to look at the false positivity rate can be found here
 * https://hur.st/bloomfilter/?n=100&p=&m=2048&k=4
 * The bloom filter is constructed by taking the low-order 11 bits (mod 2048) of each of the first four pairs of bytes from a Blake2b-256 hash
 * In english, the algorithm is to hash the input topic, take the first 8 bytes from the hash output, pair them up (1,2) (3,4) (5,6) (7,8),
 * then take the first 11 bits from each pair (each pair is 16 bits and we take 11 bits because 2^11^ = 2048). These bits can be
 * retrieved using a bit-wise AND operation. For each of the 11-bit numbers we construct a positive definite integer between (0 & 2047).
 * This integer is the index of the bit to flip in the bloom filter. Finally, the bloom filter is represented as a Array[Long] so
 * we must apply two additional bit-wise AND operations on each index to find which Long should be changed in the bloom filter and
 * finally which bit of the Long must be flipped.
 */
class BloomFilter private (private val value: Array[Long]) extends BytesSerializable {

  require(
    value.length == BloomFilter.numLongs,
    s"Invalid bloom filter length: ${value.length}. Bloom filters must be an Array[Long] of length ${BloomFilter.numLongs}"
  )

  override type M = BloomFilter
  lazy val serializer: BifrostSerializer[BloomFilter] = BloomFilter

  /** Check if a given topic is included in the Bloom filter */
  def contains(topic: BloomTopic): Boolean = {
    val indices: Set[Int] = BloomFilter.calculateIndices(topic)
    BloomFilter.generateLongs(indices).forall { case (idx, bits) =>
      (value(idx) & bits) != 0L
    }
  }

  /** JAA - DO NOT USE THE `.bytes` or `toBytes` methods from the BifrostSerailizer, this must be fixed length */
  override def toString: String = Base58.encode(value.flatMap(Longs.toByteArray))

  override def equals(obj: Any): Boolean = obj match {
    case b: BloomFilter => b.value sameElements value
    case _              => false
  }

  override def hashCode(): Int = super.hashCode()
}

object BloomFilter extends BifrostSerializer[BloomFilter] {

  @newtype
  case class BloomTopic(value: Array[Byte])

  val numBytes: Int = 256 //bytes (2048 bits)
  private val size: Int = numBytes * 8
  private val numLongs: Int = size / 64 // filter is composed of an array of longs (64 bit elements)

  val empty: BloomFilter = new BloomFilter(Array.fill(numLongs)(0L)) // 2048 element bit array of zeros

  // Define the bit masks needed to properly parse the hash outputs
  // These vals are denoted as Ints (4 bytes) because we need at least 11 bits to hold a 2048 bit bloom filter
  // In the notes below only the first two bytes are shown since the other bytes are always zero.
  // NOTE - these values are highly dependent on the length of the bloom filter, manipulate carefully
  private val idxMask: Int =
    size - 1 /* 2047 -> 0000 0111 1111 1111 - mask for taking the low-order 11 bits of the byte pairs */
  private val longElemMask: Int =
    size - 64 /* 1984 -> 0000 0111 1100 0000 - mask for finding the long to modify */
  private val bitElemMask: Int = 63 /*   63 -> 0000 0000 0011 1111 - mask for finding the bit to modify */

  /** Create a new Bloom filter given a sequence of topics to be hashed */
  def apply(topics: Set[BloomTopic]): BloomFilter = update(empty, topics)

  /**
   * Returns a new Bloom filter with updated topics included in the bit array.
   * The updated bloom filter computes the indices to be flipped and merges these changes with
   * the input array of longs before returning a new filter
   */
  def update(inputBF: BloomFilter, topics: Set[BloomTopic]): BloomFilter = {

    /** Compute the indices that should be flipped in the bloom filter */
    val indices: Set[Int] = topics.flatMap(calculateIndices)

    /** Generate a long corresponding to the indices calculated */
    val longsForFilter: Map[Int, Long] = generateLongs(indices)

    /**
     * Compute the fixed length Array[Long] by bit-wise OR operations
     * with the matching index from the input BloomFilter
     */
    val longsArray =
      inputBF.value.zipWithIndex
        .map { case (bits, idx) =>
          longsForFilter.getOrElse(idx, 0L) | bits
        }

    new BloomFilter(longsArray)
  }

  /**
   * Calculates the bit index that must be flipped.
   * The algorithm is
   * 1. Compute the hash of the given topic
   * 2. Retrieve the first 4 pairs of bytes from the hash
   * 3. Take the bit-wise AND to convert signed byte to unsigned int
   * 4. For each pair of bytes, take the first byte bit shift it up 8 elements and bit-wise OR it to produce a 16 bit
   *    unsigned number.
   * 5. From the 16 bit unsigned number, apply an 11 bit mask to retrieve the lowest 11-bits. This is the index of the
   *    bit that should be flipped in the bit array.
   *
   * Since Java uses signed numbers we must be careful about the mapping of the bitwise operations. See the examples
   * below for the expected behavior.
   * Examples
   * 1. Byte pair (0: Byte, 0: Byte)    -> 0: Int (the zero index bit in the 2048 bit array must be flipped)
   * 2. Byte pair (0: Byte, 1: Byte)    -> 1: Int (the one index bit in the 2048 bit array must be flipped)
   * 3. Byte pair (0: Byte, 127: Byte)  -> 127: Int (the 127 index bit in the 2048 bit array must be flipped)
   * 4. Byte pair (0: Byte, -128: Byte) -> 128: Int (the 128 index bit in the 2048 bit array must be flipped)
   * 5. Byte pair (0: Byte, -127: Byte) -> 129: Int
   * 6. Byte pair (0: Byte, -1: Byte)   -> 255: Int
   * 7. Byte pair (1: Byte, 0: Byte)    -> 256: Int
   * 8. Byte pair (127: Byte, 0: Byte)  -> 1792: Int
   * 9. Byte pair (127: Byte, -1: Byte) -> 2047: Int
   * 10. Byte pair (-1: Byte, -1: Byte) -> 2047: Int
   */
  private def calculateIndices(topic: BloomTopic): Set[Int] =
    // Pair up bytes and convert signed Byte to unsigned Int
    Set(0, 2, 4, 6)
      .map(i => Blake2b256.hash(topic.value).getOrThrow().value.slice(i, i + 2).map(_ & 0xff))
      .map { case Array(b1, b2) =>
        ((b1 << 8) | b2) & idxMask
      }

  /**
   * From the set of indices, create a long (with the appropriate bits flipped) that will be inserted in the
   * * bloom filter. This function performs bit-wise operations to split the 11 bit bit index integer into two pieces.
   * 1. The least significant 6 bits (6 bits from the right) are used to determine how far to shift a single 1 bit
   *    along the length of the long. This is the first element in the tuple below.
   * 2. The most significant 5 bits and bit shifted down 6 bits to form an integer between 0 and (numLongs - 1).
   *    This is the index of the Long in the array of longs where the long computed in step 1 will be placed.
   * Finally, since we may update bits corresponding to the same long, we group them and fold all longs at the
   * same index into a single value using a bit-wise OR operation. Following this the output mao should contain
   * only unique keys between 0 -> 31. These keys are to be associated with a single Long, the value of which
   * may range between Long.MinValue -> Long.MaxValue
   */
  private def generateLongs(indices: Set[Int]): Map[Int, Long] =
    indices
      .map { i =>
        (
          Long.MinValue >>> (i & bitElemMask), // value of the bit array as a Long
          (i & longElemMask) >>> 6 // index of the Long that must be manipulated
        )
      }
      .groupBy(_._2) // group the tuples by the index of the long so we can accumulate the bits into a single Long
      .map { case (elem, values) =>
        elem -> values.foldLeft(0L)((acc, bits) => acc | bits._1)
      }

  /** Recreate a bloom filter from a string encoding */
  /** JAA - DO NOT USE THE `parseBytes` method from BifrostSerializer, this must be fixed length */
  private def fromString(str: String): Try[BloomFilter] =
    Base58
      .decode(str)
      .map(_.grouped(Longs.BYTES).map(Longs.fromByteArray).toArray) //Array[Byte] -> Array[Long]
      .map(new BloomFilter(_))

  implicit val jsonEncoder: Encoder[BloomFilter] = (bf: BloomFilter) => bf.toString.asJson
  implicit val jsonKeyEncoder: KeyEncoder[BloomFilter] = (bf: BloomFilter) => bf.toString
  implicit val jsonDecoder: Decoder[BloomFilter] = Decoder.decodeString.emapTry(fromString)
  implicit val jsonKeyDecoder: KeyDecoder[BloomFilter] = (str: String) => fromString(str).toOption

  override def serialize(obj: BloomFilter, w: Writer): Unit =
    obj.value.foreach(l => w.putLong(l))

  override def parse(r: Reader): BloomFilter = {
    val value: Array[Long] = (for (_ <- 0 until numLongs) yield r.getLong()).toArray
    new BloomFilter(value)
  }

  trait Instances {
    implicit val bloomTopicDecoder: AsBytes[Infallible, BloomTopic] = AsBytes.infallible(_.value)
    implicit val bloomTopicEncoder: FromBytes[Infallible, BloomTopic] = FromBytes.infallible(BloomTopic(_))
  }

  object implicits extends Instances
}
