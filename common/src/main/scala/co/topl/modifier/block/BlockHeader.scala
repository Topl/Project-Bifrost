package co.topl.modifier.block

import co.topl.attestation.{PublicKeyPropositionEd25519, SignatureEd25519}
import co.topl.crypto.hash.digest.Digest32
import co.topl.modifier.NodeViewModifier.ModifierTypeId
import co.topl.modifier.block.PersistentNodeViewModifier.PNVMVersion
import co.topl.modifier.box.ArbitBox
import co.topl.modifier.{ModifierId, NodeViewModifier}
import co.topl.utils.TimeProvider
import co.topl.utils.codecs.CryptoCodec.implicits._
import io.circe.syntax.EncoderOps
import io.circe.{Decoder, Encoder, HCursor}

case class BlockHeader(
  id:           ModifierId,
  parentId:     ModifierId,
  timestamp:    TimeProvider.Time,
  generatorBox: ArbitBox,
  publicKey:    PublicKeyPropositionEd25519,
  signature:    SignatureEd25519,
  height:       Long,
  difficulty:   Long,
  txRoot:       Digest32,
  bloomFilter:  BloomFilter,
  version:      PNVMVersion
) extends PersistentNodeViewModifier {

  override lazy val modifierTypeId: ModifierTypeId = BlockHeader.modifierTypeId

}

object BlockHeader {
  val modifierTypeId: NodeViewModifier.ModifierTypeId = ModifierTypeId(4: Byte)

  implicit val jsonEncoder: Encoder[BlockHeader] = { bh: BlockHeader =>
    Map(
      "id"           -> bh.id.toString.asJson,
      "parentId"     -> bh.parentId.toString.asJson,
      "timestamp"    -> bh.timestamp.asJson,
      "generatorBox" -> bh.generatorBox.asJson,
      "publicKey"    -> bh.publicKey.asJson,
      "signature"    -> bh.signature.asJson,
      "height"       -> bh.height.asJson,
      "difficulty"   -> bh.difficulty.asJson,
      "txRoot"       -> bh.txRoot.asJson,
      "bloomFilter"  -> bh.bloomFilter.asJson,
      "version"      -> bh.version.asJson
    ).asJson
  }

  implicit val jsonDecoder: Decoder[BlockHeader] = (c: HCursor) =>
    for {
      id           <- c.downField("id").as[ModifierId]
      parentId     <- c.downField("parentId").as[ModifierId]
      timestamp    <- c.downField("timestamp").as[TimeProvider.Time]
      generatorBox <- c.downField("generatorBox").as[ArbitBox]
      publicKey    <- c.downField("publicKey").as[PublicKeyPropositionEd25519]
      signature    <- c.downField("signature").as[SignatureEd25519]
      height       <- c.downField("height").as[Long]
      difficulty   <- c.downField("difficulty").as[Long]
      txRoot       <- c.downField("txRoot").as[Digest32]
      bloomFilter  <- c.downField("bloomFilter").as[BloomFilter]
      version      <- c.downField("version").as[Byte]
    } yield BlockHeader(
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
