package co.topl.nodeView

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.ActorRef
import co.topl.attestation.{Address, PublicKeyPropositionCurve25519}
import co.topl.modifier.block.Block
import co.topl.modifier.box.{ArbitBox, SimpleValue}
import co.topl.modifier.transaction.PolyTransfer
import co.topl.nodeView.NodeViewHolder.ReceivableMessages
import co.topl.nodeView.NodeViewHolderSpec.TestInWithActor
import co.topl.nodeView.NodeViewTestHelpers.TestIn
import co.topl.utils.{InMemoryKeyFileTestHelper, TestSettings, TimeProvider}
import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.{Inspectors, OptionValues}

import scala.collection.AbstractIterator
import scala.concurrent.Future
import scala.concurrent.duration._

class NodeViewHolderSpec
    extends ScalaTestWithActorTestKit
    with AnyFlatSpecLike
    with TestSettings
    with InMemoryKeyFileTestHelper
    with NodeViewTestHelpers
    with MockFactory
    with OptionValues
    with Inspectors {

  behavior of "NodeViewHolder"

  it should "write, read, and remove transactions" in {

    implicit val timeProvider: TimeProvider = mock[TimeProvider]

    (() => timeProvider.time)
      .expects()
      .anyNumberOfTimes()
      .onCall(() => System.currentTimeMillis())

    genesisActorTest { testIn =>
      val addressA :: addressB :: _ = keyRingCurve25519.addresses.toList
      val polyTransfer = {
        val base = PolyTransfer
          .createRaw[PublicKeyPropositionCurve25519](
            testIn.testIn.nodeView.state,
            toReceive = IndexedSeq((addressA, SimpleValue(10))),
            sender = IndexedSeq(addressB),
            changeAddress = addressB,
            fee = 0,
            data = None
          )
          .get
        base.copy(attestation = keyRingCurve25519.generateAttestation(addressB)(base.messageToSign))
      }
      val transactions = List(polyTransfer)
      testIn.actorRef.tell(NodeViewHolder.ReceivableMessages.WriteTransactions(transactions))
      Thread.sleep(1.seconds.toMillis)
      testIn.testIn.nodeView.mempool.modifierById(transactions.head.id).value shouldBe transactions.head

      testIn.actorRef.tell(NodeViewHolder.ReceivableMessages.EliminateTransactions(transactions.map(_.id)))
      Thread.sleep(1.seconds.toMillis)
      testIn.testIn.nodeView.mempool.modifierById(transactions.head.id) shouldBe None
    }
  }

  it should "write a single viable block" in {

    implicit val timeProvider: TimeProvider = mock[TimeProvider]

    (() => timeProvider.time)
      .expects()
      .anyNumberOfTimes()
      .onCall(() => System.currentTimeMillis())

    genesisActorTest { testIn =>
      val next = nextBlock(testIn.testIn.nodeView.history.bestBlock, testIn.testIn.nodeView)
      testIn.actorRef.tell(ReceivableMessages.WriteBlocks(List(next)))

      Thread.sleep(1.seconds.toMillis)
      testIn.testIn.nodeView.history.modifierById(next.id).value shouldBe next
    }
  }

  it should "write multiple viable blocks" in {

    implicit val timeProvider: TimeProvider = mock[TimeProvider]

    (() => timeProvider.time)
      .expects()
      .anyNumberOfTimes()
      .onCall(() => System.currentTimeMillis())

    genesisActorTest { testIn =>
      val nextBlocks = generateBlocks(List(genesisBlock), keyRingCurve25519.addresses.head).take(3).toList
      testIn.actorRef.tell(ReceivableMessages.WriteBlocks(nextBlocks))
      Thread.sleep(2.seconds.toMillis)
      forAll(nextBlocks) { block =>
        testIn.testIn.nodeView.history.modifierById(block.id).value
      }
    }
  }

  it should "cache non-viable blocks" in {

    implicit val timeProvider: TimeProvider = mock[TimeProvider]

    (() => timeProvider.time)
      .expects()
      .anyNumberOfTimes()
      .onCall(() => System.currentTimeMillis())

    genesisActorTest { testIn =>
      val nextBlocks = generateBlocks(List(genesisBlock), keyRingCurve25519.addresses.head).take(3).toList
      // Insert blocks 2 and 3, but not block 1
      testIn.actorRef.tell(ReceivableMessages.WriteBlocks(nextBlocks.takeRight(2)))
      Thread.sleep(2.seconds.toMillis)
      // Because block 1 is not available yet, blocks 2 and 3 should not be in history yet either
      forAll(nextBlocks) { block =>
        testIn.testIn.nodeView.history.modifierById(block.id) shouldBe None
      }
      // Now write block 1
      testIn.actorRef.tell(ReceivableMessages.WriteBlocks(nextBlocks.take(1)))
      Thread.sleep(2.seconds.toMillis)
      // And verify that block 1 was written, as well as blocks 2 and 3 from the previous attempt
      forAll(nextBlocks) { block =>
        testIn.testIn.nodeView.history.modifierById(block.id).value
      }
    }
  }

  private def genesisActorTest(test: TestInWithActor => Unit)(implicit timeProvider: TimeProvider): Unit = {
    val testIn = genesisNodeView()
    val ref = spawn(NodeViewHolder(settings, () => Future.successful(testIn.nodeView)))
    val testInWithActor = TestInWithActor(testIn, ref)
    test(testInWithActor)
    testKit.stop(ref)
  }

  private def generateBlocks(previousBlocks: List[Block], forgerAddress: Address): Iterator[Block] =
    new AbstractIterator[Block] {

      // Because the reward fee is 0, the genesis arbit box is never destroyed during forging, so we can re-use it
      private val arbitBox =
        previousBlocks.last.transactions
          .flatMap(_.newBoxes)
          .collectFirst { case a: ArbitBox if a.evidence == forgerAddress.evidence => a }
          .value
      private var previous3Blocks: List[Block] = previousBlocks.takeRight(3)

      override def hasNext: Boolean = true

      override def next(): Block =
        if (previous3Blocks.isEmpty) {
          previous3Blocks = List(genesisBlock)
          genesisBlock
        } else {
          val newBlock = nextBlock(
            previous3Blocks.last,
            arbitBox,
            previous3Blocks.map(_.timestamp),
            forgerAddress
          )
          previous3Blocks = (previous3Blocks :+ newBlock).takeRight(3)
          newBlock
        }
    }
}

object NodeViewHolderSpec {
  case class TestInWithActor(testIn: TestIn, actorRef: ActorRef[NodeViewHolder.ReceivableMessage])
}
