package co.topl.crypto.kes

/**
  * AMS 2021:
  * Key evolving scheme instantiated with Ed25519 and b2b256 fast cryptographic hash using MMM construction
  */

class KeyEvolvingSignature extends MMM {
  override val fch: Fch = new Fch
  override val sig: Sig = new Sig
  override val seedBytes: Int = 32
  override val pkBytes: Int = 32
  override val skBytes: Int = 32
  override val sigBytes: Int = 64
  override val hashBytes: Int = 32
}
