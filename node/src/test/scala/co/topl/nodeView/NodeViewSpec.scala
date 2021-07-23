package co.topl.nodeView

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import co.topl.modifier.block.Block
import co.topl.modifier.transaction.Transaction
import co.topl.nodeView.NodeViewTestHelpers.TestIn
import co.topl.nodeView.mempool.MemPool
import co.topl.utils._
import org.scalamock.scalatest.MockFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.propspec.AnyPropSpecLike
import org.scalatest.{BeforeAndAfterAll, EitherValues, OptionValues}
import org.scalatestplus.scalacheck.{ScalaCheckDrivenPropertyChecks, ScalaCheckPropertyChecks}

class NodeViewSpec
    extends ScalaTestWithActorTestKit
    with AnyPropSpecLike
    with CommonGenerators
    with ScalaCheckPropertyChecks
    with ScalaCheckDrivenPropertyChecks
    with Matchers
    with TestSettings
    with BeforeAndAfterAll
    with NodeViewTestHelpers
    with InMemoryKeyFileTestHelper
    with GenesisBlockGenerators
    with OptionValues
    with EitherValues
    with MockFactory {

  property("Rewards transactions are removed from transactions extracted from a block being rolled back") {
    withGenesisNodeView { testIn =>
      forAll(blockCurve25519Gen) { block =>
        implicit val timeProvider: TimeProvider = mock[TimeProvider]
        (() => timeProvider.time)
          .expects()
          .once()
          .returning(10)
        val polyReward = sampleUntilNonEmpty(polyTransferGen)
        val arbitReward = sampleUntilNonEmpty(arbitTransferGen)
        val rewardBlock = block.copy(transactions = Seq(arbitReward, polyReward), parentId = genesisBlock.id)

        val memPool =
          testIn.nodeView.updateMemPool(List(rewardBlock), Nil, MemPool.empty())

        memPool.contains(polyReward) shouldBe false
        memPool.contains(arbitReward) shouldBe false
      }
    }
  }

  property("NodeView can include syntactically valid transactions") {
    val addressA :: _ = keyRingCurve25519.addresses.toList
    forAll(signedPolyTransferGen(positiveMediumIntGen.map(nonce => IndexedSeq((addressA, nonce))), keyRingCurve25519)) {
      tx =>
        implicit val timeProvider: TimeProvider = mock[TimeProvider]
        (() => timeProvider.time)
          .expects()
          .once()
          .returning(10)
        withGenesisNodeView { testIn =>
          val (events, updatedNodeView) = testIn.nodeView.withTransaction(tx).run
          updatedNodeView.mempool.contains(tx.id) shouldBe true
          events shouldBe List(NodeViewHolder.Events.SuccessfulTransaction[Transaction.TX](tx))
        }
    }
  }

  property("NodeView should reject syntactically invalid transactions") {
    implicit val timeProvider: TimeProvider = mock[TimeProvider]
    (() => timeProvider.time)
      .expects()
      .never()
    // polyTransferGen generates invalid attestations, which are considered syntactically invalid
    forAll(polyTransferGen) { tx =>
      withGenesisNodeView { testIn =>
        val (events, updatedNodeView) = testIn.nodeView.withTransaction(tx).run
        updatedNodeView.mempool.contains(tx.id) shouldBe false

        val List(failedTransaction: NodeViewHolder.Events.FailedTransaction) = events

        failedTransaction.transactionId shouldBe tx.id
      }
    }
  }

  property("NodeView can remove previously added transactions") {
    implicit val timeProvider: TimeProvider = mock[TimeProvider]
    (() => timeProvider.time)
      .expects()
      .anyNumberOfTimes()
      .returning(10)
    val addressA :: _ = keyRingCurve25519.addresses.toList
    forAll(signedPolyTransferGen(positiveMediumIntGen.map(nonce => IndexedSeq((addressA, nonce))), keyRingCurve25519)) {
      tx =>
        withGenesisNodeView { testIn =>
          val (_, updatedNodeView1) = testIn.nodeView.withTransaction(tx).run
          updatedNodeView1.mempool.contains(tx.id) shouldBe true

          val (events, updatedNodeView2) =
            updatedNodeView1.withoutTransactions(List(tx.id)).run
          updatedNodeView2.mempool.contains(tx.id) shouldBe false
          import org.scalatest.Inspectors._
          forExactly(1, events) { e =>
            e shouldBe NodeViewHolder.Events.ChangedMempool
          }
          forExactly(1, events) { e =>
            e shouldBe a[NodeViewHolder.Events.FailedTransaction]
            e.asInstanceOf[NodeViewHolder.Events.FailedTransaction].transactionId shouldBe tx.id
          }
        }
    }
  }

  property("NodeView can skip adding an existing block to history") {
    implicit val timeProvider: TimeProvider = mock[TimeProvider]
    (() => timeProvider.time)
      .expects()
      .never()
    withGenesisNodeView { testIn =>
      val initialHistoryStoreState = testIn.historyStore.state
      val (events, _) =
        testIn.nodeView.withBlock(genesisBlock).run

      testIn.historyStore.state shouldBe initialHistoryStoreState
      events shouldBe Nil
    }
  }

  property("NodeView can add a new block to history") {
    implicit val timeProvider: TimeProvider = mock[TimeProvider]

    (() => timeProvider.time)
      .expects()
      .once()
      .returning(Long.MaxValue)

    withGenesisNodeView { testIn =>
      val block = nextBlock(genesisBlock, testIn.nodeView)

      val (events, updatedNodeView) =
        testIn.nodeView.withBlock(block).run

      events shouldBe List(
        NodeViewHolder.Events.StartingPersistentModifierApplication(block),
        NodeViewHolder.Events.SyntacticallySuccessfulModifier(block),
        NodeViewHolder.Events.NewOpenSurface(List(genesisBlock.id)),
        NodeViewHolder.Events.ChangedHistory,
        NodeViewHolder.Events.SemanticallySuccessfulModifier(block),
        NodeViewHolder.Events.ChangedState,
        NodeViewHolder.Events.ChangedMempool
      )
      updatedNodeView.history.modifierById(block.id).value shouldBe block
    }
  }

  property("NodeView should refuse an invalid Block") {
    implicit val timeProvider: TimeProvider = mock[TimeProvider]

    (() => timeProvider.time)
      .expects()
      .never()

    withGenesisNodeView { testIn =>
      val block = nextBlock(genesisBlock, testIn.nodeView).copy(difficulty = -1)

      val (events, updatedNodeView) =
        testIn.nodeView.withBlock(block).run

      events should have size 2

      events(0) shouldBe NodeViewHolder.Events.StartingPersistentModifierApplication(block)
      events(1).asInstanceOf[NodeViewHolder.Events.SyntacticallyFailedModification[Block]].modifier shouldBe block
      updatedNodeView.history.modifierById(block.id) shouldBe None
    }
  }

  private def withGenesisNodeView(test: TestIn => Unit): Unit =
    test(genesisNodeView())

}
