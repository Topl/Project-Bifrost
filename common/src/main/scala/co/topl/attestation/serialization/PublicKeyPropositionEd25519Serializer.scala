package co.topl.attestation.serialization

import co.topl.attestation.PublicKeyPropositionEd25519
import co.topl.crypto.PublicKey
import co.topl.crypto.signatures.Ed25519
import co.topl.utils.serialization.{BifrostSerializer, Reader, Writer}

object PublicKeyPropositionEd25519Serializer extends BifrostSerializer[PublicKeyPropositionEd25519] {

  override def serialize(obj: PublicKeyPropositionEd25519, w: Writer): Unit =
    w.putBytes(obj.pubKeyBytes.value)

  override def parse(r: Reader): PublicKeyPropositionEd25519 = {
    val proposition = r.getBytes(Ed25519.KeyLength)
    PublicKeyPropositionEd25519(PublicKey(proposition))
  }
}
