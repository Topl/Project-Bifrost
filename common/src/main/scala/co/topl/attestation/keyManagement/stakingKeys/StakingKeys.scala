package co.topl.attestation.keyManagement.stakingKeys

import co.topl.crypto.signatures.eddsa.ECVRF25519
import co.topl.crypto.kes.KeyEvolvingSignature
import java.security.SecureRandom
import scala.util.Try

/**
  * AMS 2021:
  * Private and public key pairs for staking
  */

case class StakingKeys(ledger_id:Array[Byte], sk_vrf:Array[Byte], pk_vrf:Array[Byte], sk_kes:ForgingKey, pk_kes:Array[Byte]) {
  def publicKeys:(Array[Byte],Array[Byte],Array[Byte]) = (ledger_id,pk_vrf,pk_kes)
  def fullPublicAddress:Array[Byte] = ledger_id ++ pk_vrf ++ pk_kes
}

object StakingKeys {

  def createVrfKeypair(seed: Array[Byte],vrf:ECVRF25519): (Array[Byte],Array[Byte]) = {
    //This algorithm uses SHA-1 as the foundation of the PRNG. It computes the SHA-1 hash over a true-random seed value
    // concatenated with a 64-bit counter which is incremented by 1 for each operation.
    // From the 160-bit SHA-1 output, only 64 bits are used.
    val rnd: SecureRandom = SecureRandom.getInstance("SHA1PRNG")
    rnd.setSeed(seed)
    val sk = Array.fill[Byte](vrf.SECRET_KEY_SIZE){0x00}
    vrf.generatePrivateKey(rnd,sk)
    val pk = Array.fill[Byte](vrf.PUBLIC_KEY_SIZE){0x00}
    vrf.generatePublicKey(sk,0,pk,0)
    (sk,pk)
  }

  def apply(seed:Array[Byte], ledger_id:Array[Byte], vrf:ECVRF25519, kes:KeyEvolvingSignature, t:Long):Try[StakingKeys] = Try {
    require(seed.length==64,"Invalid seed entropy")
    val newVrfKeys = createVrfKeypair(seed.take(32),vrf)
    val newKesKey = ForgingKey(kes,seed.drop(32),t)
    StakingKeys(ledger_id,newVrfKeys._1,newVrfKeys._2,newKesKey,newKesKey.getPublicKey(kes))
  }
}
