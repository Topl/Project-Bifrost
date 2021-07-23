package co.topl.utils

import co.topl.attestation.keyManagement.{KeyRing, KeyfileCurve25519, PrivateKeyCurve25519}
import co.topl.attestation.{Address, SignatureCurve25519}
import co.topl.modifier.ModifierId
import co.topl.modifier.block.Block
import co.topl.modifier.block.PersistentNodeViewModifier.PNVMVersion
import co.topl.modifier.box.{ArbitBox, SimpleValue}
import co.topl.utils.NetworkType.NetworkPrefix
import org.scalacheck.Gen

import java.time.Instant
import scala.util.Try

trait GenesisBlockGenerators {

  def genesisBlockGen(
    keyRing:                KeyRing[PrivateKeyCurve25519, KeyfileCurve25519]
  )(implicit networkPrefix: NetworkPrefix): Gen[Block] =
    genesisBlockGen(keyRing, keyRing.generateNewKeyPairs().get.head.publicImage.address)

  def genesisBlockGen(
    keyRing:                KeyRing[PrivateKeyCurve25519, KeyfileCurve25519],
    address:                Address
  )(implicit networkPrefix: NetworkPrefix): Gen[Block] = {
    val height: Long = 1L
    val difficulty = 1000000000000000000L
    val version: PNVMVersion = 1: Byte
    val signingFunction: Array[Byte] => Try[SignatureCurve25519] =
      (messageToSign: Array[Byte]) => keyRing.signWithAddress(address)(messageToSign)

    Block
      .createAndSign(
        ModifierId.genesisParentId,
        Instant.now().toEpochMilli,
        Seq(),
        ArbitBox(address.evidence, 0L, SimpleValue(0)),
        keyRing.lookupPublicKey(address).get,
        height,
        difficulty,
        version
      )(signingFunction)
      .get
  }
}
