package co.topl.crypto.hash

import co.topl.crypto.hash.digest.Digest32

class FastCryptographicHash {
  import digest.implicits._
  val blake2b256: Hash[Blake2b, Digest32] = new Blake2bHash[Digest32] {}
  def hash(input:Array[Byte]):Array[Byte] = {
    blake2b256.hash(input).value
  }
  def hash(input:String):Array[Byte] = hash(input.getBytes)
}