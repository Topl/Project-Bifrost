package co.topl.crypto.kes

import co.topl.crypto.hash.{Blake2b, Blake2bHash, Hash, digest}
import co.topl.crypto.hash.digest.Digest32

/**
  * AMS 2021:
  * Fast Cryptographic Hash for use with MMM
  */

class Fch {
  import digest.implicits._
  val blake2b256: Hash[Blake2b, Digest32] = new Blake2bHash[Digest32] {}
  def hash(input:Array[Byte]):Array[Byte] = {
    blake2b256.hash(input).value
  }
}