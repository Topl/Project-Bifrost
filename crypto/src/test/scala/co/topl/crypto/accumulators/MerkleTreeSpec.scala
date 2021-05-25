package co.topl.crypto.accumulators

import co.topl.crypto.accumulators.merkle.{Leaf, MerkleTree}
import co.topl.crypto.hash.digest.{Digest, Digest32}
import co.topl.crypto.hash.implicits._
import co.topl.crypto.hash.{Blake2b, Hash}
import co.topl.crypto.utils.Generators._
import co.topl.crypto.utils.randomBytes
import org.scalatest.matchers.should.Matchers
import org.scalatest.propspec.AnyPropSpec
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks

class MerkleTreeSpec extends AnyPropSpec with ScalaCheckDrivenPropertyChecks with Matchers {

  type HashScheme = Blake2b
  type HashDigest = Digest32
  val hf = Hash[HashScheme, HashDigest]
  def bytesOf(h: HashDigest): Array[Byte] = Digest[HashDigest].bytes(h)

  private val leafSize = 32

  property("Proof generation by element") {
    forAll(smallInt) { N: Int =>
      whenever(N > 0) {
        val d = (0 until N).map(_ => LeafData(randomBytes(leafSize)))
        val leafs = d.map(data => Leaf[HashScheme, HashDigest](data))
        val tree = MerkleTree[HashScheme, HashDigest](d)
        val treeRootHash = tree.rootHash
        leafs.foreach { l =>
          val proof = tree.proofByElement(l).get
          proof.leafData.value.sameElements(l.data.value) shouldBe true
          proof.valid(treeRootHash) shouldBe true
        }
      }
    }
  }

  property("Proof generation by index") {
    forAll(smallInt) { N: Int =>
      whenever(N > 0) {
        val d = (0 until N).map(_ => LeafData(randomBytes(leafSize)))
        val tree = MerkleTree[HashScheme, HashDigest](d)

        (0 until N).foreach { i =>
          tree.proofByIndex(i).get.leafData.value shouldEqual d(i).value
          tree
            .proofByIndex(i)
            .get
            .valid(tree.rootHash) shouldBe true
        }
        (N until N + 100).foreach { i =>
          tree.proofByIndex(i).isEmpty shouldBe true
        }
        (-(N + 100) until 0).foreach { i =>
          tree.proofByIndex(i).isEmpty shouldBe true
        }
      }
    }
  }

  property("Tree creation from 0 elements") {
    val tree = MerkleTree.apply[HashScheme, HashDigest](Seq.empty)
    tree.rootHash.value shouldEqual Array.fill(Digest[HashDigest].size)(0: Byte)
  }

  property("Tree creation from 1 element") {
    forAll { d: Array[Byte] =>
      whenever(d.length > 0) {
        val tree = MerkleTree.apply[HashScheme, HashDigest](Seq(LeafData(d)))

        bytesOf(tree.rootHash) shouldEqual
        bytesOf(
          hf.hash(
            MerkleTree.InternalNodePrefix,
            bytesOf(hf.hash(MerkleTree.LeafPrefix, d))
          )
        )
      }
    }
  }

  property("Tree creation from 5 elements") {
    forAll { d: Array[Byte] =>
      whenever(d.length > 0) {
        val leafs: Seq[LeafData] = (0 until 5).map(_ => LeafData(d))
        val tree = MerkleTree[HashScheme, HashDigest](leafs)
        val h0x = hf.hash(MerkleTree.LeafPrefix, d)
        val h10 = hf.hash(MerkleTree.InternalNodePrefix, bytesOf(h0x), bytesOf(h0x))
        val h11 = h10
        val h12 = hf.hash(MerkleTree.InternalNodePrefix, bytesOf(h0x))
        val h20 = hf.hash(MerkleTree.InternalNodePrefix, bytesOf(h10), bytesOf(h11))
        val h21 = hf.hash(MerkleTree.InternalNodePrefix, bytesOf(h12))
        val h30 = hf.hash(MerkleTree.InternalNodePrefix, bytesOf(h20), bytesOf(h21))
        bytesOf(h30) shouldEqual bytesOf(tree.rootHash)
      }
    }
  }

  property("Tree creation from 2 element") {
    forAll { (d1: Array[Byte], d2: Array[Byte]) =>
      val tree = MerkleTree.apply[HashScheme, HashDigest](Seq(LeafData(d1), LeafData(d2)))
      tree.rootHash shouldEqual hf
        .hash(
          MerkleTree.InternalNodePrefix,
          bytesOf(hf.hash(MerkleTree.LeafPrefix, d1)),
          bytesOf(hf.hash(MerkleTree.LeafPrefix, d2))
        )
    }
  }

  property("Tree creation from a lot of elements") {
    forAll { d: Seq[Array[Byte]] =>
      whenever(d.nonEmpty) {
        val tree = MerkleTree.apply[HashScheme, HashDigest](d.map(a => LeafData(a)))
        tree.rootHash
      }
    }
  }
}
