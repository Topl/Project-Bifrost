package co.topl.consensus.genesis

import co.topl.attestation.Address
import co.topl.attestation.AddressCodec.implicits._
import co.topl.attestation.keyManagement.{PrivateKeyCurve25519, PrivateKeyEd25519}
import co.topl.consensus.Forger.ChainParams
import co.topl.crypto.{PrivateKey, PublicKey}
import co.topl.modifier.ModifierId
import co.topl.modifier.block.Block
import co.topl.modifier.block.PersistentNodeViewModifier.PNVMVersion
import co.topl.utils.IdiomaticScalaTransition.implicits.toValidatedOps
import co.topl.utils.NetworkType.NetworkPrefix
import co.topl.utils.StringDataTypes.Base58Data
import co.topl.utils.{Int128, Logging}

import scala.collection.immutable.ListMap
import scala.util.Try

trait GenesisProvider extends Logging {

  implicit val networkPrefix: NetworkPrefix

  protected lazy val genesisAcctCurve25519: PrivateKeyCurve25519 =
    new PrivateKeyCurve25519(PrivateKey(Array.fill(32)(2: Byte)), PublicKey(Array.fill(32)(2: Byte)))

  protected lazy val genesisAcctEd25519: PrivateKeyEd25519 =
    new PrivateKeyEd25519(PrivateKey(Array.fill(32)(2: Byte)), PublicKey(Array.fill(32)(2: Byte)))

  protected lazy val totalStake: Int128 = members.values.sum

  protected val blockChecksum: ModifierId

  protected val blockVersion: PNVMVersion

  protected val initialDifficulty: Long

  protected val members: ListMap[String, Int128]

  def getGenesisBlock: Try[(Block, ChainParams)]

  protected def memberKeys: Iterable[Address] = members.keys.map(Base58Data.unsafe(_).decodeAddress.getOrThrow())

}
