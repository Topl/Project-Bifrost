package co.topl.consensus.genesis

import co.topl.attestation.EvidenceProducer.Syntax._
import co.topl.attestation.{PublicKeyPropositionCurve25519, SignatureCurve25519}
import co.topl.consensus.Forger.ChainParams
import co.topl.modifier.ModifierId
import co.topl.modifier.block.Block
import co.topl.modifier.block.PersistentNodeViewModifier.PNVMVersion
import co.topl.modifier.box.{ArbitBox, SimpleValue}
import co.topl.modifier.transaction.{ArbitTransfer, PolyTransfer}
import co.topl.utils.NetworkType.NetworkPrefix
import co.topl.utils.StringDataTypes.Base58Data
import co.topl.utils.{Int128, NetworkType}

import scala.collection.immutable.ListMap
import scala.util.Try

case object ValhallaGenesis extends GenesisProvider {

  implicit val networkPrefix: NetworkPrefix = NetworkType.ValhallaTestnet.netPrefix

  override protected val blockChecksum: ModifierId =
    ModifierId.fromBase58(Base58Data.unsafe("wgUeiENYY32eC5T6WM2UiqAf6Ayba2tFNtvFkgn999iG"))

  override protected val blockVersion: PNVMVersion = 1: Byte

  override protected val initialDifficulty: Long = 1000000000000000000L

  override protected val members: ListMap[String, Int128] = ListMap(
    "3NLFmkjhx9aUh7mEcoAZjdWoU5UL5hD77L5ouQXtd11d5LgrMfQM" -> 10000000000000000L,
    "3NKx8PobhPkukn9s3Pg7j9xUUTRNRNSFwcEZdKHgNt4oRU5UJnZu" -> 10000000000000000L,
    "3NLcZmubgTAGxpk3y8ByEgMaTqYMLVMi9FfZg82gawgjByWdpzNB" -> 10000000000000000L,
    "3NLe6SSEccbD74NzwjLbPK9hsxjQfrVNuBuwsX4xxT6qKBsHNTjN" -> 10000000000000000L,
    "3NLGiBf3uRNUwKfM9Cf75p3ZJM32kpKVC4ddsqkcV2qSMA5QviBq" -> 10000000000000000L,
    "3NK4UGDB1b1E9eYfPtRKwpMXoSCQFctwq2ptkwxjCRDHgxK9nt4P" -> 10000000000000000L,
    "3NLpNbhznjmKrR1447GBPr8NUxqwGxDSDCqWudLHSpDruY6JxkGy" -> 10000000000000000L,
    "3NLGhm1zTcvjCjwnYPVsZ3d3uSk9MaQHBFnhaThpwNPqrQnnx6ns" -> 10000000000000000L,
    "3NKiHsXPtmwZth9AfGyyBCbbHRVon3ziNHKyX7dvHy6yVuAhb6uf" -> 10000000000000000L,
    "3NL9rLQHetFaNcgwTZZcY1LaPZgsMqVVrZUf3aC3QRPZ3m8DhLzu" -> 10000000000000000L,
    "3NKP1qjCBPWeirf5k8wKL9FzviauQPq1A8Hgbm6McfGVrSgW4g5t" -> 10000000000000000L,
    "3NKPebY7KBLGYBbnNjZoaD5FhCzK4M5bmzJY445U2WFYCQwaSdqK" -> 10000000000000000L,
    "3NK91VPASi2uUwzMe3GCxvzntLj2xY78aZiVD7evT9vAv23MrgTT" -> 10000000000000000L,
    "3NKagfyL2GGdLS4AQjoWaNuwrCHkBTMzKKKxULHrZY3dqTVKHbQH" -> 10000000000000000L,
    "3NLRkk6Hw5MaqgstCC2wGDSwcGYRBJxTiAYatV5voeU9HXacG5cp" -> 10000000000000000L,
    "3NLsfCVB1jYoQauXeFkKYqMnykeT25fgH7Gd4uQNtFQhUcPXTWza" -> 10000000000000000L,
    "3NKWBQoyfDtxQrGgcUHYVaaMnqcnqaQiS8ENqTQtHgRbVuaRWXF7" -> 10000000000000000L,
    "3NKQdgr3dZMgPVgs7kDpjqkuAnKVrT8d6p9zasaGzsTBwQfTWDLn" -> 10000000000000000L,
    "3NKs8wzRemNvqF1t68Z8T25nr4NZMvCSGTpS5sMoj8yq9Etq8ujv" -> 10000000000000000L,
    "3NKcfNkLkdQfuVdXt74sNYFX8PLFsNLVeqbs7PWzBu6iS3tA6KVZ" -> 10000000000000000L
  )

  def getGenesisBlock: Try[(Block, ChainParams)] = Try {

    val txInput = (
      IndexedSeq(),
      memberKeys.zip(members.values.map(SimpleValue(_))).toIndexedSeq,
      ListMap(genesisAcctCurve25519.publicImage -> SignatureCurve25519.genesis),
      Int128(0),
      0L,
      None,
      true
    )

    val txs = Seq(
      ArbitTransfer[PublicKeyPropositionCurve25519](
        txInput._1,
        txInput._2,
        txInput._3,
        txInput._4,
        txInput._5,
        txInput._6,
        txInput._7
      ),
      PolyTransfer[PublicKeyPropositionCurve25519](
        txInput._1,
        txInput._2,
        txInput._3,
        txInput._4,
        txInput._5,
        txInput._6,
        txInput._7
      )
    )

    val generatorBox = ArbitBox(genesisAcctCurve25519.publicImage.generateEvidence, 0, SimpleValue(totalStake))

    val signature = SignatureCurve25519.genesis

    val block =
      Block(
        ModifierId.genesisParentId,
        0L,
        generatorBox,
        genesisAcctCurve25519.publicImage,
        signature,
        1L,
        initialDifficulty,
        txs,
        blockVersion
      )

    require(
      block.id == blockChecksum,
      s"${Console.RED}MALFORMED GENESIS BLOCK! The calculated genesis block " +
      s"with id ${block.id} does not match the required block for the chosen network mode.${Console.RESET}"
    )

    log.debug(s"Initialize state with transaction ${txs.head} with boxes ${txs.head.newBoxes}")

    (block, ChainParams(totalStake, initialDifficulty))
  }
}
