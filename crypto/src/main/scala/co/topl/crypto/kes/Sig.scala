package co.topl.crypto.kes

import co.topl.crypto.signatures.eddsa.Ed25519

/**
  * AMS 2021:
  * Signing functionality used in MMM construction
  */

class Sig {
  val ec = new Ed25519
  def generatePublicKey(sk:Array[Byte],skOff:Int,pk:Array[Byte],pkOff:Int):Unit =
    ec.generatePublicKey(sk:Array[Byte],skOff:Int,pk:Array[Byte],pkOff: Int)

  def sign(sk:Array[Byte],skOff:Int,m:Array[Byte],mOff:Int,mLen:Int,sig:Array[Byte],sigOff:Int):Unit =
    ec.sign(sk:Array[Byte],skOff:Int,m:Array[Byte],mOff:Int,mLen:Int,sig:Array[Byte],sigOff:Int)

  def verify(sig:Array[Byte],sigOff:Int,pk:Array[Byte],pkOff:Int,m:Array[Byte],mOff:Int,mLen:Int):Boolean =
    ec.verify(sig:Array[Byte],sigOff:Int,pk:Array[Byte],pkOff:Int,m:Array[Byte],mOff:Int,mLen:Int)
}