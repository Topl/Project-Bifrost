package co.topl.nodeView.state

import co.topl.attestation.Address
import org.scalatest.BeforeAndAfterAll
import org.scalatest.OptionValues.convertOptionToValuable
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks

class TokenBoxRegistrySpec extends MockState with ScalaCheckDrivenPropertyChecks with Matchers with BeforeAndAfterAll {

  var state: State = _

  override def beforeAll(): Unit = {
    super.beforeAll()

    state = createState()
  }

  property("Token boxes should be inserted into the registry") {
    forAll(tokenBoxesGen) { tokens =>
      val keys = tokens.groupBy(_.evidence)
      directlyAddTBRStorage(modifierIdGen.sample.get, tokens, state)
      keys.foreach { key =>
        val ids = key._2.map(_.id)
        state.registryLookup(Address(key._1)).value shouldEqual ids
      }
    }
  }

  property("Rolling back should remove tokens from registry") {
    forAll(tokenBoxesGen) { tokens =>
      val tbr = state.tbrOpt.get
      val version = modifierIdGen.sample.get
      val keys = tokens.groupBy(_.evidence).map(k => Address(k._1) -> k._2)
      val update = keys.map(k => k._1 -> k._2.map(_.nonce))

      val newTbr = tbr.update(version, Map(), update).get
      newTbr.rollbackTo(state.version)

      keys.foreach { key =>
        state.registryLookup(key._1) shouldBe None
      }
    }
  }

  override def afterAll(): Unit =
    state.close()
}
