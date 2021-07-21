package co.topl.attestation.keyManagement.derivedKeys

import co.topl.attestation.keyManagement.mnemonicSeed.Mnemonic.Mnemonic
import co.topl.attestation.{PublicKeyPropositionEd25519, SignatureEd25519}
import co.topl.crypto.PublicKey
import co.topl.crypto.hash.sha512
import co.topl.crypto.signatures.{Ed25519, Signature}
import co.topl.utils.SizedByteVector
import co.topl.utils.SizedByteVector.Types.{ByteVector28, ByteVector32}
import co.topl.utils.SizedByteVector.implicits._
import co.topl.utils.serialization.BifrostSerializer
import scodec.bits.{ByteOrdering, ByteVector}

import java.nio.charset.StandardCharsets
import java.nio.{ByteBuffer, ByteOrder}

/**
 * An ED-25519 extended private key.
 *
 * See https://drive.google.com/file/d/0ByMtMw2hul0EMFJuNnZORDR2NDA/view?resourcekey=0-5yJh8wV--HB7ve2NuqfQ6A
 * for the BIP32-ED25519 specification
 *
 * @param leftKey the left 32 bytes of the private key
 * @param rightKey the right 32 bytes of the private key
 * @param chainCode the 32-byte chain code
 */
case class ExtendedPrivateKeyEd25519(
  leftKey:   ByteVector32,
  rightKey:  ByteVector32,
  chainCode: ByteVector32,
  path: Seq[DerivedKeyIndex]
) {

  // Note: BigInt expects Big-Endian, but SLIP/BIP-ED25519 need Little-Endian
  val leftNumber: BigInt = BigInt(1, leftKey.toArray.reverse)
  val rightNumber: BigInt = BigInt(1, rightKey.toArray.reverse)

  /**
   * Gets the public key paired with this private key.
   * @return a `PublicKey` for verifying signatures made with this private key
   */
  def publicKey: PublicKey = {
    val ed25519 = new Ed25519
    val pk = new Array[Byte](ed25519.PUBLIC_KEY_SIZE)
    ed25519.scalarMultBaseEncoded(leftKey.toArray, pk, 0)

    PublicKey(pk)
  }

  /**
   * Deterministically derives a child `ExtendedPrivateKey` at the given index from this extended private key.
   * @param index the index of the child key to derive
   * @return the derived `ExtendedPrivateKey`
   */
  def deriveChildKey(
    index: DerivedKeyIndex
  ): Either[ExtendedPrivateKeyEd25519.InvalidDerivedKey, ExtendedPrivateKeyEd25519] = {
    val z = index match {
      case s: SoftIndex =>
        hmac512WithKey(
          chainCode.toVector,
          ByteVector(0x00.toByte) ++ ByteVector(publicKey.value) ++ s.bytes.toVector
        )
      case h: HardenedIndex =>
        hmac512WithKey(
          chainCode.toVector,
          ByteVector(0x02.toByte) ++ leftKey.toVector ++ rightKey.toVector ++ h.bytes.toVector
        )
    }

    val zLeft =
      BigInt(
        1,
        SizedByteVector[ByteVector28].fit(z.slice(0, 28), ByteOrdering.LittleEndian).toArray.reverse
      )

    val zRight =
      BigInt(
        1,
        SizedByteVector[ByteVector32].fit(z.slice(32, 64), ByteOrdering.LittleEndian).toArray.reverse
      )

    val nextLeft =
      SizedByteVector[ByteVector32].fit(
        ByteBuffer
          .wrap(
            (zLeft * 8 + leftNumber).toByteArray.reverse
          )
          .order(ByteOrder.LITTLE_ENDIAN)
      )

    val nextRight =
      SizedByteVector[ByteVector32].fit(
        ByteBuffer
          .wrap(((zRight + rightNumber) % (2 ^ 256)).toByteArray.reverse)
          .order(ByteOrder.LITTLE_ENDIAN)
      )

    val nextChainCode =
      SizedByteVector[ByteVector32].fit(
        index match {
          case b: SoftIndex =>
            hmac512WithKey(
              chainCode.toVector,
              ByteVector(0x03.toByte) ++ ByteVector(publicKey.value) ++ b.bytes.toVector
            )
          case h: HardenedIndex =>
            hmac512WithKey(chainCode.toVector, ByteVector(0x01.toByte) ++ rightKey.toVector ++ h.bytes.toVector)
        },
        ByteOrdering.LittleEndian
      )

    ExtendedPrivateKeyEd25519.validate(ExtendedPrivateKeyEd25519(nextLeft, nextRight, nextChainCode, path :+ index))
  }

  /**
   * Signs a message using this private key.
   * @param message the message to sign serialized as a `MessageToSign`
   * @return the signature
   */
  def sign(message: Array[Byte]): SignatureEd25519 = {
    // signing is a mutable process
    val mutableKey: ExtendedPrivateKeyEd25519 = ExtendedPrivateKeyEd25519.copy(this)
    val ec = new Ed25519

    val resultSig = new Array[Byte](ec.SIGNATURE_SIZE)
    val ctx: Array[Byte] = Array.empty
    val phflag: Byte = 0x00

    val h: Array[Byte] = mutableKey.leftKey.toArray ++ mutableKey.rightKey.toArray
    val s: Array[Byte] = mutableKey.leftKey.toArray
    val pk: Array[Byte] = mutableKey.publicKey.value
    val m: Array[Byte] = message

    ec.implSign(ec.shaDigest, h, s, pk, 0, ctx, phflag, m, 0, m.length, resultSig, 0)

    SignatureEd25519(Signature(resultSig))
  }

  lazy val serializer: BifrostSerializer[ExtendedPrivateKeyEd25519] = ExtendedPrivateKeyEd25519Serializer

  lazy val publicImage: PublicKeyPropositionEd25519 = PublicKeyPropositionEd25519(publicKey)
}

object ExtendedPrivateKeyEd25519 {

  case object InvalidDerivedKey
  type InvalidDerivedKey = InvalidDerivedKey.type

  /**
   * ED-25519 Base Order N
   *
   * Equivalent to `2^252 + 27742317777372353535851937790883648493`
   */
  val edBaseN: BigInt = BigInt("7237005577332262213973186563042994240857116359379907606001950938285454250989")

  /**
   * Generates a root `ExtendedPrivateKey` from a mnemonic phrase and an optional password.
   * @param m the mnemonic phrase
   * @param password the password (optional)
   * @return a root `ExtendedPrivateKey`
   */
  def fromMnemonic(m: Mnemonic, password: Option[String]): ExtendedPrivateKeyEd25519 = fromSeed(m(password))

  /**
   * Creates a root `ExtendedPrivateKey` from the given seed.
   * See https://github.com/satoshilabs/slips/blob/master/slip-0023.md
   * for specification.
   * @param seed the seed to generate from
   * @return a root `ExtendedPrivateKey`
   */
  def fromSeed(seed: Array[Byte]): ExtendedPrivateKeyEd25519 = {
    val i =
      hmac512WithKey(ByteVector.view("ed25519 cardano seed".getBytes(StandardCharsets.UTF_8)), ByteVector.view(seed))

    val iLeft = i.slice(0, 32)
    val iRight = i.slice(32, 64)

    val k = sha512.hash(iLeft.toArray).value
    k(0) = (k(0) & 0xf8).toByte
    k(31) = ((k(31) & 0x1f) | 0x40).toByte

    ExtendedPrivateKeyEd25519(
      SizedByteVector[ByteVector32].fit(k.slice(0, 32), ByteOrdering.LittleEndian),
      SizedByteVector[ByteVector32].fit(k.slice(32, 64), ByteOrdering.LittleEndian),
      SizedByteVector[ByteVector32].fit(iRight, ByteOrdering.LittleEndian),
      Seq()
    )
  }

  /**
   * Creates a copy of an `ExtendedPrivateKey`.
   * @param value the private key to copy
   * @return the copied `ExtendedPrivateKey`
   */
  def copy(value: ExtendedPrivateKeyEd25519): ExtendedPrivateKeyEd25519 =
    new ExtendedPrivateKeyEd25519(
      SizedByteVector[ByteVector32].fit(value.leftKey.toArray.clone(), ByteOrdering.LittleEndian),
      SizedByteVector[ByteVector32].fit(value.rightKey.toArray.clone(), ByteOrdering.LittleEndian),
      SizedByteVector[ByteVector32].fit(value.chainCode.toArray.clone(), ByteOrdering.LittleEndian),
      value.path
    )

  /**
   * Validates that the given key is a valid derived key.
   * Keys are invalid if their left private keys are divisible by the ED-25519 Base Order N.
   * @param value the private key value
   * @return either an invalid error or the private key
   */
  def validate(value: ExtendedPrivateKeyEd25519): Either[InvalidDerivedKey, ExtendedPrivateKeyEd25519] =
    Either.cond(value.leftNumber % edBaseN != 0, value, InvalidDerivedKey)
}
