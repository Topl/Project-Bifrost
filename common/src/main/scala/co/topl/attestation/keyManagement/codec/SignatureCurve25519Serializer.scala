package co.topl.attestation.keyManagement.codec

import co.topl.attestation.keyManagement.proof.SignatureCurve25519
import co.topl.crypto.signatures.Signature
import co.topl.utils.serialization.{BifrostSerializer, Reader, Writer}

object SignatureCurve25519Serializer extends BifrostSerializer[SignatureCurve25519] {

  override def serialize(obj: SignatureCurve25519, w: Writer): Unit =
    w.putBytes(obj.value.value)

  override def parse(r: Reader): SignatureCurve25519 = {
    val sigBytes = r.getBytes(SignatureCurve25519.signatureSize)
    SignatureCurve25519(Signature(sigBytes))
  }
}
