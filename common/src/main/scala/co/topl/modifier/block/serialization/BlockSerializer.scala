package co.topl.modifier.block.serialization

import co.topl.attestation.serialization.{PublicKeyPropositionEd25519Serializer, SignatureEd25519Serializer}
import co.topl.attestation.{PublicKeyPropositionEd25519, SignatureEd25519}
import co.topl.modifier.ModifierId
import co.topl.modifier.block.Block
import co.topl.modifier.box.ArbitBox
import co.topl.modifier.box.serialization.ArbitBoxSerializer
import co.topl.modifier.transaction.Transaction
import co.topl.modifier.transaction.serialization.TransactionSerializer
import co.topl.utils.Extensions.LongOps
import co.topl.utils.serialization.{BifrostSerializer, Reader, Writer}

object BlockSerializer extends BifrostSerializer[Block] {

  override def serialize(block: Block, w: Writer): Unit = {
    /* version: Byte */
    w.put(block.version)

    /* parentId: ModifierId */
    ModifierId.serialize(block.parentId, w)

    /* timestamp: Long */
    w.putULong(block.timestamp)

    /* generatorBox: ArbitBox */
    ArbitBoxSerializer.serialize(block.generatorBox, w)

    /* publicKey: PublicKeyCurve25519Proposition */
    PublicKeyPropositionEd25519Serializer.serialize(block.publicKey, w)

    /* signature: Signature25519 */
    SignatureEd25519Serializer.serialize(block.signature, w)

    /* height: Long */
    w.putULong(block.height)

    /* difficulty: Long */
    w.putULong(block.difficulty)

    /* txsLength: Int */
    w.putUInt(block.transactions.length)

    /* txs: Seq[Transaction] */
    block.transactions.foreach(tx => TransactionSerializer.serialize(tx, w))
  }

  override def parse(r: Reader): Block = {
    /* The order of the getByte, getLong... calls should not be changed */
    val version: Byte = r.getByte()

    val parentId: ModifierId = ModifierId.parse(r)

    val timestamp: Long = r.getULong()

    val generatorBox: ArbitBox = ArbitBoxSerializer.parse(r)

    val publicKey: PublicKeyPropositionEd25519 = PublicKeyPropositionEd25519Serializer.parse(r)

    val signature: SignatureEd25519 = SignatureEd25519Serializer.parse(r)

    val height: Long = r.getULong()

    val difficulty: Long = r.getULong()

    val txsLength: Int = r.getUInt().toIntExact
    val txs: Seq[Transaction.TX] = for (_ <- 0 until txsLength) yield TransactionSerializer.parse(r)

    Block(parentId, timestamp, generatorBox, publicKey, signature, height, difficulty, txs, version)
  }
}
