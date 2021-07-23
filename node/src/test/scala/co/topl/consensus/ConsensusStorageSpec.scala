package co.topl.consensus

import co.topl.db.LDBVersionedStore
import co.topl.utils.CommonGenerators
import co.topl.crypto.hash.blake2b256
import co.topl.crypto.hash.implicits._
import com.google.common.primitives.Longs
import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.must.Matchers
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks

class ConsensusStorageSpec
    extends AnyFlatSpec
    with ScalaCheckDrivenPropertyChecks
    with Matchers
    with CommonGenerators
    with MockFactory {

  "totalStake" should "return default total stake after no updates with empty storage" in {
    forAll(positiveMediumIntGen) { defaultTotalStake =>
      val storage = new ConsensusStorage(None, defaultTotalStake)

      storage.totalStake shouldBe defaultTotalStake
    }
  }

  "totalStake" should "load total stake from storage on start" in {
    forAll(positiveInt128Gen) { storageTotalStake =>
      val store = mock[LDBVersionedStore]
      (store
        .get(_: Array[Byte]))
        .expects(*)
        .onCall { key: Array[Byte] =>
          if (key sameElements blake2b256.hash("totalStake".getBytes).bytes) {
            Some(storageTotalStake.toByteArray)
          } else Some(Longs.toByteArray(0))
        }
        .anyNumberOfTimes()

      val storage = new ConsensusStorage(Some(store), 100000)

      storage.totalStake shouldBe storageTotalStake
    }
  }

  "totalStake" should "return default total stake when storage does not contain value" in {
    forAll(positiveMediumIntGen) { defaultTotalStake =>
      val store = mock[LDBVersionedStore]
      (store
        .get(_: Array[Byte]))
        .expects(*)
        .returns(None)
        .anyNumberOfTimes()

      val storage = new ConsensusStorage(None, defaultTotalStake)

      storage.totalStake shouldBe defaultTotalStake
    }
  }

}
