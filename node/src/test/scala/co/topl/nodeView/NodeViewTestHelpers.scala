package co.topl.nodeView

import co.topl.attestation.{Address, PublicKeyPropositionCurve25519}
import co.topl.consensus.consensusHelper.setProtocolMngr
import co.topl.consensus.genesis.PrivateGenesis
import co.topl.consensus.{calcNewBaseDifficulty, consensusStorage, ConsensusStorage, LeaderElection, Rewards}
import co.topl.modifier.block.Block
import co.topl.modifier.box.ArbitBox
import co.topl.modifier.transaction.{ArbitTransfer, PolyTransfer}
import co.topl.nodeView.NodeViewTestHelpers.TestIn
import co.topl.nodeView.history.{History, InMemoryKeyValueStore, Storage}
import co.topl.nodeView.mempool.MemPool
import co.topl.nodeView.state.{State, TokenBoxRegistry}
import co.topl.utils.{InMemoryKeyFileTestHelper, TestSettings, TimeProvider}
import org.scalatest.{BeforeAndAfterAll, Suite}

trait NodeViewTestHelpers extends BeforeAndAfterAll {
  self: Suite with InMemoryKeyFileTestHelper with TestSettings =>

  protected var genesisBlock: Block = _

  protected def nextBlock(parent: Block, nodeView: NodeView): Block = {
    val forgerAddress = keyRingCurve25519.addresses.head
    val timestamp = parent.timestamp + 50000
    val arbitBox = LeaderElection
      .getEligibleBox(
        parent,
        keyRingCurve25519.addresses,
        timestamp,
        nodeView.state
      )
      .toOption
      .get
    nextBlock(parent, arbitBox, nodeView.history.getTimestampsFrom(parent, 3), forgerAddress)
  }

  protected def nextBlock(
    parent:             Block,
    arbitBox:           ArbitBox,
    previousTimestamps: Seq[TimeProvider.Time],
    forgerAddress:      Address
  ): Block = {
    val timestamp = parent.timestamp + 50000
    val rewards = {
      val base = Rewards(Nil, forgerAddress, parent.id, timestamp).get
      base.map {
        case b: PolyTransfer[_] =>
          b.copy(attestation = keyRingCurve25519.generateAttestation(forgerAddress)(b.messageToSign))
        case a: ArbitTransfer[_] =>
          a.copy(attestation = keyRingCurve25519.generateAttestation(forgerAddress)(a.messageToSign))
      }
    }
    Block
      .createAndSign(
        parent.id,
        timestamp = timestamp,
        txs = rewards,
        generatorBox = arbitBox,
        publicKey = keyRingCurve25519.lookupPublicKey(forgerAddress).get,
        height = parent.height + 1,
        difficulty = calcNewBaseDifficulty(
          parent.height + 1,
          parent.difficulty,
          previousTimestamps :+ timestamp
        ),
        version = 1: Byte
      )(keyRingCurve25519.signWithAddress(forgerAddress))
      .get
  }

  def genesisNodeView(): TestIn = {
    val historyStore = InMemoryKeyValueStore.empty()
    val stateStore = InMemoryKeyValueStore.empty()
    val tokenBoxStore = InMemoryKeyValueStore.empty()
    val nodeView = NodeView(
      History(settings, new Storage(historyStore, 32)),
      State(
        settings,
        stateStore,
        keys => Some(new TokenBoxRegistry(tokenBoxStore, keys)),
        None
      ),
      MemPool.empty()
    )

    nodeView.history.append(genesisBlock)
    nodeView.state.applyModifier(genesisBlock)
    TestIn(nodeView, historyStore, stateStore, tokenBoxStore)
  }

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    // A beforeAll step generates 3 keys.  We need 7 more to hit 10.
    keyRingCurve25519.generateNewKeyPairs(7)
    setProtocolMngr(settings)
    consensusStorage = ConsensusStorage(settings, appContext.networkType)
    genesisBlock = PrivateGenesis(keyRingCurve25519.addresses, settings).formNewBlock._1
  }
}

object NodeViewTestHelpers {

  case class TestIn(
    nodeView:      NodeView,
    historyStore:  InMemoryKeyValueStore,
    stateStore:    InMemoryKeyValueStore,
    tokenBoxStore: InMemoryKeyValueStore
  )
}
