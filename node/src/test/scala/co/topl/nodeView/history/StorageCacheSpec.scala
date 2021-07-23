package co.topl.nodeView.history

import co.topl.consensus.consensusHelper.setProtocolMngr
import co.topl.modifier.block.Block
import co.topl.utils.{CommonGenerators, NodeGenerators}
import org.scalatest.DoNotDiscover
import org.scalatest.matchers.should.Matchers
import org.scalatest.propspec.AnyPropSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

@DoNotDiscover
class StorageCacheSpec
    extends AnyPropSpec
    with ScalaCheckPropertyChecks
    with Matchers
    with CommonGenerators
    with NodeGenerators {

  var history: History = _

  override def beforeAll(): Unit = {
    super.beforeAll()

    /* Initialize protocolMngr */
    setProtocolMngr(settings)

    history = generateHistory()
  }

  property("The genesis block is stored in cache") {
    val genesisBlockId = genesisBlock.parentId

    println(s"${genesisBlockId}")
    println(s"${history.storage.modifierById(genesisBlockId)}")

    history.storage.blockCache.getIfPresent(genesisBlockId) shouldEqual
    history.storage.modifierById(genesisBlockId).get.bytes
  }

  property("Cache should invalidate all entry when it's rolled back in storage") {
    val bestBlockIdKey = genesisBlock.parentId

    /* Append a new block, make sure it is updated in cache, then drop it */
    val fstBlock: Block = blockCurve25519Gen.sample.get.copy(parentId = history.bestBlockId)
    history = history.append(fstBlock).get._1

    history.storage.blockCache.getIfPresent(bestBlockIdKey) should not be null

    history = history.drop(fstBlock.id)

    /* After dropping the new block, the best block should be its parent block, the genesis block */
    /* The block Id for best block in cache should be invalidated */
    history.storage.blockCache.getIfPresent(bestBlockIdKey) shouldBe null

    /* Append multiple times */
    forAll(blockCurve25519Gen) { blockTemp =>
      val block: Block = blockTemp.copy(parentId = history.bestBlockId)

      history = history.append(block).get._1
    }

    /* Drop all new blocks */
    val height: Int = history.height.toInt
    (2 to height) foreach { _ =>
      history = history.drop(history.bestBlockId)
    }
    /* Same checks as above */
    history.storage.blockCache.getIfPresent(bestBlockIdKey) shouldBe null

    /* Maybe also check other entries like blockScore */
  }

  property("The new block updated is stored in cache") {

    forAll(blockCurve25519Gen) { blockTemp =>
      val block: Block = blockTemp.copy(parentId = history.bestBlockId)

      history = history.append(block).get._1
      history.storage.blockCache.getIfPresent(block.id.getIdBytes) shouldEqual
      history.storage.storage.get(block.id.getIdBytes)
    }
  }

  /* -----This test need to be done with smaller cacheSize or it will take very long to append enough entries----- */
  /* --------This test is commented out, change cacheSize in test.conf if we need to test this again------- */
  /*
  private val cacheSize: Int = settings.cacheSize

  property("Appending more entries than the maximum cache size will drop a portion of existing cache") {

    /* Append one block */
    val fstBlock: Block = BlockGen.sample.get.copy(parentId = history.bestBlockId)
    history = history.append(fstBlock).get._1

    history.storage.blockCache.getIfPresent((fstBlock.id.hashBytes)) should not be null

    /* Append a number of new blocks, so that we store more entries than the cache size limit */
    /* Assuming an average new block creates more than 50 entries */
    val numOfBlocks:Int = cacheSize / 50
    (1 to numOfBlocks) foreach { _ =>
      val oneBlock:Block = BlockGen.sample.get.copy(parentId = history.bestBlockId)
      history = history.append(oneBlock).get._1
    }

    history.storage.blockCache.getIfPresent((fstBlock.id.hashBytes)) shouldBe null
  }
   */

  property("blockLoader should correctly return a block from storage not found in cache") {
    val block: Block = blockCurve25519Gen.sample.get.copy(parentId = history.bestBlockId)
    val tempHistory = history.append(block).get._1

    tempHistory.storage.blockCache.invalidateAll()
    tempHistory.modifierById(block.id).get should not be None
  }
}
