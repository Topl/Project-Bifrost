package co.topl.consensus

import co.topl.consensus.consensusHelper.setProtocolMngr
import co.topl.modifier.ModifierId
import co.topl.modifier.block.Block
import co.topl.nodeView.history.History
import co.topl.nodeView.state.{MockState, State}
import co.topl.utils.{DiskKeyFileTestHelper, GenesisBlockGenerators, NodeGenerators}

class BlockVersionTests extends MockState with NodeGenerators with DiskKeyFileTestHelper with GenesisBlockGenerators {

  /** Generate a history and state with a genesis block of the oldest version in the configuration */
  var fstVersion: Byte = _
  var genesisBlockOldestVersion: Block = _
  var history: History = _
  var state: State = _

  override def beforeAll(): Unit = {
    super.beforeAll()

    /** Initialize protocolMngr */
    setProtocolMngr(settings)

    fstVersion = protocolMngr.applicable.map(_.blockVersion).min.get
    genesisBlockOldestVersion = genesisBlockGen(keyRingCurve25519).sample.get.copy(version = fstVersion)
    history = generateHistory(genesisBlockOldestVersion)
    state = createState(genesisBlockOldestVersion)
  }

  property(
    "Applying different blocks in different versions according to the test.conf should yield blocks " +
    "with intended versions"
  ) {

    /**
     * Apply enough blocks to history and state so there will be blocks of all possible versions
     * Don't make a test.conf that has a version with a really large startBlock
     */
    val blocksToAppend: Int = protocolMngr.applicable.maxBy(_.startBlock).startBlock.toInt
    val blocksCount: Int = blocksToAppend + 1 // with genesis block

    for (_ <- 1 to blocksToAppend) {
      val oneBlock: Block = blockCurve25519Gen.sample.get.copy(
        parentId = history.bestBlockId,
        transactions = Seq(),
        version = blockVersion(history.height + 1)
      )
      history = history.append(oneBlock).get._1
      state = state.applyModifier(oneBlock).get
    }

    var currentId: ModifierId = history.storage.bestBlockId
    for (height <- blocksCount to 1 by -1) {
      val currentBlock: Block = history.storage.modifierById(currentId).get
      currentId = currentBlock.parentId
      // log.debug(s"${Console.MAGENTA}${currentBlock.json}${Console.RESET}")
      val versionConf = protocolMngr
        .current(height)
        .getOrElse(throw new Error("Unable to find applicable protocol rules"))
        .blockVersion
        .get
      versionConf == currentBlock.version shouldBe true
    }
  }

  property("Applying genesis block to history/state with different available versions should be successful") {
    for (version <- protocolMngr.applicable.map(_.blockVersion.get)) {
      val genesisBlockWithVersion: Block = genesisBlockGen(keyRingCurve25519).sample.get.copy(version = version)
      history = generateHistory(genesisBlockWithVersion)
      history.modifierById(genesisBlockWithVersion.id).isDefined shouldBe true
      state = createState(genesisBlockWithVersion)
      state.version == genesisBlockWithVersion.id shouldBe true
    }
  }
}
