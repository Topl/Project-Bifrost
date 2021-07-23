package co.topl.modifier.transaction.validation

import cats.data.NonEmptyChain
import cats.scalatest.{ValidatedMatchers, ValidatedNecMatchers}
import co.topl.attestation.{Address, Proposition, PublicKeyPropositionCurve25519, PublicKeyPropositionEd25519}
import co.topl.modifier.box._
import co.topl.modifier.transaction._
import co.topl.modifier.transaction.validation.implicits._
import co.topl.utils.NetworkType.PrivateTestnet
import co.topl.utils.{NetworkType, NodeGenerators}
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.propspec.AnyPropSpec
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks

import scala.util.Random

class TransactionValidationSpec
    extends AnyPropSpec
    with ScalaCheckDrivenPropertyChecks
    with Matchers
    with NodeGenerators
    with EitherValues
    with ValidatedMatchers
    with ValidatedNecMatchers {

  type TransferTx = TransferTransaction[TokenValueHolder, _ <: Proposition]

  property("Randomly generated AssetTransfer Tx should be valid") {
    forAll(validAssetTransferGen(keyRingCurve25519, keyRingEd25519, genesisState, minting = true)) { tx =>
      tx.syntacticValidation should beValid[TransferTx](tx)
    }
  }

  property("AssetTransfer with minting = true should fail if no PolyBoxes are provided") {
    val genCurve25519 = validAssetTransferCurve25519Gen(keyRingCurve25519, genesisState, fee = 0, minting = true).map {
      tx =>
        val fromWithoutPolys: IndexedSeq[(Address, Box.Nonce)] = tx.from
          .map { case (address, nonce) =>
            val boxId = BoxId.idFromEviNonce(address.evidence, nonce)
            val box = genesisState.getBox(boxId).get
            (address, nonce, box)
          }
          .collect { case (address, nonce, _: AssetBox) =>
            (address, nonce)
          }

        val noPolyRawTx = tx.copy(from = fromWithoutPolys)
        val sender = keyRingCurve25519.addresses.head
        noPolyRawTx.copy(attestation =
          Transaction.updateAttestation(noPolyRawTx)(keyRingCurve25519.generateAttestation(sender))
        )
    }
    forAll(genCurve25519)(tx =>
      tx.semanticValidation(genesisState) should haveInvalidC[SemanticValidationFailure](
        SyntacticSemanticValidationFailure(
          NonEmptyChain(NoInputBoxesSpecified)
        )
      )
    )

    val genEd25519 = validAssetTransferEd25519Gen(keyRingEd25519, genesisState, fee = 0, minting = true).map { tx =>
      val fromWithoutPolys: IndexedSeq[(Address, Box.Nonce)] = tx.from
        .map { case (address, nonce) =>
          val boxId = BoxId.idFromEviNonce(address.evidence, nonce)
          val box = genesisState.getBox(boxId).get
          (address, nonce, box)
        }
        .collect { case (address, nonce, _: AssetBox) =>
          (address, nonce)
        }

      val noPolyRawTx = tx.copy(from = fromWithoutPolys)
      val sender = keyRingEd25519.addresses.head
      noPolyRawTx.copy(attestation =
        Transaction.updateAttestation(noPolyRawTx)(keyRingEd25519.generateAttestation(sender))
      )
    }
    forAll(genEd25519)(tx =>
      tx.semanticValidation(genesisState) should haveInvalidC[SemanticValidationFailure](
        SyntacticSemanticValidationFailure(
          NonEmptyChain(NoInputBoxesSpecified)
        )
      )
    )
  }

  property("Attempting to validate a AssetTransfer without valid signature should error") {
    // Create invalid AssetTransfer
    // send tx to state
    forAll(assetTransferGen) { tx =>
      tx.syntacticValidation should haveInvalidC[SyntacticValidationFailure](UnsatisfiedProposition)
      tx.syntacticValidation should haveInvalidC[SyntacticValidationFailure](PropositionEvidenceMismatch)
      tx.syntacticValidation should haveInvalidC[SyntacticValidationFailure](MintingMissingIssuersSignature)
    }
  }

  property("Attempting to validate an AssetTransfer with data of invalid length should error") {
    forAll(stringGen) { data =>
      whenever(data.length >= 128) {
        val tx = assetTransferEd25519Gen.sample.get
        val invalidDataTx = tx.copy(data = Some(data))
        invalidDataTx.syntacticValidation should haveInvalidC[SyntacticValidationFailure](DataTooLong)
      }
    }
  }

  property("Attempting to validate an AssetTransfer with metadata of invalid length should error") {
    forAll(stringGen) { metadata =>
      whenever(metadata.length >= 128) {
        val tx = assetTransferEd25519Gen.sample.get
        val assetValue = assetValueEd25519Gen.sample.get.copy(metadata = Some(metadata))
        val invalidDataTx = tx.copy(to = IndexedSeq((assetValue.assetCode.issuer, assetValue)))
        invalidDataTx.syntacticValidation should haveInvalidC[SyntacticValidationFailure](DataTooLong)
      }
    }
  }

  property("Attempting to validate an AssetTransfer with non-latin1 metadata should error") {
    forAll(assetTransferEd25519Gen) { tx =>
      val metadata = "ˇ"
      val assetValue = assetValueEd25519Gen.sample.get.copy(metadata = Some(metadata))
      val invalidDataTx = tx.copy(to = IndexedSeq((assetValue.assetCode.issuer, assetValue)))
      invalidDataTx.syntacticValidation should haveInvalidC[SyntacticValidationFailure](DataNotLatin1)
    }
  }

  property("Randomly generated ArbitTransfer Tx should be valid") {
    forAll(validArbitTransferGen(keyRingCurve25519, keyRingEd25519, genesisState)) { tx =>
      tx.syntacticValidation should beValid[TransferTx](tx)
    }
  }

  property("Attempting to validate a ArbitTransfer without valid signature should error") {
    // Create invalid PolyTransfer
    // send tx to state
    forAll(arbitTransferGen) { tx =>
      tx.syntacticValidation should haveInvalidC[SyntacticValidationFailure](UnsatisfiedProposition)
      tx.syntacticValidation should haveInvalidC[SyntacticValidationFailure](PropositionEvidenceMismatch)
    }
  }

  property("ArbitTransfer should fail if no PolyBoxes are provided") {
    val genCurve25519 =
      validArbitTransferCurve25519Gen(keyRingCurve25519, genesisState, fee = 0)
        .map { tx =>
          val fromWithoutPolys: IndexedSeq[(Address, Box.Nonce)] = tx.from
            .map { case (address, nonce) =>
              val boxId = BoxId.idFromEviNonce(address.evidence, nonce)
              val box = genesisState.getBox(boxId).get
              (address, nonce, box)
            }
            .collect { case (address, nonce, _: ArbitBox) =>
              (address, nonce)
            }

          signTx(tx.copy(from = fromWithoutPolys))

        }
    forAll(genCurve25519) { tx =>
      tx.semanticValidation(genesisState) should haveInvalidC[SemanticValidationFailure](
        InputFeeChangeOutputUnequalNonMinting(
          1000000,
          0,
          0
        )
      )
    }

    val genEd25519 =
      validArbitTransferEd25519Gen(keyRingEd25519, genesisState, fee = 0)
        .map { tx =>
          val fromWithoutPolys: IndexedSeq[(Address, Box.Nonce)] = tx.from
            .map { case (address, nonce) =>
              val boxId = BoxId.idFromEviNonce(address.evidence, nonce)
              val box = genesisState.getBox(boxId).get
              (address, nonce, box)
            }
            .collect { case (address, nonce, _: ArbitBox) =>
              (address, nonce)
            }

          signTx(tx.copy(from = fromWithoutPolys))

        }
    forAll(genEd25519) { tx =>
      tx.semanticValidation(genesisState) should haveInvalidC[SemanticValidationFailure](
        InputFeeChangeOutputUnequalNonMinting(
          1000000,
          0,
          0
        )
      )
    }
  }

  property("Transactions created on a specific network should not be accepted on any other network") {
    val otherNetworks = NetworkType.all.filterNot(_ == PrivateTestnet)
    forAll(validAssetTransferGen(keyRingCurve25519, keyRingEd25519, genesisState, minting = true)) { tx =>
      otherNetworks.foreach { netType =>
        tx.syntacticValidation(netType.netPrefix) should haveInvalidC[SyntacticValidationFailure](
          MintingMissingIssuersSignature
        )
      }
      tx.syntacticValidation(PrivateTestnet.netPrefix) should beValid[TransferTx](tx)
    }
  }

  property("Generated PolyTransfer Tx should be valid") {
    forAll(validPolyTransferGen(keyRingCurve25519, keyRingEd25519, genesisState)) { tx =>
      tx.syntacticValidation should beValid[TransferTx](tx)
    }
  }

  property("Attempting to validate a PolyTransfer without valid signature should error") {
    // Create invalid PolyTransfer
    // send tx to state
    forAll(polyTransferCurve25519Gen) { tx =>
      tx.syntacticValidation should haveInvalidC[SyntacticValidationFailure](UnsatisfiedProposition)
    }
  }

  property("Transaction with negative fee should be invalid") {
    forAll(validPolyTransferCurve25519Gen(keyRingCurve25519, genesisState).map(tx => signTx(tx.copy(fee = -1)))) { tx =>
      tx.syntacticValidation should haveInvalidC[SyntacticValidationFailure](NegativeFeeFailure)
    }

    forAll(validPolyTransferEd25519Gen(keyRingEd25519, genesisState).map(tx => signTx(tx.copy(fee = -1)))) { tx =>
      tx.syntacticValidation should haveInvalidC[SyntacticValidationFailure](NegativeFeeFailure)
    }
  }

  property("Transaction with negative timestamp should be invalid") {
    forAll(
      validPolyTransferCurve25519Gen(keyRingCurve25519, genesisState).map(tx => signTx(tx.copy(timestamp = -1)))
    ) { tx =>
      tx.syntacticValidation should haveInvalidC[SyntacticValidationFailure](InvalidTimestamp)
    }

    forAll(validPolyTransferEd25519Gen(keyRingEd25519, genesisState).map(tx => signTx(tx.copy(timestamp = -1)))) { tx =>
      tx.syntacticValidation should haveInvalidC[SyntacticValidationFailure](InvalidTimestamp)
    }
  }

  property("Transaction with non-latin1 data should be invalid") {
    val data = "ˇ"

    forAll(
      validPolyTransferCurve25519Gen(keyRingCurve25519, genesisState).map(tx => signTx(tx.copy(data = Some(data))))
    ) { tx =>
      tx.syntacticValidation should haveInvalidC[SyntacticValidationFailure](DataNotLatin1)
    }

    forAll(
      validPolyTransferEd25519Gen(keyRingEd25519, genesisState).map(tx => signTx(tx.copy(data = Some(data))))
    ) { tx =>
      tx.syntacticValidation should haveInvalidC[SyntacticValidationFailure](DataNotLatin1)
    }
  }

  property("Transaction with data length > 128 bytes should be invalid") {
    val data = Random.alphanumeric.take(512).mkString

    forAll(
      validPolyTransferCurve25519Gen(keyRingCurve25519, genesisState).map(tx => signTx(tx.copy(data = Some(data))))
    ) { tx =>
      tx.syntacticValidation should haveInvalidC[SyntacticValidationFailure](DataTooLong)
    }

    forAll(
      validPolyTransferEd25519Gen(keyRingEd25519, genesisState).map(tx => signTx(tx.copy(data = Some(data))))
    ) { tx =>
      tx.syntacticValidation should haveInvalidC[SyntacticValidationFailure](DataTooLong)
    }
  }

  private def signTx(
    tx: PolyTransfer[PublicKeyPropositionCurve25519]
  ): PolyTransfer[PublicKeyPropositionCurve25519] =
    tx.copy(attestation =
      Transaction.updateAttestation(tx)(keyRingCurve25519.generateAttestation(tx.from.map(_._1).toSet))
    )

  private def signTx(tx: => PolyTransfer[PublicKeyPropositionEd25519]): PolyTransfer[PublicKeyPropositionEd25519] =
    tx.copy(attestation =
      Transaction.updateAttestation(tx)(keyRingEd25519.generateAttestation(tx.from.map(_._1).toSet))
    )

  private def signTx(
    tx: ArbitTransfer[PublicKeyPropositionCurve25519]
  ): ArbitTransfer[PublicKeyPropositionCurve25519] =
    tx.copy(attestation =
      Transaction.updateAttestation(tx)(keyRingCurve25519.generateAttestation(tx.from.map(_._1).toSet))
    )

  private def signTx(
    tx: => ArbitTransfer[PublicKeyPropositionEd25519]
  ): ArbitTransfer[PublicKeyPropositionEd25519] =
    tx.copy(attestation =
      Transaction.updateAttestation(tx)(keyRingEd25519.generateAttestation(tx.from.map(_._1).toSet))
    )

  private def signTx(
    tx: AssetTransfer[PublicKeyPropositionCurve25519]
  ): AssetTransfer[PublicKeyPropositionCurve25519] =
    tx.copy(attestation =
      Transaction.updateAttestation(tx)(keyRingCurve25519.generateAttestation(tx.from.map(_._1).toSet))
    )

  private def signTx(
    tx: => AssetTransfer[PublicKeyPropositionEd25519]
  ): AssetTransfer[PublicKeyPropositionEd25519] =
    tx.copy(attestation =
      Transaction.updateAttestation(tx)(keyRingEd25519.generateAttestation(tx.from.map(_._1).toSet))
    )
}
