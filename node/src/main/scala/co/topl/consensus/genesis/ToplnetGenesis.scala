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

case object ToplnetGenesis extends GenesisProvider {

  implicit val networkPrefix: NetworkPrefix = NetworkType.Mainnet.netPrefix

  override protected val blockChecksum: ModifierId =
    ModifierId.fromBase58(Base58Data.unsafe("y2srCtQZiV2XkYfhjgmyxZQE8SZcETQrxZjKGHGuFjv7"))

  override protected val blockVersion: PNVMVersion = 1: Byte

  override protected val initialDifficulty: Long = 1000000000000000000L

  override protected val members: ListMap[String, Int128] = ListMap(
    "9caWb2hfQdRceaSBdhh9yxNTyVzTKvDn5HEUdi2oaQ77hpZRhPS" -> 10000000000000000L,
    "9dGWgpcR7kJw2HsWNPed4J7e8Vn61vwKHwLjrUawuXR1vcYC4QB" -> 10000000000000000L,
    "9d42DjKEQ2CTiC6sF8WjLGGTrr7wRYWDB1urGVvBJkQRDYvxyFf" -> 10000000000000000L,
    "9cuQTFYRLpgmansPVFXaoqBHofLwWkktCt33a7WXM9s1HUqHWgv" -> 10000000000000000L,
    "9ckvdkLS1KbCeTVPwzf8cUTeognE3dbJNqZTH8727MSqrzL6hgY" -> 10000000000000000L,
    "9dcWXPVWkXRmFJfisXbBguVcfZvX75gnQX5hxbAm1JzNXsyJfT2" -> 10000000000000000L,
    "9e5gU6BeGRCGxQubPaRAG277P73Qjd2zWZU3CqiABmuVA1AvUt5" -> 10000000000000000L,
    "9chb47nYoSHWh8kt8GGP4XGZEcbJAKPJT3p8YNa9U7i6Q3kjMuR" -> 10000000000000000L,
    "9ceUYwXNYQNmTe7rc6MF95yCMJhr2PoMqLtgE4Yitk9w2zSBAoD" -> 10000000000000000L,
    "9d1seGeX3xZ1Q7qfnCRamwZZFozWeHCALEwPvypLCCc7wMGUcNJ" -> 10000000000000000L,
    "9dCq5YpBzMeDx1HKbih2SxRxRuDQXrxHk5gV76jmu4ni93peJoq" -> 10000000000000000L,
    "9cpA8Vp2wZkTaVqsdXtxttPnSzQsqjRMfqqeoRnkyaWq71D83QM" -> 10000000000000000L,
    "9eWrmeAfGxPgsLCi1YhAmSwLoDT8k2Z2b9sJvXM1EWAcN7DeDBs" -> 10000000000000000L,
    "9dG87swWcJ9WHigMfF9i8ZU2kz5wYQtwqpFV7r5moUjRWobnH55" -> 10000000000000000L,
    "9dLUQ8R7yihAiCVoFwH3VorDF6FCabSFrhdfbM6cmSN9AaWzqC3" -> 10000000000000000L,
    "9eHs4AcM6K8qs8yk7njQfeBsun8aKH6n5YnGzEMaoZnW7ZZUDvs" -> 10000000000000000L,
    "9e2XbV8ueayCbf9dF3uVSN2UEVtwPv6hfpuQRPEfhbio9JtEXJU" -> 10000000000000000L,
    "9e61tfxRFP3xu3dPFsdCwJH7thn5Zh657BzwghpNWK1prkMPFPg" -> 10000000000000000L,
    "9dhrbcHDBm8Wcpo5CsNV3reowLqHqwVs7uGdp6SUXgm4bUT3mvW" -> 10000000000000000L,
    "9dWhopwVrLHw61RPWPBPbViXAJoZa6MfBMPiNt1CZcpbiHY8WH6" -> 10000000000000000L
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
