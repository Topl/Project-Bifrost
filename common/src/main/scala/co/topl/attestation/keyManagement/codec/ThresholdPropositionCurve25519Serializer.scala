package co.topl.attestation.keyManagement.codec

import co.topl.attestation.keyManagement.proposition.implicits._
import co.topl.attestation.keyManagement.proposition.{PublicKeyPropositionCurve25519, ThresholdPropositionCurve25519}
import co.topl.utils.Extensions._
import co.topl.utils.serialization.{BifrostSerializer, Reader, Writer}

import scala.collection.SortedSet

object ThresholdPropositionCurve25519Serializer extends BifrostSerializer[ThresholdPropositionCurve25519] {

  override def serialize(obj: ThresholdPropositionCurve25519, w: Writer): Unit = {
    /* m: Int */
    w.putUInt(obj.threshold)

    /* setOfPubKeyBytes: Set[Array[Byte]] */
    w.putUInt(obj.pubKeyProps.size)
    obj.pubKeyProps.foreach(prop => w.putBytes(prop.value.value))
  }

  override def parse(r: Reader): ThresholdPropositionCurve25519 = {
    val threshold: Int = r.getUInt().toIntExact

    val numSigs: Int = r.getUInt().toIntExact
    val signatures: SortedSet[PublicKeyPropositionCurve25519] = SortedSet[PublicKeyPropositionCurve25519](
      (0 until numSigs).map(_ => PublicKeyPropositionCurve25519Serializer.parse(r)): _*
    )

    ThresholdPropositionCurve25519(threshold, signatures)
  }
}
