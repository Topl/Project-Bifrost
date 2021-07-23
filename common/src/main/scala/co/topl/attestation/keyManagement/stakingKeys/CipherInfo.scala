package co.topl.attestation.keyManagement.stakingKeys

case class CipherInfo(pubKey:Array[Byte], cipherText:Array[Byte], mac:Array[Byte], salt:Array[Byte], iv:Array[Byte])
