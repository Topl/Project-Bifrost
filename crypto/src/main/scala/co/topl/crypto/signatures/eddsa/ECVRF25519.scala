package co.topl.crypto.signatures.eddsa

import java.security.SecureRandom

/**
  * AMS 2021:
  * ECVRF-ED25519-SHA512-TAI
  * Elliptic curve Verifiable Random Function based on EdDSA
  * https://tools.ietf.org/html/draft-irtf-cfrg-vrf-04
  */

class ECVRF25519 extends EC {
  val suite: Array[Byte] = Array(0x03.toByte)
  val cofactor: Array[Byte] = Array.fill(SCALAR_BYTES){0x00.toByte}
  val zeroScalar: Array[Byte] = Array.fill(SCALAR_BYTES){0x00.toByte}
  val oneScalar: Array[Byte] = Array.fill(SCALAR_BYTES){0x00.toByte}
  val np: Array[Int] = Array.fill(SCALAR_INTS){0}
  val nb: Array[Int] = Array.fill(SCALAR_INTS){0}
  val C_BYTES = 16
  val PI_BYTES: Int = POINT_BYTES+SCALAR_BYTES+C_BYTES
  val neutralPointBytes: Array[Byte] = Array.fill(POINT_BYTES){0x00.toByte}
  val NP = new PointAccum
  cofactor.update(0,0x08.toByte)
  oneScalar.update(0,0x01.toByte)
  pointSetNeutral(NP)
  encodePoint(NP,neutralPointBytes,0)


  def generatePrivateKey(random: SecureRandom, k: Array[Byte]): Unit = {
    random.nextBytes(k)
  }

  def generatePublicKey(sk: Array[Byte], skOff: Int, pk: Array[Byte], pkOff: Int): Unit = {
    val h = new Array[Byte](sha512Digest.getDigestSize)
    sha512Digest.update(sk, skOff, SECRET_KEY_SIZE)
    sha512Digest.doFinal(h, 0)
    val s = new Array[Byte](SCALAR_BYTES)
    pruneScalar(h, 0, s)
    scalarMultBaseEncoded(s, pk, pkOff)
  }

  def verifyKeyPair(sk:Array[Byte],pk: Array[Byte]): Boolean = {
    if (pk.length == PUBLIC_KEY_SIZE && sk.length == SECRET_KEY_SIZE) {
      val pkt: Array[Byte] = Array.fill[Byte](PUBLIC_KEY_SIZE)(0)
      generatePublicKey(sk, 0, pkt, 0)
      pkt sameElements pk
    } else {
      false
    }
  }

  def isNeutralPoint(p: PointAccum): Boolean = {
    val pBytes: Array[Byte] = Array.fill(POINT_BYTES){0x00.toByte}
    encodePoint(p,pBytes,0)
    pBytes sameElements neutralPointBytes
  }

  def isNeutralPoint(p: PointExt): Boolean = {
    val pBytes: Array[Byte] = Array.fill(POINT_BYTES){0x00.toByte}
    val pA = new PointAccum
    decodeScalar(oneScalar,0,np)
    decodeScalar(zeroScalar,0,nb)
    scalarMultStraussVar(nb,np,p,pA)
    encodePoint(pA,pBytes,0)
    pBytes sameElements neutralPointBytes
  }

  def pruneHash(s: Array[Byte]): Array[Byte] = {
    val h: Array[Byte] = new Array[Byte](sha512Digest.getDigestSize)
    sha512Digest.update(s,0,SECRET_KEY_SIZE)
    sha512Digest.doFinal(h,0)
    h.update(0,(h(0) & 0xF8).toByte)
    h.update(SCALAR_BYTES-1,(h(SCALAR_BYTES-1) & 0x7F).toByte)
    h.update(SCALAR_BYTES-1,(h(SCALAR_BYTES-1) | 0x40).toByte)
    h
  }

  def scalarMultBaseEncoded(s: Array[Byte]): Array[Byte] = {
    val r: Array[Byte] = Array.fill(SCALAR_BYTES){0x00}
    scalarMultBaseEncoded(s,r,0)
    r
  }

  /*
  ECVRF_validate_key(PK_string)
  Input:
  PK_string - public key, an octet string
  Output:
  "INVALID", or
  Y - public key, an EC point
  Steps:
  1. Y = string_to_point(PK_string)
  2. If Y is "INVALID", output "INVALID" and stop
  3. If cofactor*Y is the EC point at infinty, output "INVALID" and
  stop
  4. Output Y
  */

  private def verifyPublicKey(pk: Array[Byte]): Boolean = {
    if (pk.length == PUBLIC_KEY_SIZE) {
      val Y = new PointExt
      val CY = new PointAccum
      val decoded = decodePointVar(pk,0,negate = false,Y)
      if (decoded) {
        decodeScalar(cofactor, 0, np)
        decodeScalar(zeroScalar, 0, nb)
        scalarMultStraussVar(nb, np, Y, CY)
        !isNeutralPoint(CY)
      } else {
        false
      }
    } else {
      false
    }
  }

  /*
  ECVRF_hash_to_try_and_increment(suite_string, Y, alpha_string)
  Input:
  suite_string - a single octet specifying ECVRF ciphersuite.
  Y - public key, an EC point
  alpha_string - value to be hashed, an octet string
  Output:
  H - hashed value, a finite EC point in G
  Steps:
  1. ctr = 0
  2. PK_string = point_to_string(Y)
  3. one_string = 0x01 = int_to_string(1, 1), a single octet with
  value 1
  4. H = "INVALID"
  5. While H is "INVALID" or H is EC point at infinity:
  6.
  A. ctr_string = int_to_string(ctr, 1)
  B. hash_string = Hash(suite_string || one_string || PK_string ||
  alpha_string || ctr_string)
  C. H = arbitrary_string_to_point(hash_string)
  D. If H is not "INVALID" and cofactor > 1, set H = cofactor * H
  E. ctr = ctr + 1
  Output H
 */

  //This leads to side channel attack (timing attack) if alpha is a secret

  private def ECVRF_hash_to_curve_try_and_increment(Y: Array[Byte],a: Array[Byte]): (PointAccum, Array[Byte]) = {
    var ctr = 0
    val one = Array(0x01.toByte)
    val hash: Array[Byte] = new Array[Byte](POINT_BYTES)
    val H = new PointExt
    val HR = new PointAccum
    var isPoint = false
    while (!isPoint) {
      val ctr_byte = Array(ctr.toByte)
      val input = suite++one++Y++a++ctr_byte
      val output = new Array[Byte](sha512Digest.getDigestSize)
      sha512Digest.update(input,0,input.length)
      sha512Digest.doFinal(output,0)
      java.lang.System.arraycopy(output,0,hash,0,POINT_BYTES)
      isPoint = decodePointVar(hash,0,negate = false,H)
      if (isPoint){
        isPoint != isNeutralPoint(H)
      }
      ctr += 1
    }
    decodeScalar(cofactor,0,np)
    decodeScalar(zeroScalar,0,nb)
    scalarMultStraussVar(nb,np,H,HR)
    encodePoint(HR,hash,0)
    (HR, hash)
  }

  /*
  ECVRF_hash_points(P1, P2, ..., PM)
  Input:
  P1...PM - EC points in G
  Output:
  c - hash value, integer between 0 and 2^(8n)-1
  Steps:
  1. two_string = 0x02 = int_to_string(2, 1), a single octet with
  value 2
  2. Initialize str = suite_string || two_string
  3. for PJ in [P1, P2, ... PM]:
  str = str || point_to_string(PJ)
  4. c_string = Hash(str)
  5. truncated_c_string = c_string[0]...c_string[n-1]
  6. c = string_to_int(truncated_c_string)
  7. Output c
  */

  private def ECVRF_hash_points(p1: PointAccum, p2: PointAccum, p3: PointAccum, p4: PointAccum): Array[Byte] ={
    val two: Array[Byte] = Array(0x02.toByte)
    var str: Array[Byte] = suite++two
    val r: Array[Byte] = Array.fill(POINT_BYTES){0x00.toByte}
    val out:Array[Byte] = new Array[Byte](sha512Digest.getDigestSize)
    encodePoint(p1,r,0)
    str = str++r
    encodePoint(p2,r,0)
    str = str++r
    encodePoint(p3,r,0)
    str = str++r
    encodePoint(p4,r,0)
    str = str++r
    sha512Digest.update(str,0,str.length)
    sha512Digest.doFinal(out,0)
    out.take(C_BYTES)++Array.fill(SCALAR_BYTES-C_BYTES){0x00.toByte}
  }

  /*
  ECVRF_nonce_generation_RFC8032(SK, h_string)
  Input:
  SK - an ECVRF secret key
  h_string - an octet string
  Output:
  k - an integer between 0 and q-1
  Steps:
  1. hashed_sk_string = Hash (SK)
  2. truncated_hashed_sk_string =
  hashed_sk_string[32]...hashed_sk_string[63]
  3. k_string = Hash(truncated_hashed_sk_string || h_string)
  4. k = string_to_int(k_string) mod q
  */

  private def ECVRF_nonce_generation_RFC8032(sk: Array[Byte],h: Array[Byte]): Array[Byte] = {
    val out:Array[Byte] = new Array[Byte](sha512Digest.getDigestSize)
    sha512Digest.update(sk,0,SECRET_KEY_SIZE)
    sha512Digest.doFinal(out,0)
    val trunc_hashed_sk = out.drop(SCALAR_BYTES)++h
    sha512Digest.update(trunc_hashed_sk,0,trunc_hashed_sk.length)
    sha512Digest.doFinal(out,0)
    val k_string = out
    reduceScalar(k_string)
  }

  /*
  ECVRF Proving
  ECVRF_prove(SK, alpha_string)
  Input:
  SK - VRF private key
  alpha_string = input alpha, an octet string
  Output:
  pi_string - VRF proof, octet string of length ptLen+n+qLen
  Steps:
  1. Use SK to derive the VRF secret scalar x and the VRF public key Y
  = x*B
  (this derivation depends on the ciphersuite, as per Section 5.5;
  these values can be cached, for example, after key generation,
  and need not be rederived each time)
  2. H = ECVRF_hash_to_curve(suite_string, Y, alpha_string)
  3. h_string = point_to_string(H)
  4. Gamma = x*H
  5. k = ECVRF_nonce_generation(SK, h_string)
  6. c = ECVRF_hash_points(H, Gamma, k*B, k*H)
  7. s = (k + c*x) mod q
  8. pi_string = point_to_string(Gamma) || int_to_string(c, n) || int_to_string(s, qLen)
  9. Output pi_string
  */

  def vrfProof(sk: Array[Byte], alpha: Array[Byte]): Array[Byte] = {
    assert(sk.length == SECRET_KEY_SIZE)
    // secret scalar
    val x = pruneHash(sk)
    // public key
    val pk = scalarMultBaseEncoded(x)
    assert(verifyKeyPair(sk,pk))
    val H: (PointAccum, Array[Byte]) = ECVRF_hash_to_curve_try_and_increment(pk,alpha)
    val nonce = ECVRF_nonce_generation_RFC8032(sk,H._2)
    assert(checkScalarVar(nonce))
    val gamma = new PointAccum
    decodeScalar(x,0,np)
    decodeScalar(zeroScalar,0,nb)
    scalarMultStraussVar(nb,np,pointCopy(H._1),gamma)
    val k = ECVRF_nonce_generation_RFC8032(sk,H._2)
    assert(checkScalarVar(k))
    val kB = new PointAccum
    val kH = new PointAccum
    scalarMultBase(k,kB)
    decodeScalar(k,0,np)
    decodeScalar(zeroScalar,0,nb)
    scalarMultStraussVar(nb,np,pointCopy(H._1),kH)
    val c = ECVRF_hash_points(H._1,gamma,kB,kH)
    val s = calculateS(k,c,x)
    val gamma_str: Array[Byte] = Array.fill(POINT_BYTES){0x00.toByte}
    encodePoint(gamma,gamma_str,0)
    val pi = gamma_str++c.take(C_BYTES)++s
    assert(pi.length == PI_BYTES)
    pi
  }

  /*
  ECVRF_verify(Y, pi_string, alpha_string)
  Input:
  Y - public key, an EC point
  pi_string - VRF proof, octet string of length ptLen+n+qLen
  alpha_string - VRF input, octet string
  Output:
  ("VALID", beta_string), where beta_string is the VRF hash output,
  octet string of length hLen; or
  "INVALID"
  Steps:
  1. D = ECVRF_decode_proof(pi_string)
  2. If D is "INVALID", output "INVALID" and stop
  3. (Gamma, c, s) = D
  4. H = ECVRF_hash_to_curve(suite_string, Y, alpha_string)
  5. U = s*B - c*Y
  6. V = s*H - c*Gamma
  7. c’ = ECVRF_hash_points(H, Gamma, U, V)
  8. If c and c’ are equal, output ("VALID",
  ECVRF_proof_to_hash(pi_string)); else output "INVALID"
  */

  def vrfVerify(pk: Array[Byte], alpha: Array[Byte], pi: Array[Byte]): Boolean = {
    assert(pi.length == PI_BYTES)
    val gamma_str = pi.take(POINT_BYTES)
    val c = pi.slice(POINT_BYTES,POINT_BYTES+C_BYTES)++Array.fill(SCALAR_BYTES-C_BYTES){0x00.toByte}
    val s = pi.drop(POINT_BYTES+C_BYTES)
    assert(checkPointVar(gamma_str))
    assert(checkScalarVar(c))
    assert(checkScalarVar(s))
    assert(verifyPublicKey(pk))
    val H: (PointAccum, Array[Byte]) = ECVRF_hash_to_curve_try_and_increment(pk,alpha)
    val gamma = new PointExt
    val Y = new PointExt
    decodePointVar(gamma_str,0,negate = false,gamma)
    decodePointVar(pk,0,negate = false,Y)
    val A = new PointAccum //s*B
    val B = new PointAccum //c*Y
    val C = new PointAccum //s*H
    val D = new PointAccum //c*Gamma
    val U = new PointAccum
    val V = new PointAccum
    val g = new PointAccum
    val t = new PointExt
    scalarMultBase(s,A)
    decodeScalar(c,0,np)
    decodeScalar(zeroScalar,0,nb)
    scalarMultStraussVar(nb,np,Y,B)
    decodeScalar(s,0,np)
    decodeScalar(zeroScalar,0,nb)
    scalarMultStraussVar(nb,np,pointCopy(H._1),C)
    decodeScalar(c,0,np)
    decodeScalar(zeroScalar,0,nb)
    scalarMultStraussVar(nb,np,gamma,D)
    decodeScalar(oneScalar,0,np)
    decodeScalar(zeroScalar,0,nb)
    pointAddVar(negate = true,pointCopy(A),pointCopy(B),t)
    scalarMultStraussVar(nb,np,t,U)
    pointAddVar(negate = true,pointCopy(C),pointCopy(D),t)
    scalarMultStraussVar(nb,np,t,V)
    scalarMultStraussVar(nb,np,gamma,g)
    val cp = ECVRF_hash_points(H._1,g,U,V)
    c sameElements cp
  }

  /*
  ECVRF_proof_to_hash(pi_string)
  Input:
  pi_string - VRF proof, octet string of length ptLen+n+qLen
  Output:
  "INVALID", or
  beta_string - VRF hash output, octet string of length hLen
  Important note:
  ECVRF_proof_to_hash should be run only on pi_string that is known
  to have been produced by ECVRF_prove, or from within ECVRF_verify
  as specified in Section 5.3.
  Steps:
  1. D = ECVRF_decode_proof(pi_string)
  2. If D is "INVALID", output "INVALID" and stop
  3. (Gamma, c, s) = D
  4. three_string = 0x03 = int_to_string(3, 1), a single octet with
  value 3
  5. beta_string = Hash(suite_string || three_string ||
  point_to_string(cofactor * Gamma))
  6. Output beta_string
  */

  def vrfProofToHash(pi: Array[Byte]): Array[Byte] = {
    assert(pi.length == PI_BYTES)
    val gamma_str = pi.take(POINT_BYTES)
    val c = pi.slice(POINT_BYTES,POINT_BYTES+C_BYTES)++Array.fill(SCALAR_BYTES-C_BYTES){0x00.toByte}
    val s = pi.drop(POINT_BYTES+C_BYTES)
    val three = Array(0x03.toByte)
    assert(checkPointVar(gamma_str))
    assert(checkScalarVar(c))
    assert(checkScalarVar(s))
    val gamma = new PointExt
    val cg = new PointAccum
    decodePointVar(gamma_str,0,negate = false,gamma)
    decodeScalar(cofactor,0,np)
    decodeScalar(zeroScalar,0,nb)
    scalarMultStraussVar(nb,np,gamma,cg)
    val cg_enc = Array.fill(POINT_BYTES){0x00.toByte}
    encodePoint(cg,cg_enc,0)
    val input = suite++three++cg_enc
    val out = new Array[Byte](sha512Digest.getDigestSize)
    sha512Digest.update(input,0,input.length)
    sha512Digest.doFinal(out,0)
    out
  }

}