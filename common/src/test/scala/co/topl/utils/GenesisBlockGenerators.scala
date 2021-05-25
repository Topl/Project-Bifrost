package co.topl.utils

import co.topl.attestation.{SignatureCurve25519, SignatureEd25519}
import co.topl.modifier.ModifierId
import co.topl.modifier.block.Block
import co.topl.modifier.block.PersistentNodeViewModifier.PNVMVersion
import co.topl.modifier.box.{ArbitBox, SimpleValue}
import org.scalacheck.Gen

import java.time.Instant
import scala.util.Try

trait GenesisBlockGenerators {
  self: KeyFileTestHelper =>

  lazy val genesisBlockGen: Gen[Block] = {
    val keyPair = keyRing.generateNewKeyPairs().get.head
    val matchingAddr = keyPair.publicImage.address
    val height: Long = 1L
    val difficulty = 1000000000000000000L
    val version: PNVMVersion = 1: Byte
    val signingFunction: Array[Byte] => Try[SignatureEd25519] =
      (messageToSign: Array[Byte]) => keyRing.signWithAddress(matchingAddr)(messageToSign)

    Block
      .createAndSign(
        ModifierId.genesisParentId,
        Instant.now().toEpochMilli,
        Seq(),
        ArbitBox(matchingAddr.evidence, 0L, SimpleValue(0)),
        keyPair.publicImage,
        height,
        difficulty,
        version
      )(signingFunction)
      .get
  }
}
