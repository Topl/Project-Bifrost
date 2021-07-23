package co.topl.consensus

import co.topl.attestation.EvidenceProducer.Syntax._
import co.topl.attestation._
import co.topl.consensus.Forger.ChainParams
import co.topl.consensus.genesis.GenesisProvider
import co.topl.modifier.ModifierId
import co.topl.modifier.block.Block
import co.topl.modifier.block.PersistentNodeViewModifier.PNVMVersion
import co.topl.modifier.box.{ArbitBox, SimpleValue}
import co.topl.modifier.transaction.{ArbitTransfer, PolyTransfer}
import co.topl.settings.AppSettings
import co.topl.utils.Int128
import co.topl.utils.NetworkType.NetworkPrefix

import scala.collection.immutable.ListMap
import scala.util.Try

case class TestGenesis(addressesCurve25519: Set[Address], addressesEd25519: Set[Address], settings: AppSettings)(
  implicit val networkPrefix:               NetworkPrefix
) extends GenesisProvider {

  override protected val blockChecksum: ModifierId = ModifierId.empty

  override protected val blockVersion: PNVMVersion = settings.application.version.blockByte

  override protected val members: ListMap[String, Int128] = ListMap("Not implemented here" -> 0L)

  override def getGenesisBlock: Try[(Block, ChainParams)] = Try(formNewBlock)

  /**
   * We want a private network to have a brand new genesis block that is created at runtime. This is
   * done to allow the user to forge on their private network. Therefore, we need to generate a new set of keys
   * by making a call to the key manager holder to create a the set of forging keys. Once these keys are created,
   * we can use the public images to pre-fund the accounts from genesis.
   */
  val (_, balance, initialDifficulty) = settings.forging.privateTestnet
    .map { settings =>
      (settings.numTestnetAccts, settings.testnetBalance, settings.initialDifficulty)
    }
    .getOrElse(10, 1000000L, 1000000000000000000L)

  def formNewBlock: (Block, ChainParams) = {
    // map the members to their balances then continue as normal
    val privateTotalStake = (addressesCurve25519.size + addressesEd25519.size) * balance

    val txInputCurve25519 = (
      IndexedSeq(),
      (genesisAcctCruve25519.publicImage.address -> SimpleValue(0L)) +: addressesCurve25519
        .map(_ -> SimpleValue(balance))
        .toIndexedSeq,
      Map(genesisAcctCruve25519.publicImage -> SignatureCurve25519.genesis),
      Int128(0),
      0L,
      None,
      true
    )

    val txInputEd25519 = (
      IndexedSeq(),
      (genesisAcctEd25519.publicImage.address -> SimpleValue(0L)) +: addressesEd25519
        .map(_ -> SimpleValue(balance))
        .toIndexedSeq,
      Map(genesisAcctEd25519.publicImage -> SignatureEd25519.genesis),
      Int128(0),
      0L,
      None,
      true
    )

    val txs = Seq(
      (ArbitTransfer[PublicKeyPropositionCurve25519] _).tupled(txInputCurve25519),
      (PolyTransfer[PublicKeyPropositionCurve25519] _).tupled(txInputCurve25519),
      (ArbitTransfer[PublicKeyPropositionEd25519] _).tupled(txInputEd25519),
      (PolyTransfer[PublicKeyPropositionEd25519] _).tupled(txInputEd25519)
    )

    val generatorBox = ArbitBox(genesisAcctCruve25519.publicImage.generateEvidence, 0, SimpleValue(privateTotalStake))

    val signature = SignatureCurve25519.genesis

    val block =
      Block(
        ModifierId.genesisParentId,
        0L,
        generatorBox,
        genesisAcctCruve25519.publicImage,
        signature,
        1L,
        initialDifficulty,
        txs,
        blockVersion
      )

    log.debug(s"Initialize state with block $block")

    (block, ChainParams(privateTotalStake, initialDifficulty))
  }
}
