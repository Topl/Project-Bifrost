package attestation

import co.topl.crypto.hash.blake2b256
import co.topl.utils.codecs.AsBytes.implicits._
import co.topl.utils.encode.Base58
import cats.implicits._

import scala.util.{Failure, Try}

/**
 * The Address encoder dictates how addresses are cast To and From strings. Since this is the primary
 * method users will interact with the protocol, the Address encoder adds a 4 byte checksum to the Address
 * as a quick check that may be used with external systems.
 */
object AddressEncoder {

  type NetworkPrefix = Byte

  val checksumLength = 4

  //encoded addresses are 38 bytes (1 for network prefix, 1 for type prefix, 32 for content, 4 for checksum)
  // ENCODED ADDRESS != ADDRESS (Address are contained in an encoded address)
  private val encodedAddressLength: Int = Address.addressSize + checksumLength

  /**
   * Generates a checksum value for checking correctness of string parsed addresses
   *
   * @param addrBytes the bytes of an address (1 - networkPrefix, 1 - addressTypePres, 32 - content bytes)
   * @return a 4 byte checksum value
   */
  private def genChecksum(addrBytes: Array[Byte]): Array[Byte] =
    blake2b256
      .hash(addrBytes)
      .value
      .take(checksumLength)

  def toString(addr: Address): String = {
    val addrBytes = addr.bytes
    val checksum = genChecksum(addrBytes)

    Base58.encode(addrBytes ++ checksum)
  }

  /**
   * Parse an Address from a string (without checking that the network matches)
   * @param addrStr a Base58 encoded address
   * @return the address that was encoded in the string
   */
  def fromStringUnsafe(addrStr: String): Try[Address] = Base58.decode(addrStr).flatMap(fromBytes)

  /**
   * Parse an Address from a string ensuring that the networkPrefix is correct
   * @param addrStr a Base58 encoded address
   * @param networkPrefix a single byte used to identify a network
   * @return the address encoded in the string
   */
  def fromStringWithCheck(addrStr: String, networkPrefix: NetworkPrefix): Try[Address] =
    Base58.decode(addrStr).flatMap { b =>
      if (b.head == networkPrefix) fromBytes(b)
      else Failure(new Exception(s"""Invalid address: "$addrStr". Network type does not match"""))
    }

  /**
   * Parses an address from an array of bytes
   * @param bytes bytes with the encoded address
   * @return the address corresponding to given array of bytes
   */
  private def fromBytes(bytes: Array[Byte]): Try[Address] = {
    require(bytes.length == encodedAddressLength, s"Invalid address: Not the required length")

    val addrBytes = bytes.dropRight(checksumLength)
    val checksum = bytes.takeRight(checksumLength)
    require(
      genChecksum(addrBytes) sameElements checksum,
      s"Invalid address: Checksum fails for ${Base58.encode(bytes)}"
    )

    Address.parseBytes(addrBytes)
  }
}
