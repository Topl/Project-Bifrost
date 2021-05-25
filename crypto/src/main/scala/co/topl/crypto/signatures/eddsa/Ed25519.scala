package co.topl.crypto.signatures.eddsa

import cats.implicits._
import co.topl.crypto.hash.sha256
import co.topl.crypto.signatures._
import co.topl.crypto.{PrivateKey, PublicKey, SharedSecret}

import java.security.SecureRandom
import java.util

/**
 * AMS 2021:
 * Ed25519 ported from BouncyCastle
 * Licensing: https://www.bouncycastle.org/licence.html
 * Copyright (c) 2000 - 2021 The Legion of the Bouncy Castle Inc. (https://www.bouncycastle.org)
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the
 * Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
 * WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS
 * OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

object Ed25519 extends EC with EllipticCurveSignatureScheme {

  def dom2(d: SHA512Digest, phflag: Byte, ctx: Array[Byte]): Unit =
    if (ctx.nonEmpty) {
      d.update(DOM2_PREFIX, 0, DOM2_PREFIX.length)
      d.update(phflag)
      d.update(ctx.length.toByte)
      d.update(ctx, 0, ctx.length)
    }

  def generatePrivateKey(random: SecureRandom, k: Array[Byte]): Unit =
    random.nextBytes(k)

  def generatePublicKey(sk: Array[Byte], skOff: Int, pk: Array[Byte], pkOff: Int): Unit = {
    val h = new Array[Byte](shaDigest.getDigestSize)
    shaDigest.update(sk, skOff, SECRET_KEY_SIZE)
    shaDigest.doFinal(h, 0)
    val s = new Array[Byte](SCALAR_BYTES)
    pruneScalar(h, 0, s)

    scalarMultBaseEncoded(s, pk, pkOff)
  }

  def implSign(
    d:      SHA512Digest,
    h:      Array[Byte],
    s:      Array[Byte],
    pk:     Array[Byte],
    pkOff:  Int,
    ctx:    Array[Byte],
    phflag: Byte,
    m:      Array[Byte],
    mOff:   Int,
    mLen:   Int,
    sig:    Array[Byte],
    sigOff: Int
  ): Unit = {
    dom2(d, phflag, ctx)
    d.update(h, SCALAR_BYTES, SCALAR_BYTES)
    d.update(m, mOff, mLen)
    d.doFinal(h, 0)
    val r = reduceScalar(h)
    val R = new Array[Byte](POINT_BYTES)
    scalarMultBaseEncoded(r, R, 0)
    dom2(d, phflag, ctx)
    d.update(R, 0, POINT_BYTES)
    d.update(pk, pkOff, POINT_BYTES)
    d.update(m, mOff, mLen)
    d.doFinal(h, 0)
    val k = reduceScalar(h)
    val S = calculateS(r, k, s)
    System.arraycopy(R, 0, sig, sigOff, POINT_BYTES)
    System.arraycopy(S, 0, sig, sigOff + POINT_BYTES, SCALAR_BYTES)
  }

  def implSign(
    sk:     Array[Byte],
    skOff:  Int,
    ctx:    Array[Byte],
    phflag: Byte,
    m:      Array[Byte],
    mOff:   Int,
    mLen:   Int,
    sig:    Array[Byte],
    sigOff: Int
  ): Unit = {
    if (!checkContextVar(ctx, phflag)) throw new IllegalArgumentException("ctx")
    val h = new Array[Byte](shaDigest.getDigestSize)
    shaDigest.update(sk, skOff, SECRET_KEY_SIZE)
    shaDigest.doFinal(h, 0)
    val s = new Array[Byte](SCALAR_BYTES)
    pruneScalar(h, 0, s)
    val pk = new Array[Byte](POINT_BYTES)
    scalarMultBaseEncoded(s, pk, 0)
    implSign(shaDigest, h, s, pk, 0, ctx, phflag, m, mOff, mLen, sig, sigOff)
  }

  def implSign(
    sk:     Array[Byte],
    skOff:  Int,
    pk:     Array[Byte],
    pkOff:  Int,
    ctx:    Array[Byte],
    phflag: Byte,
    m:      Array[Byte],
    mOff:   Int,
    mLen:   Int,
    sig:    Array[Byte],
    sigOff: Int
  ): Unit = {
    if (!checkContextVar(ctx, phflag)) throw new IllegalArgumentException("ctx")
    val h = new Array[Byte](shaDigest.getDigestSize)
    shaDigest.update(sk, skOff, SECRET_KEY_SIZE)
    shaDigest.doFinal(h, 0)
    val s = new Array[Byte](SCALAR_BYTES)
    pruneScalar(h, 0, s)
    implSign(shaDigest, h, s, pk, pkOff, ctx, phflag, m, mOff, mLen, sig, sigOff)
  }

  def implVerify(
    sig:    Array[Byte],
    sigOff: Int,
    pk:     Array[Byte],
    pkOff:  Int,
    ctx:    Array[Byte],
    phflag: Byte,
    m:      Array[Byte],
    mOff:   Int,
    mLen:   Int
  ): Boolean = {
    if (!checkContextVar(ctx, phflag)) throw new IllegalArgumentException("ctx")
    val R = util.Arrays.copyOfRange(sig, sigOff, sigOff + POINT_BYTES)
    val S = util.Arrays.copyOfRange(sig, sigOff + POINT_BYTES, sigOff + SIGNATURE_SIZE)
    if (!checkPointVar(R)) return false
    if (!checkScalarVar(S)) return false
    val pA = new PointExt
    if (!decodePointVar(pk, pkOff, negate = true, pA)) return false
    val h = new Array[Byte](shaDigest.getDigestSize)
    dom2(shaDigest, phflag, ctx)
    shaDigest.update(R, 0, POINT_BYTES)
    shaDigest.update(pk, pkOff, POINT_BYTES)
    shaDigest.update(m, mOff, mLen)
    shaDigest.doFinal(h, 0)
    val k = reduceScalar(h)
    val nS = new Array[Int](SCALAR_INTS)
    decodeScalar(S, 0, nS)
    val nA = new Array[Int](SCALAR_INTS)
    decodeScalar(k, 0, nA)
    val pR = new PointAccum
    scalarMultStraussVar(nS, nA, pA, pR)
    val check = new Array[Byte](POINT_BYTES)
    encodePoint(pR, check, 0)
    util.Arrays.equals(check, R)
  }

  def sign(sk: Array[Byte], skOff: Int, m: Array[Byte], mOff: Int, mLen: Int, sig: Array[Byte], sigOff: Int): Unit = {
    val ctx: Array[Byte] = Array.empty
    val phflag: Byte = 0x00
    implSign(sk, skOff, ctx, phflag, m, mOff, mLen, sig, sigOff)
  }

  def sign(
    sk:     Array[Byte],
    skOff:  Int,
    pk:     Array[Byte],
    pkOff:  Int,
    m:      Array[Byte],
    mOff:   Int,
    mLen:   Int,
    sig:    Array[Byte],
    sigOff: Int
  ): Unit = {
    val ctx: Array[Byte] = Array.empty
    val phflag: Byte = 0x00
    implSign(sk, skOff, pk, pkOff, ctx, phflag, m, mOff, mLen, sig, sigOff)
  }

  def sign(
    sk:     Array[Byte],
    skOff:  Int,
    ctx:    Array[Byte],
    m:      Array[Byte],
    mOff:   Int,
    mLen:   Int,
    sig:    Array[Byte],
    sigOff: Int
  ): Unit = {
    val phflag: Byte = 0x00
    implSign(sk, skOff, ctx, phflag, m, mOff, mLen, sig, sigOff)
  }

  def sign(
    sk:     Array[Byte],
    skOff:  Int,
    pk:     Array[Byte],
    pkOff:  Int,
    ctx:    Array[Byte],
    m:      Array[Byte],
    mOff:   Int,
    mLen:   Int,
    sig:    Array[Byte],
    sigOff: Int
  ): Unit = {
    val phflag: Byte = 0x00
    implSign(sk, skOff, pk, pkOff, ctx, phflag, m, mOff, mLen, sig, sigOff)
  }

  def signPrehash(
    sk:     Array[Byte],
    skOff:  Int,
    ctx:    Array[Byte],
    ph:     Array[Byte],
    phOff:  Int,
    sig:    Array[Byte],
    sigOff: Int
  ): Unit = {
    val phflag: Byte = 0x01
    implSign(sk, skOff, ctx, phflag, ph, phOff, PREHASH_SIZE, sig, sigOff)
  }

  def signPrehash(
    sk:     Array[Byte],
    skOff:  Int,
    pk:     Array[Byte],
    pkOff:  Int,
    ctx:    Array[Byte],
    ph:     Array[Byte],
    phOff:  Int,
    sig:    Array[Byte],
    sigOff: Int
  ): Unit = {
    val phflag: Byte = 0x01
    implSign(sk, skOff, pk, pkOff, ctx, phflag, ph, phOff, PREHASH_SIZE, sig, sigOff)
  }

  def signPrehash(
    sk:     Array[Byte],
    skOff:  Int,
    ctx:    Array[Byte],
    ph:     SHA512Digest,
    sig:    Array[Byte],
    sigOff: Int
  ): Unit = {
    val m = new Array[Byte](PREHASH_SIZE)
    if (PREHASH_SIZE != ph.doFinal(m, 0)) throw new IllegalArgumentException("ph")
    val phflag: Byte = 0x01
    implSign(sk, skOff, ctx, phflag, m, 0, m.length, sig, sigOff)
  }

  def signPrehash(
    sk:     Array[Byte],
    skOff:  Int,
    pk:     Array[Byte],
    pkOff:  Int,
    ctx:    Array[Byte],
    ph:     SHA512Digest,
    sig:    Array[Byte],
    sigOff: Int
  ): Unit = {
    val m = new Array[Byte](PREHASH_SIZE)
    if (PREHASH_SIZE != ph.doFinal(m, 0)) throw new IllegalArgumentException("ph")
    val phflag: Byte = 0x01
    implSign(sk, skOff, pk, pkOff, ctx, phflag, m, 0, m.length, sig, sigOff)
  }

  def verify(
    sig:    Array[Byte],
    sigOff: Int,
    pk:     Array[Byte],
    pkOff:  Int,
    m:      Array[Byte],
    mOff:   Int,
    mLen:   Int
  ): Boolean = {
    val ctx: Array[Byte] = Array.empty
    val phflag: Byte = 0x00
    implVerify(sig, sigOff, pk, pkOff, ctx, phflag, m, mOff, mLen)
  }

  def verify(
    sig:    Array[Byte],
    sigOff: Int,
    pk:     Array[Byte],
    pkOff:  Int,
    ctx:    Array[Byte],
    m:      Array[Byte],
    mOff:   Int,
    mLen:   Int
  ): Boolean = {
    val phflag: Byte = 0x00
    implVerify(sig, sigOff, pk, pkOff, ctx, phflag, m, mOff, mLen)
  }

  def verifyPrehash(
    sig:    Array[Byte],
    sigOff: Int,
    pk:     Array[Byte],
    pkOff:  Int,
    ctx:    Array[Byte],
    ph:     Array[Byte],
    phOff:  Int
  ): Boolean = {
    val phflag: Byte = 0x01
    implVerify(sig, sigOff, pk, pkOff, ctx, phflag, ph, phOff, PREHASH_SIZE)
  }

  def verifyPrehash(
    sig:    Array[Byte],
    sigOff: Int,
    pk:     Array[Byte],
    pkOff:  Int,
    ctx:    Array[Byte],
    ph:     SHA512Digest
  ): Boolean = {
    val m = new Array[Byte](PREHASH_SIZE)
    if (PREHASH_SIZE != ph.doFinal(m, 0)) throw new IllegalArgumentException("ph")
    val phflag = 0x01.toByte
    implVerify(sig, sigOff, pk, pkOff, ctx, phflag, m, 0, m.length)
  }

  override val SignatureLength: Int = SIGNATURE_SIZE
  override val KeyLength: Int = SECRET_KEY_SIZE

  override def createKeyPair(seed: Array[Byte]): (PrivateKey, PublicKey) = {
    val sk:Array[Byte] = new Array[Byte](SECRET_KEY_SIZE)
    val pk:Array[Byte] = new Array[Byte](PUBLIC_KEY_SIZE)
    val hashedSeed = sha256.hash(seed)
    generatePrivateKey(new SecureRandom(hashedSeed.value), sk)
    generatePublicKey(sk, 0, pk, 0)
    (PrivateKey(sk), PublicKey(pk))
  }

  override def sign(privateKey: PrivateKey, message: MessageToSign): Signature = {
    require(privateKey.value.length == SECRET_KEY_SIZE)
    val sig = new Array[Byte](SIGNATURE_SIZE)
    sign(privateKey.value, 0, message, 0, message.length, sig, 0)
    Signature(sig)
  }

  override def verify(signature: Signature, message: MessageToSign, publicKey: PublicKey): Boolean =
    signature.value.length == SIGNATURE_SIZE &&
    publicKey.value.length == PUBLIC_KEY_SIZE &&
    verify(signature.value, 0, publicKey.value, 0, message, 0, message.length)

  override def createSharedSecret(privateKey: PrivateKey, publicKey: PublicKey): SharedSecret = ???
}
