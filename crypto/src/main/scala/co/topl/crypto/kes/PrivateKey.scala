package co.topl.crypto.kes

import com.google.common.primitives.Longs

/**
  * AMS 2021:
  * This is a private key of the MMM construction with a specified time step offset
  * the offset is constrained by the public key and signatures include the offset,
  * The age of keys may be enforced in validation where the offset can be compared
  * to the time step of the signature since signatures include the offset
  */

class PrivateKey {

  var L:Tree[Array[Byte]] = Leaf(Array())
  var Si:Tree[Array[Byte]] = Leaf(Array())
  var sig:Array[Byte] = Array()
  var pki:Array[Byte] = Array()
  var rp:Array[Byte] = Array()
  var offset:Long = 0

  def update(kes:MMM, t:Long): Unit = {
    val updatedKey = kes.updateKeyFast((L,Si,sig,pki,rp),(t-offset).toInt)
    L = updatedKey._1
    Si = updatedKey._2
    sig = updatedKey._3
    pki = updatedKey._4
    rp = updatedKey._5
  }

  def sign(kes:MMM, m:Array[Byte]): (Array[Byte],Array[Byte],Array[Byte],Long,Array[Byte]) = {
    val out = kes.sign((L,Si,sig,pki,rp),m)
    (out._1,out._2,out._3,offset,kes.publicKey((L,Si,sig,pki,rp)))
  }

  def getPublic(kes:MMM, fch:Fch):Array[Byte] = {
    val pk_kes = kes.publicKey((L,Si,sig,pki,rp))
    fch.hash(Longs.toByteArray(offset)++pk_kes)
  }

  def time(kes:MMM):Long = {
    kes.getKeyTimeStep((L,Si,sig,pki,rp)) + offset
  }

}

object PrivateKey {

  def apply(kes:MMM, seed:Array[Byte], t:Long):PrivateKey = {
    val keyData = kes.generateKey(seed)
    val newKey = new PrivateKey
    newKey.L = keyData._1
    newKey.Si = keyData._2
    newKey.sig = keyData._3
    newKey.pki = keyData._4
    newKey.rp = keyData._5
    newKey.offset = t
    newKey
  }

  def apply(L:Tree[Array[Byte]],
            Si:Tree[Array[Byte]],
            sig:Array[Byte],
            pki:Array[Byte],
            rp:Array[Byte],
            offset:Long
           ):PrivateKey = {
    val newKey = new PrivateKey
    newKey.L = L
    newKey.Si = Si
    newKey.sig = sig
    newKey.pki = pki
    newKey.rp = rp
    newKey.offset = offset
    newKey
  }

}
