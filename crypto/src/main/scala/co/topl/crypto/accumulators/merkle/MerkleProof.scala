package co.topl.crypto.accumulators.merkle

import cats.implicits._
import co.topl.crypto.accumulators.{LeafData, Side}
import co.topl.crypto.hash.Hash
import co.topl.crypto.hash.digest.Digest
import co.topl.crypto.hash.digest.implicits._

/* Forked from https://github.com/input-output-hk/scrypto */

/**
 * Proof is given leaf data, leaf hash sibling and also siblings for parent nodes. Using this data, it is possible to
 * compute nodes on the path to root hash, and the hash itself. The picture of a proof given below. In the picture,
 * "^^" is leaf data(to compute leaf hash from), "=" values are to be computed, "*" values are to be stored.
 *
 * ........= Root
 * ..... /  \
 * .... *   =
 * ....... / \
 * ...... *   =
 * ......... /.\
 * .........*   =
 * ............ ^^
 *
 * @param leafData - leaf data bytes
 * @param levels - levels in proof, bottom up, each level is about stored value and position of computed element
 *               (whether it is left or right to stored value)
 */
case class MerkleProof[H, D: Digest](leafData: LeafData, levels: Seq[(Option[D], Side)])(implicit
  hashFunc:                                    Hash[H, D]
) {

  def valid(expectedRootHash: D): Boolean = {
    val leafHash = hashFunc.hash(MerkleTree.LeafPrefix, leafData.value)

    val result = levels.foldLeft(leafHash) {
      case (prevHash, (hash, side)) =>
        val nodeBytes =
          hash.map { h =>
            if (side == MerkleProof.LeftSide) prevHash.bytes ++ h.bytes
            else h.bytes ++ prevHash.bytes
          } getOrElse prevHash.bytes

        hashFunc.hash(MerkleTree.InternalNodePrefix, nodeBytes)

      case (invalidHash, _) => invalidHash
    }

    result === expectedRootHash
  }
}

object MerkleProof {

  val LeftSide: Side = Side(0.toByte)
  val RightSide: Side = Side(1.toByte)
}
