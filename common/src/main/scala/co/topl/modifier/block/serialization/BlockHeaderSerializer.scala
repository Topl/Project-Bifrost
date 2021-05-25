package co.topl.modifier.block.serialization

import co.topl.attestation.serialization.{PublicKeyPropositionEd25519Serializer, SignatureEd25519Serializer}
import co.topl.attestation.{PublicKeyPropositionEd25519, SignatureEd25519}
import co.topl.crypto.hash.digest.Digest32
import co.topl.modifier.ModifierId
import co.topl.modifier.block.{BlockHeader, BloomFilter}
import co.topl.modifier.box.ArbitBox
import co.topl.modifier.box.serialization.ArbitBoxSerializer
import co.topl.utils.IdiomaticScalaTransition.implicits.toValidatedOps
import co.topl.utils.serialization.{BifrostSerializer, Reader, Writer}

object BlockHeaderSerializer extends BifrostSerializer[BlockHeader] {

  override def serialize(header: BlockHeader, w: Writer): Unit = {
    /* version: Byte */
    w.put(header.version)

    /* blockId: ModifiedId */
    ModifierId.serialize(header.id, w)

    /* parentId: ModifierId */
    ModifierId.serialize(header.parentId, w)

    /* timestamp: Long */
    w.putULong(header.timestamp)

    /* generatorBox: ArbitBox */
    ArbitBoxSerializer.serialize(header.generatorBox, w)

    /* publicKey: PublicKeyCurve25519Proposition */
    PublicKeyPropositionEd25519Serializer.serialize(header.publicKey, w)

    /* signature: Signature25519 */
    SignatureEd25519Serializer.serialize(header.signature, w)

    /* height: Long */
    w.putLong(header.height)

    /* difficulty: Long */
    w.putLong(header.difficulty)

    /* txRoot: Array[Byte] */
    w.putBytes(header.txRoot.value)

    /* bloomFilter: Array[Long] */
    BloomFilter.serialize(header.bloomFilter, w)
  }

  override def parse(r: Reader): BlockHeader = {
    val version: Byte = r.getByte()

    val id: ModifierId = ModifierId.parse(r)

    val parentId: ModifierId = ModifierId.parse(r)

    val timestamp: Long = r.getULong()

    val generatorBox: ArbitBox = ArbitBoxSerializer.parse(r)

    val publicKey: PublicKeyPropositionEd25519 = PublicKeyPropositionEd25519Serializer.parse(r)

    val signature: SignatureEd25519 = SignatureEd25519Serializer.parse(r)

    val height: Long = r.getLong()

    val difficulty: Long = r.getLong()

    val txRoot: Digest32 = Digest32
      .validated(r.getBytes(Digest32.size))
      .getOrThrow()

    val bloomFilter: BloomFilter = BloomFilter.parse(r)

    BlockHeader(
      id,
      parentId,
      timestamp,
      generatorBox,
      publicKey,
      signature,
      height,
      difficulty,
      txRoot,
      bloomFilter,
      version
    )
  }
}
