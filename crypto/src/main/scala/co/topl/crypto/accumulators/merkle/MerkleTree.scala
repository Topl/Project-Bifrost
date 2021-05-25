package co.topl.crypto.accumulators.merkle

import cats.implicits._
import co.topl.crypto.accumulators.{LeafData, Side}
import co.topl.crypto.hash.digest.Digest
import co.topl.crypto.hash.digest.implicits._
import co.topl.crypto.hash.Hash

import scala.annotation.tailrec
import scala.collection.mutable

/* Forked from https://github.com/input-output-hk/scrypto */

case class MerkleTree[H, D: Digest](
  topNode:           Option[Node[D]],
  elementsHashIndex: Map[mutable.WrappedArray.ofByte, Int]
)(implicit h:        Hash[H, D]) {

  private lazy val emptyRootHash: D = Digest[D].empty

  lazy val rootHash: D =
    topNode
      .map(_.hash)
      .getOrElse(emptyRootHash)

  lazy val length: Int = elementsHashIndex.size

  def proofByElement(element: Leaf[H, D]): Option[MerkleProof[H, D]] = proofByElementHash(element.hash)

  def proofByElementHash(hash: D): Option[MerkleProof[H, D]] =
    elementsHashIndex.get(new mutable.WrappedArray.ofByte(hash.bytes)).flatMap(i => proofByIndex(i))

  def proofByIndex(index: Int): Option[MerkleProof[H, D]] = if (index >= 0 && index < length) {

    @tailrec
    def loop(
      node:      Option[Node[D]],
      i:         Int,
      curLength: Int,
      acc:       Seq[(Option[D], Side)]
    ): Option[(Leaf[H, D], Seq[(Option[D], Side)])] =
      node match {
        case Some(n: InternalNode[H, D]) if i < curLength / 2 =>
          n.right match {
            case Some(right) => loop(Some(n.left), i, curLength / 2, (Some(right.hash), MerkleProof.LeftSide) +: acc)
            case None        => loop(Some(n.left), i, curLength / 2, (None, MerkleProof.LeftSide) +: acc)
          }
        case Some(n: InternalNode[H, D]) if i < curLength =>
          loop(n.right, i - curLength / 2, curLength / 2, (Some(n.left.hash), MerkleProof.RightSide) +: acc)
        case Some(n: Leaf[H, D]) =>
          Some((n, acc))
        case _ =>
          None
      }

    val leafWithProofs = loop(topNode, index, lengthWithEmptyLeafs, Seq())
    leafWithProofs.map(lp => MerkleProof(lp._1.data, lp._2))
  } else {
    None
  }

  lazy val lengthWithEmptyLeafs: Int = {
    def log2(x: Double): Double = math.log(x) / math.log(2)

    Math.max(math.pow(2, math.ceil(log2(length))).toInt, 2)
  }

}

object MerkleTree {
  val LeafPrefix: Byte = 0: Byte
  val InternalNodePrefix: Byte = 1: Byte

  /**
   * Construct Merkle tree from leafs
   *
   * @param payload       - sequence of leafs data
   * @return MerkleTree constructed from current leafs with defined empty node and hash function
   */
  def apply[H, D: Digest](payload: Seq[LeafData])(implicit h: Hash[H, D]): MerkleTree[H, D] = {
    val leafs = payload.map(d => Leaf[H, D](d))

    val elementsToIndex =
      leafs.zipWithIndex
        .foldLeft(Map[mutable.WrappedArray.ofByte, Int]()) { case (elements, (leaf, leafIndex)) =>
          elements + (new mutable.WrappedArray.ofByte(leaf.hash.bytes) -> leafIndex)
        }

    val topNode = calcTopNode[H, D](leafs)

    MerkleTree[H, D](topNode, elementsToIndex)
  }

  @tailrec
  def calcTopNode[H, D: Digest](nodes: Seq[Node[D]])(implicit h: Hash[H, D]): Option[Node[D]] =
    if (nodes.isEmpty) {
      None
    } else {
      val nextNodes = nodes
        .grouped(2)
        .map(lr => InternalNode[H, D](lr.head, if (lr.lengthCompare(2) == 0) Some(lr.last) else None))
        .toSeq
      if (nextNodes.lengthCompare(1) == 0) Some(nextNodes.head) else calcTopNode(nextNodes)
    }

}
