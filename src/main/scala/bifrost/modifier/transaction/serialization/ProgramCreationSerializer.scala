package bifrost.modifier.transaction.serialization

import java.util.UUID

import bifrost.crypto.Signature25519
import bifrost.crypto.serialization.Signature25519Serializer
import bifrost.modifier.box.proposition.{PublicKey25519Proposition, PublicKey25519PropositionSerializer}
import bifrost.modifier.transaction.bifrostTransaction.ProgramCreation
import bifrost.modifier.transaction.bifrostTransaction.Transaction.Nonce
import bifrost.program.{ExecutionBuilder, ExecutionBuilderSerializer}
import bifrost.utils.Extensions._
import bifrost.utils.serialization.{BifrostSerializer, Reader, Writer}

//noinspection ScalaStyle
object ProgramCreationSerializer extends BifrostSerializer[ProgramCreation] {

  override def serialize(obj: ProgramCreation, w: Writer): Unit = {
    /* executionBuilder: ExecutionBuilder */
    ExecutionBuilderSerializer.serialize(obj.executionBuilder, w)

    /* readOnlyStateBoxes: Seq[UUID] */
    w.putUInt(obj.readOnlyStateBoxes.length)
    obj.readOnlyStateBoxes.foreach { id =>
      w.putLong(id.getMostSignificantBits)
      w.putLong(id.getLeastSignificantBits)
    }

    /* preInvestmentBoxes: IndexedSeq[(Nonce, Long)] */
    w.putUInt(obj.preInvestmentBoxes.length)
    obj.preInvestmentBoxes.foreach { box =>
      w.putLong(box._1)
      w.putULong(box._2)
    }

    /* owner: PublicKey25519Proposition */
    PublicKey25519PropositionSerializer.serialize(obj.owner, w)

    // TODO: Jing - We will need to change this to just the signature
    /* signatures: Map[PublicKey25519Proposition, Signature25519] */
    Signature25519Serializer.serialize(obj.signatures.head._2, w)

    // TODO: Jing - preFeeBoxes will be removed
    /* preFeeBoxes: Map[PublicKey25519Proposition, IndexedSeq[(Nonce, Long)]] */
    /* nonce can be negative and value is positive */
    w.putUInt(obj.preFeeBoxes.head._2.length)
    obj.preFeeBoxes.head._2.foreach { case (nonce, value) =>
      w.putLong(nonce)
      w.putULong(value)
    }

    /* fees: Map[PublicKey25519Proposition, Long] */
    w.putULong(obj.fees.head._2)

    /* timestamp: Long */
    w.putULong(obj.timestamp)

    /* data: String */
    w.putIntString(obj.data)
  }

  override def parse(r: Reader): ProgramCreation = {
    val executionBuilder: ExecutionBuilder = ExecutionBuilderSerializer.parse(r)
    val readOnlyStateBoxesLength: Int = r.getUInt().toIntExact
    val readOnlyStateBoxes: Seq[UUID] = (0 until readOnlyStateBoxesLength).map(_ => new UUID(r.getLong(), r.getLong()))
    val preInvestmentBoxesLength: Int = r.getUInt().toIntExact

    val preInvestmentBoxes: IndexedSeq[(Nonce, Long)] = (0 until preInvestmentBoxesLength).map { _ =>
      val nonce: Nonce = r.getLong()
      val value: Long = r.getULong()
      nonce -> value
    }

    val owner: PublicKey25519Proposition = PublicKey25519PropositionSerializer.parse(r)

    val signatures: Map[PublicKey25519Proposition, Signature25519] = {
      val sig = Signature25519Serializer.parse(r)
      Map(owner -> sig)
    }

    val preBoxesLength: Int = r.getUInt.toIntExact
    val preBoxes: IndexedSeq[(Nonce, Long)] = (0 until preBoxesLength).map { _ =>
      val nonce: Nonce = r.getLong()
      val value: Long = r.getULong()
      nonce -> value
    }
    val preFeeBoxes: Map[PublicKey25519Proposition, IndexedSeq[(Nonce, Long)]] = Map(owner -> preBoxes)

    val fees: Map[PublicKey25519Proposition, Long] = Map(owner -> r.getULong())
    val timestamp: Long = r.getULong()
    val data: String = r.getIntString()

    ProgramCreation(
      executionBuilder,
      readOnlyStateBoxes,
      preInvestmentBoxes,
      owner,
      signatures,
      preFeeBoxes,
      fees,
      timestamp,
      data
    )
  }
}
