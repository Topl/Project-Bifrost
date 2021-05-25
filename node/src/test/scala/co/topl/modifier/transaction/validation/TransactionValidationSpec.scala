package co.topl.modifier.transaction.validation

import cats.data.NonEmptyChain
import cats.scalatest.{ValidatedMatchers, ValidatedNecMatchers}
import co.topl.attestation.{Address, PublicKeyPropositionEd25519}
import co.topl.modifier.box._
import co.topl.modifier.transaction.validation.implicits._
import co.topl.modifier.transaction._
import co.topl.utils.NetworkType.PrivateTestnet
import co.topl.utils.{CommonGenerators, NetworkType, NodeGenerators}
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.propspec.AnyPropSpec
import org.scalatestplus.scalacheck.{ScalaCheckDrivenPropertyChecks, ScalaCheckPropertyChecks}

import scala.util.Random

class TransactionValidationSpec
    extends AnyPropSpec
    with ScalaCheckPropertyChecks
    with ScalaCheckDrivenPropertyChecks
    with Matchers
    with CommonGenerators
    with NodeGenerators
    with EitherValues
    with ValidatedMatchers
    with ValidatedNecMatchers {

  type TransferTx = TransferTransaction[TokenValueHolder, PublicKeyPropositionEd25519]

  property("Randomly generated AssetTransfer Tx should be valid") {
    forAll(validAssetTransfer(keyRing, genesisState, minting = true)) { tx =>
      tx.syntacticValidation should beValid[TransferTx](tx)
    }
  }

  property("AssetTransfer with minting = true should fail if no PolyBoxes are provided") {
    val gen = validAssetTransfer(keyRing, genesisState, fee = 0, minting = true).map { tx =>
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
      val sender = keyRing.addresses.head
      noPolyRawTx.copy(attestation = Transaction.updateAttestation(noPolyRawTx)(keyRing.generateAttestation(sender)))
    }
    forAll(gen)(tx =>
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

  property("Randomly generated ArbitTransfer Tx should be valid") {
    forAll(validArbitTransfer(keyRing, genesisState)) { tx =>
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
    val gen =
      validArbitTransfer(keyRing, genesisState, fee = 0)
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
    forAll(gen) { tx =>
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
    forAll(validAssetTransfer(keyRing, genesisState, minting = true)) { tx =>
      otherNetworks.foreach { netType =>
        tx.syntacticValidation(netType.netPrefix) should haveInvalidC[SyntacticValidationFailure](
          MintingMissingIssuersSignature
        )
      }
      tx.syntacticValidation(PrivateTestnet.netPrefix) should beValid[TransferTx](tx)
    }
  }

  property("Generated PolyTransfer Tx should be valid") {
    forAll(validPolyTransfer(keyRing, genesisState)) { tx =>
      tx.syntacticValidation should beValid[TransferTx](tx)
    }
  }

  property("Attempting to validate a PolyTransfer without valid signature should error") {
    // Create invalid PolyTransfer
    // send tx to state
    forAll(polyTransferGen) { tx =>
      tx.syntacticValidation should haveInvalidC[SyntacticValidationFailure](UnsatisfiedProposition)
    }
  }

  property("Transaction with negative fee should be invalid") {
    forAll(validPolyTransfer(keyRing, genesisState).map(tx => signTx(tx.copy(fee = -1)))) { tx =>
      tx.syntacticValidation should haveInvalidC[SyntacticValidationFailure](NegativeFeeFailure)
    }
  }

  property("Transaction with negative timestamp should be invalid") {
    forAll(validPolyTransfer(keyRing, genesisState).map(tx => signTx(tx.copy(timestamp = -1)))) { tx =>
      tx.syntacticValidation should haveInvalidC[SyntacticValidationFailure](InvalidTimestamp)
    }
  }

  property("Transaction with non-latin1 data should be invalid") {
    val data = "ˇ"
    forAll(validPolyTransfer(keyRing, genesisState).map(tx => signTx(tx.copy(data = Some(data))))) { tx =>
      tx.syntacticValidation should haveInvalidC[SyntacticValidationFailure](DataNotLatin1)
    }
  }

  property("Transaction with data length > 128 bytes should be invalid") {
    val data = Random.alphanumeric.take(512).mkString
    forAll(
      validPolyTransfer(keyRing, genesisState).map(tx => signTx(tx.copy(data = Some(data))))
    ) { tx =>
      tx.syntacticValidation should haveInvalidC[SyntacticValidationFailure](DataTooLong)
    }
  }

  private def signTx(tx: PolyTransfer[PublicKeyPropositionEd25519]): PolyTransfer[PublicKeyPropositionEd25519] =
    tx.copy(attestation = Transaction.updateAttestation(tx)(keyRing.generateAttestation(tx.from.map(_._1).toSet)))

  private def signTx(tx: ArbitTransfer[PublicKeyPropositionEd25519]): ArbitTransfer[PublicKeyPropositionEd25519] =
    tx.copy(attestation = Transaction.updateAttestation(tx)(keyRing.generateAttestation(tx.from.map(_._1).toSet)))

  private def signTx(tx: AssetTransfer[PublicKeyPropositionEd25519]): AssetTransfer[PublicKeyPropositionEd25519] =
    tx.copy(attestation = Transaction.updateAttestation(tx)(keyRing.generateAttestation(tx.from.map(_._1).toSet)))
}
