package co.topl.attestation.keyManagement

import cats.implicits._
import co.topl.attestation.keyManagement.proof.SignatureCurve25519.InvalidSignatureFailure
import co.topl.attestation.keyManagement.proof.{SignatureCurve25519, ThresholdSignatureCurve25519}
import co.topl.attestation.keyManagement.proposition.{PublicKeyPropositionCurve25519, ThresholdPropositionCurve25519}
import co.topl.crypto.signatures.Signature
import co.topl.utils.StringDataTypes.Base58Data
import co.topl.utils.StringDataTypes.implicits._
import co.topl.utils.codecs.{AsBytes, FromBytes, Infallible}
import co.topl.utils.serialization.BifrostSerializer
import io.circe.{Decoder, Encoder, KeyDecoder, KeyEncoder}
import co.topl.utils.codecs.implicits._
import io.circe.syntax._

package object codec {

  trait BifrostSerializerInstances {

    implicit val signatureCurve25519Serializer: BifrostSerializer[SignatureCurve25519] = SignatureCurve25519Serializer

    implicit val thresholdSignatureCurve25519Serializer: BifrostSerializer[ThresholdSignatureCurve25519] =
      ThresholdSignatureCurve25519Serializer

    implicit val publicKeyPropositionCurve25519Serializer: BifrostSerializer[PublicKeyPropositionCurve25519] =
      PublicKeyPropositionCurve25519Serializer

    implicit val thresholdPropositionCurve25519Serializer: BifrostSerializer[ThresholdPropositionCurve25519] =
      ThresholdPropositionCurve25519Serializer

  }

  private object BifrostSerializers extends BifrostSerializerInstances

  import BifrostSerializers._

  trait FromBytesInstances {

    implicit val signatureCurve25519FromBytes: FromBytes[InvalidSignatureFailure, SignatureCurve25519] =
      bytes => SignatureCurve25519.validated(Signature(bytes)).toValidatedNec

  }

  private object FromBytes extends FromBytesInstances

  import FromBytes._

  trait JsonEncoderInstances {

    private def deriveEncoderFromBifrostSerializer[T](implicit serializer: AsBytes[Infallible, T]): Encoder[T] =
      (t: T) => t.encodeAsBase58.asJson

    private def deriveKeyEncoderFromBase58[T](implicit asBytes: AsBytes[Infallible, T]): KeyEncoder[T] =
      _.encodeAsBase58.show

    implicit val publicKeyPropositionCurve25519Encoder: Encoder[PublicKeyPropositionCurve25519] =
      deriveEncoderFromBifrostSerializer[PublicKeyPropositionCurve25519]

    implicit val publicKeyPropositionCurve25519KeyEncoder: KeyEncoder[PublicKeyPropositionCurve25519] =
      deriveKeyEncoderFromBase58[PublicKeyPropositionCurve25519]

    implicit val thresholdSignatureCurve25519Encoder: Encoder[ThresholdSignatureCurve25519] =
      deriveEncoderFromBifrostSerializer[ThresholdSignatureCurve25519]

    implicit val thresholdSignatureCurve25519KeyEncoder: KeyEncoder[ThresholdSignatureCurve25519] =
      deriveKeyEncoderFromBase58[ThresholdSignatureCurve25519]

    implicit val signatureCurve25519Encoder: Encoder[SignatureCurve25519] =
      deriveEncoderFromBifrostSerializer[SignatureCurve25519]

    implicit val signatureCurve25519KeyEncoder: KeyEncoder[SignatureCurve25519] =
      deriveKeyEncoderFromBase58[SignatureCurve25519]

    implicit val thresholdPropositionCurve25519Encoder: Encoder[ThresholdPropositionCurve25519] =
      deriveEncoderFromBifrostSerializer[ThresholdPropositionCurve25519]

    implicit val thresholdPropositionCurve25519KeyEncoder: KeyEncoder[ThresholdPropositionCurve25519] =
      deriveKeyEncoderFromBase58[ThresholdPropositionCurve25519]

  }

  trait JsonDecoderInstances {

    private def deriveKeyDecoderFromBifrostSerializer[T](implicit serializer: BifrostSerializer[T]): KeyDecoder[T] =
      Base58Data.validated(_).toOption.flatMap(data => serializer.parseBytes(data.value).toOption)

    private def deriveDecoderFromBase58[T, F](implicit fromBytes: FromBytes[F, T]): Decoder[T] =
      Decoder[Base58Data].emap(data => data.decodeTo[F, T].toEither.leftMap(err => err.toString))

    implicit val thresholdSignatureCurve25519Decoder: Decoder[ThresholdSignatureCurve25519] =
      deriveDecoderFromBase58[ThresholdSignatureCurve25519, Throwable]

    implicit val thresholdSignatureCurve25519KeyDecoder: KeyDecoder[ThresholdSignatureCurve25519] =
      deriveKeyDecoderFromBifrostSerializer[ThresholdSignatureCurve25519]

    implicit val signatureCurve25519Decoder: Decoder[SignatureCurve25519] =
      deriveDecoderFromBase58[SignatureCurve25519, InvalidSignatureFailure]

    implicit val signatureCurve25519KeyDecoder: KeyDecoder[SignatureCurve25519] =
      deriveKeyDecoderFromBifrostSerializer[SignatureCurve25519]

    implicit val publicKeyPropositionCurve25519Decoder: Decoder[PublicKeyPropositionCurve25519] =
      deriveDecoderFromBase58[PublicKeyPropositionCurve25519, Throwable]

    implicit val publicKeyPropositionCurve25519KeyDecoder: KeyDecoder[PublicKeyPropositionCurve25519] =
      deriveKeyDecoderFromBifrostSerializer[PublicKeyPropositionCurve25519]

    implicit val thresholdPropositionCurve25519Decoder: Decoder[ThresholdPropositionCurve25519] =
      deriveDecoderFromBase58[ThresholdPropositionCurve25519, Throwable]

    implicit val thresholdPropositionCurve25519KeyDecoder: KeyDecoder[ThresholdPropositionCurve25519] =
      deriveKeyDecoderFromBifrostSerializer[ThresholdPropositionCurve25519]

  }

  trait Instances
      extends BifrostSerializerInstances
      with FromBytesInstances
      with JsonEncoderInstances
      with JsonDecoderInstances

  object implicits extends Instances
}
