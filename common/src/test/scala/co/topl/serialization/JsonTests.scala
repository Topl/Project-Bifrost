package co.topl.serialization

import co.topl.attestation._
import co.topl.attestation.keyManagement.{
  KeyfileCurve25519,
  KeyfileCurve25519Companion,
  KeyfileEd25519,
  KeyfileEd25519Companion
}
import co.topl.modifier.ModifierId
import co.topl.modifier.block.{Block, BlockBody, BlockHeader, BloomFilter}
import co.topl.modifier.box._
import co.topl.modifier.transaction.Transaction
import co.topl.utils.CommonGenerators
import io.circe.syntax.EncoderOps
import org.scalacheck.Gen
import org.scalatest.matchers.must.Matchers
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper
import org.scalatest.propspec.AnyPropSpec
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks

class JsonTests extends AnyPropSpec with Matchers with ScalaCheckDrivenPropertyChecks with CommonGenerators {

  property("PublicKeyProposition json") {
    forAll(propositionCurve25519Gen)(prop => prop.asJson.as[PublicKeyPropositionCurve25519] shouldEqual Right(prop))
    forAll(propositionEd25519Gen)(prop => prop.asJson.as[PublicKeyPropositionEd25519] shouldEqual Right(prop))
  }

  property("ThresholdProposition json") {
    forAll(oneOfNPropositionCurve25519Gen) { case (_, prop) =>
      prop.asJson.as[ThresholdPropositionCurve25519] shouldEqual Right(prop)
    }
  }

  property("Signature json") {
    forAll(signatureCurve25519Gen)(sig => sig.asJson.as[SignatureCurve25519] shouldEqual Right(sig))
    forAll(signatureEd25519Gen)(sig => sig.asJson.as[SignatureEd25519] shouldEqual Right(sig))
  }

  property("Keyfile json") {
    forAll(keyCurve25519Gen) { key =>
      val keyfile = KeyfileCurve25519Companion.encryptSecret(key._1, "test")
      keyfile.asJson.as[KeyfileCurve25519] match {
        case Right(kf) =>
          kf.address shouldEqual keyfile.address
          kf.cipherText sameElements keyfile.cipherText
          kf.mac sameElements keyfile.mac
          kf.salt sameElements keyfile.salt
          kf.iv sameElements keyfile.iv
        case Left(e) => e
      }
    }

    forAll(keyEd25519Gen) { key =>
      val keyfile = KeyfileEd25519Companion.encryptSecret(key._1, "test")
      keyfile.asJson.as[KeyfileEd25519] match {
        case Right(kf) =>
          kf.address shouldEqual keyfile.address
          kf.cipherText sameElements keyfile.cipherText
          kf.mac sameElements keyfile.mac
          kf.salt sameElements keyfile.salt
          kf.iv sameElements keyfile.iv
        case Left(e) => e
      }
    }
  }

  property("Address json") {
    forAll(addressGen)(address => address.asJson.as[Address] shouldEqual Right(address))
  }

  property("Evidence json") {
    forAll(evidenceGen)(evidence => evidence.asJson.as[Evidence] shouldEqual Right(evidence))
  }

  property("ModifierId json") {
    forAll(modifierIdGen)(id => id.asJson.as[ModifierId] shouldEqual Right(id))
  }

  property("AssetCode json") {
    forAll(assetCodeGen)(code => code.asJson.as[AssetCode] shouldEqual Right(code))
  }

  property("SecurityRoot json") {
    forAll(securityRootGen)(root => root.asJson.as[SecurityRoot] shouldEqual Right(root))
  }

  property("TokenValueHolder json") {
    forAll(Gen.oneOf(simpleValueGen, assetValueGen)) { value: TokenValueHolder =>
      value.asJson.as[TokenValueHolder] shouldEqual Right(value)
    }
  }

  property("PolyBox json") {
    forAll(polyBoxGen)(box => box.asJson.as[PolyBox] shouldEqual Right(box))
  }

  property("ArbitBox json") {
    forAll(arbitBoxGen)(box => box.asJson.as[ArbitBox] shouldEqual Right(box))
  }

  property("AssetBox json") {
    forAll(assetBoxGen)(box => box.asJson.as[AssetBox] shouldEqual Right(box))
  }

  property("ProgramId json") {
    forAll(programIdGen)(id => id.asJson.as[ProgramId] shouldEqual Right(id))
  }

  property("StateBox json") {
    forAll(stateBoxGen)(box => box.asJson.as[StateBox] shouldEqual Right(box))
  }

  property("CodeBox json") {
    forAll(codeBoxGen)(box => box.asJson.as[CodeBox] shouldEqual Right(box))
  }

  property("ExecutionBox json") {
    forAll(executionBoxGen)(box => box.asJson.as[ExecutionBox] shouldEqual Right(box))
  }

  property("Transaction json") {
    forAll(transferGen) { tx: Transaction.TX =>
      tx.asJson.as[Transaction.TX] shouldEqual Right(tx)
    }
  }

  property("FullBlock json") {
    forAll(blockCurve25519Gen)(block => block.asJson.as[Block] shouldEqual Right(block))
  }

  property("BlockHeader json") {
    forAll(blockCurve25519Gen) { block =>
      val header = block.toComponents._1
      header.asJson.as[BlockHeader] match {
        case Right(value) =>
          value.id shouldEqual header.id
          value.parentId shouldEqual header.parentId
          value.timestamp shouldEqual header.timestamp
          value.generatorBox shouldEqual header.generatorBox
          value.publicKey shouldEqual header.publicKey
          value.signature shouldEqual header.signature
          value.height shouldEqual header.height
          value.difficulty shouldEqual header.difficulty
          value.txRoot shouldEqual header.txRoot
          value.bloomFilter shouldEqual header.bloomFilter
          value.version shouldEqual header.version
        case Left(e) => e
      }

    }
  }

  property("BlockBody json") {
    forAll(blockCurve25519Gen) { block =>
      val body = block.toComponents._2
      body.asJson.as[BlockBody] shouldEqual Right(body)
    }
  }

  property("BloomFilter json") {
    forAll(blockCurve25519Gen)(block => block.bloomFilter.asJson.as[BloomFilter] shouldEqual Right(block.bloomFilter))
  }
}
