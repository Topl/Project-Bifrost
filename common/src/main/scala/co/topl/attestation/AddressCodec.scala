package co.topl.attestation

import cats.Semigroup
import cats.data.{Validated, ValidatedNec}
import cats.implicits._
import co.topl.crypto.hash.blake2b256
import co.topl.utils.NetworkType.NetworkPrefix
import co.topl.utils.encode.Base58
import co.topl.utils.NetworkType
import co.topl.utils.codecs.{AsBytes, FromBytes, Infallible}

import scala.language.implicitConversions

/**
 * The Address encoder dictates how addresses are cast To and From strings. Since this is the primary
 * method users will interact with the protocol, the Address encoder adds a 4 byte checksum to the Address
 * as a quick check that may be used with external systems.
 */
object AddressCodec {
  val ChecksumLength = 4

  // encoded addresses are 38 bytes (1 for network prefix, 1 for type prefix, 32 for content, 4 for checksum)
  // ENCODED ADDRESS != ADDRESS (Address are contained in an encoded address)
  private val EncodedAddressLength: Int = Address.addressSize + ChecksumLength

  trait Implicits {

    implicit def addressFromBytes(implicit networkPrefix: NetworkPrefix): AddressFromBytes = new AddressFromBytes

    implicit val addressToBytes: AsBytes[Infallible, Address] =
      AsBytes.infallible[Address](address => address.bytes ++ address.bytes.checksum)

    implicit class AddressOps(address: Address) {

      import co.topl.utils.codecs.AsBytes.implicits._

      def base58Encoded: String =
        Base58.encode(address.infalliblyEncodeAsBytes)(identityBytesEncoder)
    }

    implicit class StringOps(value: String) {

      import co.topl.utils.codecs.FromBytes.implicits._

      def decodeAddress(implicit networkPrefix: NetworkPrefix): ValidatedNec[AddressValidationError, Address] =
        Validated
          .fromTry(Base58.decode(value))
          .leftMap(_ => NotBase58)
          .toValidatedNec
          .andThen((bytes: Array[Byte]) => bytes.decodeTo[AddressValidationError, Address])
    }

    implicit class ByteArrayOps(bytes: Array[Byte]) {

      /**
       * Generates a checksum value for checking correctness of string parsed addresses
       *
       * @return a 4 byte checksum value
       */
      def checksum: Array[Byte] =
        blake2b256
          .hash(bytes)
          .value
          .take(ChecksumLength)
    }

  }

  object implicits extends Implicits

  class AddressFromBytes(implicit networkPrefix: NetworkPrefix) extends FromBytes[AddressValidationError, Address] {
    import implicits._

    override def decode(bytes: Array[Byte]): ValidatedNec[AddressValidationError, Address] =
      networkPrefixValidation(bytes)
        .map(_ => bytes)
        .combine(lengthValidation(bytes))
        .combine(checksumValidation(bytes))
        .andThen(_ => parseValidation(bytes))

    implicit private def prefixSemigroup: Semigroup[NetworkPrefix] = (_, b) => b
    implicit private def byteArraySemigroup: Semigroup[Array[Byte]] = (_, b) => b

    private[attestation] def networkPrefixValidation(bytes: Array[Byte])(implicit
      networkPrefix:                                        NetworkPrefix
    ): ValidatedNec[AddressValidationError, NetworkPrefix] =
      bytes.headOption
        .toValidNec(InvalidAddress: AddressValidationError)
        .andThen(prefix =>
          NetworkType
            .pickNetworkType(prefix)
            .toValidNec(InvalidNetworkPrefix)
            .map(_.netPrefix)
            .combine(Validated.condNec(prefix == networkPrefix, prefix, NetworkTypeMismatch))
        )

    private[attestation] def lengthValidation(bytes: Array[Byte]): ValidatedNec[AddressValidationError, Array[Byte]] =
      Validated.condNec(bytes.length == EncodedAddressLength, bytes, InvalidAddressLength)

    private[attestation] def checksumValidation(bytes: Array[Byte]): ValidatedNec[AddressValidationError, Array[Byte]] =
      Validated.condNec(
        bytes.dropRight(ChecksumLength).checksum sameElements bytes.takeRight(ChecksumLength),
        bytes,
        InvalidChecksum
      )

    private[attestation] def parseValidation(bytes: Array[Byte]): ValidatedNec[AddressValidationError, Address] =
      Validated.fromTry(AddressSerializer.parseBytes(bytes)).leftMap(AddressDecodeFailure).toValidatedNec

  }
}

sealed abstract class AddressValidationError
case object InvalidNetworkPrefix extends AddressValidationError
case object InvalidAddress extends AddressValidationError
case object NotBase58 extends AddressValidationError
case object NetworkTypeMismatch extends AddressValidationError
case object InvalidAddressLength extends AddressValidationError
case object InvalidChecksum extends AddressValidationError
case class AddressDecodeFailure(throwable: Throwable) extends AddressValidationError
