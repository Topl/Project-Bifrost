package co.topl.crypto.signatures.eddsa

import java.security.MessageDigest
import java.util
import scala.util.control.Breaks._

/*
  Ed25519 is EdDSA instantiated with:
+-----------+-------------------------------------------------------+
| Parameter |                                                 Value |
+-----------+-------------------------------------------------------+
|     p     |     p of edwards25519 in [RFC7748] (i.e., 2^255 - 19) |
|     b     |                                                   256 |
|  encoding |    255-bit little-endian encoding of {0, 1, ..., p-1} |
|  of GF(p) |                                                       |
|    H(x)   |            SHA-512(dom2(phflag,context)||x) [RFC6234] |
|     c     |       base 2 logarithm of cofactor of edwards25519 in |
|           |                                   [RFC7748] (i.e., 3) |
|     n     |                                                   254 |
|     d     |  d of edwards25519 in [RFC7748] (i.e., -121665/121666 |
|           | = 370957059346694393431380835087545651895421138798432 |
|           |                           19016388785533085940283555) |
|     a     |                                                    -1 |
|     B     | (X(P),Y(P)) of edwards25519 in [RFC7748] (i.e., (1511 |
|           | 22213495354007725011514095885315114540126930418572060 |
|           | 46113283949847762202, 4631683569492647816942839400347 |
|           |      5163141307993866256225615783033603165251855960)) |
|     L     |             order of edwards25519 in [RFC7748] (i.e., |
|           |        2^252+27742317777372353535851937790883648493). |
|    PH(x)  |                       x (i.e., the identity function) |
+-----------+-------------------------------------------------------+
Table 1: Parameters of Ed25519
  */


/**
  * AMS 2021: Supporting curve point operations for all EC crypto primitives in eddsa package
  * Directly ported from BouncyCastle implementation of Ed25519 RFC8032 https://tools.ietf.org/html/rfc8032
  * Licensing: https://www.bouncycastle.org/licence.html
  * Copyright (c) 2000 - 2021 The Legion of the Bouncy Castle Inc. (https://www.bouncycastle.org)
  * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
  * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
  * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
  */

trait EC {

  val x25519Field:X25519Field = new X25519Field

  val M28L = 0x0FFFFFFFL
  val M32L = 0xFFFFFFFFL
  val POINT_BYTES:Int = 32
  val SCALAR_INTS:Int = 8
  val SCALAR_BYTES:Int = SCALAR_INTS * 4
  val PREHASH_SIZE:Int = 64
  val PUBLIC_KEY_SIZE:Int = POINT_BYTES
  val SECRET_KEY_SIZE:Int = 32
  val SIGNATURE_SIZE:Int = POINT_BYTES + SCALAR_BYTES
  val DOM2_PREFIX:Array[Byte] = "SigEd25519 no Ed25519 collisions".getBytes()

  val P:Array[Int] = Array[Int](0xFFFFFFED, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0x7FFFFFFF)
  val L:Array[Int] = Array[Int](0x5CF5D3ED, 0x5812631A, 0xA2F79CD6, 0x14DEF9DE, 0x00000000, 0x00000000, 0x00000000, 0x10000000)

  val L0 = 0xFCF5D3ED // L0:26/--
  val L1 = 0x012631A6 // L1:24/22
  val L2 = 0x079CD658 // L2:27/--
  val L3 = 0xFF9DEA2F // L3:23/--
  val L4 = 0x000014DF // L4:12/11

  val B_x:Array[Int] = Array[Int](0x0325D51A, 0x018B5823, 0x007B2C95, 0x0304A92D, 0x00D2598E, 0x01D6DC5C, 0x01388C7F, 0x013FEC0A, 0x029E6B72, 0x0042D26D)
  val B_y:Array[Int] = Array[Int](0x02666658, 0x01999999, 0x00666666, 0x03333333, 0x00CCCCCC, 0x02666666, 0x01999999, 0x00666666, 0x03333333, 0x00CCCCCC)
  val C_d:Array[Int] = Array[Int](0x035978A3, 0x02D37284, 0x018AB75E, 0x026A0A0E, 0x0000E014, 0x0379E898, 0x01D01E5D, 0x01E738CC, 0x03715B7F, 0x00A406D9)
  val C_d2:Array[Int] = Array[Int](0x02B2F159, 0x01A6E509, 0x01156EBD, 0x00D4141D, 0x0001C029, 0x02F3D130, 0x03A03CBB, 0x01CE7198, 0x02E2B6FF, 0x00480DB3)
  val C_d4:Array[Int] = Array[Int](0x0165E2B2, 0x034DCA13, 0x002ADD7A, 0x01A8283B, 0x00038052, 0x01E7A260, 0x03407977, 0x019CE331, 0x01C56DFF, 0x00901B67)

  val WNAF_WIDTH_BASE:Int = 7
  val PRECOMP_BLOCKS:Int = 8
  val PRECOMP_TEETH:Int = 4
  val PRECOMP_SPACING:Int = 8
  val PRECOMP_POINTS:Int = 1 << (PRECOMP_TEETH - 1)
  val PRECOMP_MASK:Int = PRECOMP_POINTS - 1

  var precompLock:Option[Any] = None
  var precompBaseTable:Array[PointExt] = Array.empty
  var precompBase:Array[Int] = Array.empty

  val M:Long = 0xFFFFFFFFL

  class PointAccum {
    val x:Array[Int] = x25519Field.create
    val y:Array[Int] = x25519Field.create
    val z:Array[Int] = x25519Field.create
    val u:Array[Int] = x25519Field.create
    val v:Array[Int] = x25519Field.create
  }

  class PointExt {
    val x:Array[Int] = x25519Field.create
    val y:Array[Int] = x25519Field.create
    val z:Array[Int] = x25519Field.create
    val t:Array[Int] = x25519Field.create
  }

  class PointPrecomp {
    val ypx_h:Array[Int] = x25519Field.create
    val ymx_h:Array[Int] = x25519Field.create
    val xyd:Array[Int] = x25519Field.create
  }

  class SHA512Digest {

    val digest: MessageDigest = MessageDigest.getInstance("SHA-512")

    def Sha512(bytes: Array[Byte]):Array[Byte] = {
      digest.reset()
      digest.update(bytes)
      digest.digest()
    }

    def getAlgorithmName: String = "SHA-512"

    def getDigestSize: Int = 64

    def update(in: Byte): Unit = digest.update(in)

    def update(in: Array[Byte], inOff: Int, len: Int): Unit = digest.update(in,inOff,len)

    def doFinal(out: Array[Byte], outOff: Int): Int = {
      digest.digest(out,outOff,out.length)
    }

    def reset(): Unit = digest.reset()
  }

  class SHA256Digest {

    val digest: MessageDigest = MessageDigest.getInstance("SHA-256")

    def Sha256(bytes: Array[Byte]):Array[Byte] = {
      digest.reset()
      digest.update(bytes)
      digest.digest()
    }

    def getAlgorithmName: String = "SHA-256"

    def getDigestSize: Int = 32

    def update(in: Byte): Unit = digest.update(in)

    def update(in: Array[Byte], inOff: Int, len: Int): Unit = digest.update(in,inOff,len)

    def doFinal(out: Array[Byte], outOff: Int): Int = {
      digest.digest(out,outOff,out.length)
    }

    def reset(): Unit = digest.reset()
  }

  val sha512Digest:SHA512Digest = new SHA512Digest

  val sha256Digest:SHA256Digest = new SHA256Digest

  def mulAddTo256(x: Array[Int], y: Array[Int], zz: Array[Int]): Int = {
    val y_0:Long = y(0) & M
    val y_1:Long = y(1) & M
    val y_2:Long = y(2) & M
    val y_3:Long = y(3) & M
    val y_4:Long = y(4) & M
    val y_5:Long = y(5) & M
    val y_6:Long = y(6) & M
    val y_7:Long = y(7) & M
    var zc:Long = 0
    for (i <- 0 until 8) {
      var c:Long = 0
      val x_i = x(i) & M
      c += x_i * y_0 + (zz(i + 0) & M)
      zz(i + 0) = c.toInt
      c >>>= 32
      c += x_i * y_1 + (zz(i + 1) & M)
      zz(i + 1) = c.toInt
      c >>>= 32
      c += x_i * y_2 + (zz(i + 2) & M)
      zz(i + 2) = c.toInt
      c >>>= 32
      c += x_i * y_3 + (zz(i + 3) & M)
      zz(i + 3) = c.toInt
      c >>>= 32
      c += x_i * y_4 + (zz(i + 4) & M)
      zz(i + 4) = c.toInt
      c >>>= 32
      c += x_i * y_5 + (zz(i + 5) & M)
      zz(i + 5) = c.toInt
      c >>>= 32
      c += x_i * y_6 + (zz(i + 6) & M)
      zz(i + 6) = c.toInt
      c >>>= 32
      c += x_i * y_7 + (zz(i + 7) & M)
      zz(i + 7) = c.toInt
      c >>>= 32
      zc += c + zz(i + 8) & M
      zz(i + 8) = zc.toInt
      zc >>>= 32
    }
    zc.toInt
  }

  def gte256(x: Array[Int], y: Array[Int]): Boolean = {
    for (i <- 7 to 0 by -1) {
      val x_i = x(i) ^ Integer.MIN_VALUE
      val y_i = y(i) ^ Integer.MIN_VALUE
      if (x_i < y_i) return false
      if (x_i > y_i) return true
    }
    true
  }

  def cmov(len: Int, mask: Int, x: Array[Int], xOff: Int, z: Array[Int], zOff: Int): Unit = {
    var maskv = mask
    maskv = -(maskv & 1)
    for (i <- 0 until len) {
      var z_i = z(zOff + i)
      val diff = z_i ^ x(xOff + i)
      z_i ^= (diff & maskv)
      z(zOff + i) = z_i
    }
  }

  def cadd(len: Int, mask: Int, x: Array[Int], y: Array[Int], z: Array[Int]): Int = {
    val m = -(mask & 1) & M
    var c = 0L
    for (i <- 0 until len) {
      c += (x(i) & M) + (y(i) & m)
      z(i) = c.toInt
      c >>>= 32
    }
    c.toInt
  }

  def shiftDownBit(len: Int, z: Array[Int], c: Int): Int = {
    var i:Int = len
    var cv = c
    while ({i -= 1; i} >= 0) {
      val next = z(i)
      z(i) = (next >>> 1) | (cv << 31)
      cv = next
    }
    cv << 31
  }

  def shuffle2(x: Int): Int = { // "shuffle" (twice) low half to even bits and high half to odd bits
    var t = 0
    var xv = x
    t = (xv ^ (xv >>> 7)) & 0x00AA00AA
    xv ^= (t ^ (t << 7))
    t = (xv ^ (xv >>> 14)) & 0x0000CCCC
    xv ^= (t ^ (t << 14))
    t = (xv ^ (xv >>> 4)) & 0x00F000F0
    xv ^= (t ^ (t << 4))
    t = (xv ^ (xv >>> 8)) & 0x0000FF00
    xv ^= (t ^ (t << 8))
    xv
  }

  def areAllZeroes(buf: Array[Byte], off: Int, len: Int): Boolean = {
    var bits = 0
    for (i <- 0 until len) {
      bits |= buf(off + i)
    }
    bits == 0
  }

  def calculateS(r: Array[Byte], k: Array[Byte], s: Array[Byte]): Array[Byte] = {
    val t = new Array[Int](SCALAR_INTS * 2)
    decodeScalar(r, 0, t)
    val u = new Array[Int](SCALAR_INTS)
    decodeScalar(k, 0, u)
    val v = new Array[Int](SCALAR_INTS)
    decodeScalar(s, 0, v)
    mulAddTo256(u, v, t)
    val result = new Array[Byte](SCALAR_BYTES * 2)
    for (i <- t.indices) {
      encode32(t(i), result, i * 4)
    }
    reduceScalar(result)
  }

  def checkContextVar(ctx: Array[Byte], phflag: Byte): Boolean = ctx == null && phflag == 0x00 || ctx != null && ctx.length < 256

  def checkPointVar(p: Array[Byte]): Boolean = {
    val t = new Array[Int](8)
    decode32(p, 0, t, 0, 8)
    t(7) &= 0x7FFFFFFF
    !gte256(t, P)
  }

  def checkScalarVar(s: Array[Byte]): Boolean = {
    val n = new Array[Int](SCALAR_INTS)
    decodeScalar(s, 0, n)
    !gte256(n, L)
  }

  def decode24(bs: Array[Byte], off: Int):Int = {
    var n = bs(off) & 0xFF
    n |= (bs(off+1) & 0xFF) << 8
    n |= (bs(off+2) & 0xFF) << 16
    n
  }

  def decode32(bs: Array[Byte], off: Int):Int = {
    var n = bs(off) & 0xFF
    n |= (bs(off+1) & 0xFF) << 8
    n |= (bs(off+2) & 0xFF) << 16
    n |= bs(off+3) << 24
    n
  }

  def decode32(bs: Array[Byte], bsOff: Int, n: Array[Int], nOff: Int, nLen: Int): Unit = {
    for (i <- 0 until nLen) {
      n(nOff + i) = decode32(bs, bsOff + i * 4)
    }
  }

  def decodePointVar(p: Array[Byte], pOff: Int, negate: Boolean, r:PointExt): Boolean = {
    val py = util.Arrays.copyOfRange(p, pOff, pOff + POINT_BYTES)
    if (!checkPointVar(py)) return false
    val x_0 = (py(POINT_BYTES - 1) & 0x80) >>> 7
    py(POINT_BYTES - 1) = (py(POINT_BYTES - 1) & 0x7F).toByte
    x25519Field.decode(py, 0, r.y)
    val u = x25519Field.create
    val v = x25519Field.create
    x25519Field.sqr(r.y, u)
    x25519Field.mul(C_d, u, v)
    x25519Field.subOne(u)
    x25519Field.addOne(v)
    if (!x25519Field.sqrtRatioVar(u, v, r.x)) return false
    x25519Field.normalize(r.x)
    if (x_0 == 1 && x25519Field.isZeroVar(r.x)) return false
    if (negate ^ (x_0 != (r.x(0) & 1))) x25519Field.negate(r.x, r.x)
    pointExtendXY(r)
    true
  }

  def decodeScalar(k: Array[Byte], kOff: Int, n: Array[Int]): Unit = {
    decode32(k, kOff, n, 0, SCALAR_INTS)
  }

  def encode24(n: Int, bs: Array[Byte], off: Int): Unit = {
    bs(off) = n.toByte
    bs(off+1) = (n >>> 8).toByte
    bs(off+2) = (n >>> 16).toByte
  }

  def encode32(n: Int, bs: Array[Byte], off: Int): Unit = {
    bs(off) = n.toByte
    bs(off+1) = (n >>> 8).toByte
    bs(off+2) = (n >>> 16).toByte
    bs(off+3) = (n >>> 24).toByte
  }

  def encode56(n: Long, bs: Array[Byte], off: Int): Unit = {
    encode32(n.toInt, bs, off)
    encode24((n >>> 32).toInt, bs, off + 4)
  }

  def encodePoint(p:PointAccum, r: Array[Byte], rOff: Int): Unit = {
    val x = x25519Field.create
    val y = x25519Field.create
    x25519Field.inv(p.z, y)
    x25519Field.mul(p.x, y, x)
    x25519Field.mul(p.y, y, y)
    x25519Field.normalize(x)
    x25519Field.normalize(y)
    x25519Field.encode(y, r, rOff)
    r(rOff + POINT_BYTES - 1) = (r(rOff + POINT_BYTES - 1) | ((x(0) & 1) << 7)).toByte
  }

  def getWNAF(n: Array[Int], width: Int): Array[Byte] = {
    val t = new Array[Int](SCALAR_INTS * 2)
    var tPos = t.length
    var c = 0
    var i = SCALAR_INTS
    while ({i -= 1; i} >= 0) {
      val next = n(i)
      t({tPos -= 1; tPos}) = (next >>> 16) | (c << 16)
      c = next
      t({tPos -= 1; tPos}) = c
    }
    val ws = new Array[Byte](256)
    val pow2 = 1 << width
    val mask = pow2 - 1
    val sign = pow2 >>> 1
    var j = 0
    var carry = 0
    i = 0
    while (i < t.length) {
      val word = t(i)
      while (j < 16) breakable{
        val word16 = word >>> j
        val bit = word16 & 1
        if (bit == carry) {
          j += 1
          break
        }
        var digit = (word16 & mask) + carry
        carry = digit & sign
        digit -= (carry << 1)
        carry >>>= (width - 1)
        ws((i << 4) + j) = digit.toByte
        j += width
      }
      i += 1
      j -= 16
    }
    ws
  }

  def scalarMultBaseYZ(k: Array[Byte], kOff: Int, y: Array[Int], z: Array[Int]): Unit = {
    val n = new Array[Byte](SCALAR_BYTES)
    pruneScalar(k, kOff, n)
    val p = new PointAccum
    scalarMultBase(n, p)
    x25519Field.copy(p.y, 0, y, 0)
    x25519Field.copy(p.z, 0, z, 0)
  }

  def pointAddVar(negate: Boolean, p: PointExt, r: PointAccum): Unit = {
    val A = x25519Field.create
    val B = x25519Field.create
    val C = x25519Field.create
    val D = x25519Field.create
    val E = r.u
    val F = x25519Field.create
    val G = x25519Field.create
    val H = r.v
    var c:Array[Int] = Array.empty
    var d:Array[Int] = Array.empty
    var f:Array[Int] = Array.empty
    var g:Array[Int] = Array.empty
    if (negate) {
      c = D
      d = C
      f = G
      g = F
    }
    else {
      c = C
      d = D
      f = F
      g = G
    }
    x25519Field.apm(r.y, r.x, B, A)
    x25519Field.apm(p.y, p.x, d, c)
    x25519Field.mul(A, C, A)
    x25519Field.mul(B, D, B)
    x25519Field.mul(r.u, r.v, C)
    x25519Field.mul(C, p.t, C)
    x25519Field.mul(C, C_d2, C)
    x25519Field.mul(r.z, p.z, D)
    x25519Field.add(D, D, D)
    x25519Field.apm(B, A, H, E)
    x25519Field.apm(D, C, g, f)
    x25519Field.carry(g)
    x25519Field.mul(E, F, r.x)
    x25519Field.mul(G, H, r.y)
    x25519Field.mul(F, G, r.z)
  }

  def pointAddVar(negate:Boolean, p:PointExt, q:PointExt, r:PointExt): Unit = {
    val A = x25519Field.create
    val B = x25519Field.create
    val C = x25519Field.create
    val D = x25519Field.create
    val E = x25519Field.create
    val F = x25519Field.create
    val G = x25519Field.create
    val H = x25519Field.create
    var c:Array[Int] = Array.empty
    var d:Array[Int] = Array.empty
    var f:Array[Int] = Array.empty
    var g:Array[Int] = Array.empty
    if (negate) {
      c = D
      d = C
      f = G
      g = F
    }
    else {
      c = C
      d = D
      f = F
      g = G
    }
    x25519Field.apm(p.y, p.x, B, A)
    x25519Field.apm(q.y, q.x, d, c)
    x25519Field.mul(A, C, A)
    x25519Field.mul(B, D, B)
    x25519Field.mul(p.t, q.t, C)
    x25519Field.mul(C, C_d2, C)
    x25519Field.mul(p.z, q.z, D)
    x25519Field.add(D, D, D)
    x25519Field.apm(B, A, H, E)
    x25519Field.apm(D, C, g, f)
    x25519Field.carry(g)
    x25519Field.mul(E, F, r.x)
    x25519Field.mul(G, H, r.y)
    x25519Field.mul(F, G, r.z)
    x25519Field.mul(E, H, r.t)
  }

  def pointAddPrecomp(p:PointPrecomp, r:PointAccum): Unit = {
    val A = x25519Field.create
    val B = x25519Field.create
    val C = x25519Field.create
    val E = r.u
    val F = x25519Field.create
    val G = x25519Field.create
    val H = r.v
    x25519Field.apm(r.y, r.x, B, A)
    x25519Field.mul(A, p.ymx_h, A)
    x25519Field.mul(B, p.ypx_h, B)
    x25519Field.mul(r.u, r.v, C)
    x25519Field.mul(C, p.xyd, C)
    x25519Field.apm(B, A, H, E)
    x25519Field.apm(r.z, C, G, F)
    x25519Field.carry(G)
    x25519Field.mul(E, F, r.x)
    x25519Field.mul(G, H, r.y)
    x25519Field.mul(F, G, r.z)
  }

  def pointCopy(p: PointAccum): PointExt = {
    val r = new PointExt
    x25519Field.copy(p.x, 0, r.x, 0)
    x25519Field.copy(p.y, 0, r.y, 0)
    x25519Field.copy(p.z, 0, r.z, 0)
    x25519Field.mul(p.u, p.v, r.t)
    r
  }

  def pointCopy(p: PointExt): PointExt = {
    val r = new PointExt
    x25519Field.copy(p.x, 0, r.x, 0)
    x25519Field.copy(p.y, 0, r.y, 0)
    x25519Field.copy(p.z, 0, r.z, 0)
    x25519Field.copy(p.t, 0, r.t, 0)
    r
  }

  def pointDouble(r: PointAccum): Unit = {
    val A = x25519Field.create
    val B = x25519Field.create
    val C = x25519Field.create
    val E = r.u
    val F = x25519Field.create
    val G = x25519Field.create
    val H = r.v
    x25519Field.sqr(r.x, A)
    x25519Field.sqr(r.y, B)
    x25519Field.sqr(r.z, C)
    x25519Field.add(C, C, C)
    x25519Field.apm(A, B, H, G)
    x25519Field.add(r.x, r.y, E)
    x25519Field.sqr(E, E)
    x25519Field.sub(H, E, E)
    x25519Field.add(C, G, F)
    x25519Field.carry(F)
    x25519Field.mul(E, F, r.x)
    x25519Field.mul(G, H, r.y)
    x25519Field.mul(F, G, r.z)
  }

  def pointExtendXY(p:PointAccum): Unit = {
    x25519Field.one(p.z)
    x25519Field.copy(p.x, 0, p.u, 0)
    x25519Field.copy(p.y, 0, p.v, 0)
  }

  def pointExtendXY(p:PointExt): Unit = {
    x25519Field.one(p.z)
    x25519Field.mul(p.x, p.y, p.t)
  }

  def pointLookup(block: Int, index: Int, p:PointPrecomp): Unit = {
    var off = block * PRECOMP_POINTS * 3 * x25519Field.SIZE
    for (i <- 0 until PRECOMP_POINTS) {
      val mask = ((i ^ index) - 1) >> 31
      cmov(x25519Field.SIZE, mask, precompBase, off, p.ypx_h, 0)
      off += x25519Field.SIZE
      cmov(x25519Field.SIZE, mask, precompBase, off, p.ymx_h, 0)
      off += x25519Field.SIZE
      cmov(x25519Field.SIZE, mask, precompBase, off, p.xyd, 0)
      off += x25519Field.SIZE
    }
  }

  def pointPrecompVar(p:PointExt, count: Int):Array[PointExt] = {
    val d = new PointExt
    pointAddVar(negate = false, p, p, d)
    val table = new Array[PointExt](count)
    table(0) = pointCopy(p)
    for (i <- 1 until count) {
      table(i) = new PointExt
      pointAddVar(negate = false, table(i - 1), d, table(i))
    }
    table
  }

  def pointSetNeutral(p:PointAccum): Unit = {
    x25519Field.zero(p.x)
    x25519Field.one(p.y)
    x25519Field.one(p.z)
    x25519Field.zero(p.u)
    x25519Field.one(p.v)
  }

  def pointSetNeutral(p:PointExt): Unit = {
    x25519Field.zero(p.x)
    x25519Field.one(p.y)
    x25519Field.one(p.z)
    x25519Field.zero(p.t)
  }

  def precompute(): Unit = precompLock match {
    case None =>
      precompLock = Some("Locked")
      if (precompBase.nonEmpty) return
      // Precomputed table for the base point in verification ladder
      val b = new PointExt
      x25519Field.copy(B_x, 0, b.x, 0)
      x25519Field.copy(B_y, 0, b.y, 0)
      pointExtendXY(b)
      precompBaseTable = pointPrecompVar(b, 1 << (WNAF_WIDTH_BASE - 2))
      val p = new PointAccum
      x25519Field.copy(B_x, 0, p.x, 0)
      x25519Field.copy(B_y, 0, p.y, 0)
      pointExtendXY(p)
      precompBase = new Array[Int](PRECOMP_BLOCKS * PRECOMP_POINTS * 3 * x25519Field.SIZE)
      var off = 0
      for (b <- 0 until PRECOMP_BLOCKS) {
        val ds = new Array[PointExt](PRECOMP_TEETH)
        val sum = new PointExt
        pointSetNeutral(sum)
        for (t <- 0 until PRECOMP_TEETH) {
          val q = pointCopy(p)
          pointAddVar(negate = true, sum, q, sum)
          pointDouble(p)
          ds(t) = pointCopy(p)
          if (b + t != PRECOMP_BLOCKS + PRECOMP_TEETH - 2) for (_ <- 1 until PRECOMP_SPACING) {
            pointDouble(p)
          }
        }
        val points = new Array[PointExt](PRECOMP_POINTS)
        var k = 0
        points({
          k += 1; k - 1
        }) = sum
        for (t <- 0 until (PRECOMP_TEETH - 1)) {
          val size = 1 << t
          var j = 0
          while (j < size) {
            points(k) = new PointExt
            pointAddVar(negate = false, points(k - size), ds(t), points(k))
            j += 1
            k += 1
          }
        }
        for (i <- 0 until PRECOMP_POINTS) {
          val q = points(i)
          val x = x25519Field.create
          val y = x25519Field.create
          x25519Field.add(q.z, q.z, x)
          x25519Field.inv(x, y)
          x25519Field.mul(q.x, y, x)
          x25519Field.mul(q.y, y, y)
          val r = new PointPrecomp
          x25519Field.apm(y, x, r.ypx_h, r.ymx_h)
          x25519Field.mul(x, y, r.xyd)
          x25519Field.mul(r.xyd, C_d4, r.xyd)
          x25519Field.normalize(r.ypx_h)
          x25519Field.normalize(r.ymx_h)
          x25519Field.copy(r.ypx_h, 0, precompBase, off)
          off += x25519Field.SIZE
          x25519Field.copy(r.ymx_h, 0, precompBase, off)
          off += x25519Field.SIZE
          x25519Field.copy(r.xyd, 0, precompBase, off)
          off += x25519Field.SIZE
        }
      }
    case _ =>
  }

  def pruneScalar(n: Array[Byte], nOff: Int, r: Array[Byte]): Unit = {
    System.arraycopy(n, nOff, r, 0, SCALAR_BYTES)
    r(0) = (r(0) & 0xF8).toByte
    r(SCALAR_BYTES - 1) = (r(SCALAR_BYTES - 1) & 0x7F).toByte
    r(SCALAR_BYTES - 1) = (r(SCALAR_BYTES - 1) | 0x40).toByte
  }

  def reduceScalar(n: Array[Byte]): Array[Byte] = {
    var x00 = decode32(n, 0) & M32L // x00:32/--
    var x01 = (decode24(n, 4) << 4) & M32L // x01:28/--
    var x02 = decode32(n, 7) & M32L // x02:32/--
    var x03 = (decode24(n, 11) << 4) & M32L // x03:28/--
    var x04 = decode32(n, 14) & M32L // x04:32/--
    var x05 = (decode24(n, 18) << 4) & M32L // x05:28/--
    var x06 = decode32(n, 21) & M32L // x06:32/--
    var x07 = (decode24(n, 25) << 4) & M32L // x07:28/--
    var x08 = decode32(n, 28) & M32L // x08:32/--
    var x09 = (decode24(n, 32) << 4) & M32L // x09:28/--
    var x10 = decode32(n, 35) & M32L // x10:32/--
    var x11 = (decode24(n, 39) << 4) & M32L // x11:28/--
    var x12 = decode32(n, 42) & M32L // x12:32/--
    var x13 = (decode24(n, 46) << 4) & M32L // x13:28/--
    var x14 = decode32(n, 49) & M32L // x14:32/--
    var x15 = (decode24(n, 53) << 4) & M32L // x15:28/--
    var x16 = decode32(n, 56) & M32L // x16:32/--
    var x17 = (decode24(n, 60) << 4) & M32L // x17:28/--
    val x18 = n(63) & 0xFFL // x18:08/--
    var t = 0L
    x09 -= x18 * L0 // x09:34/28
    x10 -= x18 * L1 // x10:33/30
    x11 -= x18 * L2 // x11:35/28
    x12 -= x18 * L3 // x12:32/31
    x13 -= x18 * L4 // x13:28/21
    x17 += (x16 >> 28)
    x16 &= M28L // x17:28/--, x16:28/--
    x08 -= x17 * L0 // x08:54/32
    x09 -= x17 * L1 // x09:52/51
    x10 -= x17 * L2 // x10:55/34
    x11 -= x17 * L3 // x11:51/36
    x12 -= x17 * L4 // x12:41/--
    x07 -= x16 * L0 // x07:54/28
    x08 -= x16 * L1 // x08:54/53
    x09 -= x16 * L2 // x09:55/53
    x10 -= x16 * L3 // x10:55/52
    x11 -= x16 * L4 // x11:51/41
    x15 += (x14 >> 28)
    x14 &= M28L // x15:28/--, x14:28/--
    x06 -= x15 * L0 // x06:54/32
    x07 -= x15 * L1 // x07:54/53
    x08 -= x15 * L2 // x08:56/--
    x09 -= x15 * L3 // x09:55/54
    x10 -= x15 * L4 // x10:55/53
    x05 -= x14 * L0 // x05:54/28
    x06 -= x14 * L1 // x06:54/53
    x07 -= x14 * L2 // x07:56/--
    x08 -= x14 * L3 // x08:56/51
    x09 -= x14 * L4 // x09:56/--
    x13 += (x12 >> 28)
    x12 &= M28L // x13:28/22, x12:28/--
    x04 -= x13 * L0 // x04:54/49
    x05 -= x13 * L1 // x05:54/53
    x06 -= x13 * L2 // x06:56/--
    x07 -= x13 * L3 // x07:56/52
    x08 -= x13 * L4 // x08:56/52
    x12 += (x11 >> 28)
    x11 &= M28L // x12:28/24, x11:28/--
    x03 -= x12 * L0 // x03:54/49
    x04 -= x12 * L1 // x04:54/51
    x05 -= x12 * L2 // x05:56/--
    x06 -= x12 * L3 // x06:56/52
    x07 -= x12 * L4 // x07:56/53
    x11 += (x10 >> 28)
    x10 &= M28L // x11:29/--, x10:28/--
    x02 -= x11 * L0 // x02:55/32
    x03 -= x11 * L1 // x03:55/--
    x04 -= x11 * L2 // x04:56/55
    x05 -= x11 * L3 // x05:56/52
    x06 -= x11 * L4 // x06:56/53
    x10 += (x09 >> 28)
    x09 &= M28L // x10:29/--, x09:28/--
    x01 -= x10 * L0 // x01:55/28
    x02 -= x10 * L1 // x02:55/54
    x03 -= x10 * L2 // x03:56/55
    x04 -= x10 * L3 // x04:57/--
    x05 -= x10 * L4 // x05:56/53
    x08 += (x07 >> 28)
    x07 &= M28L // x08:56/53, x07:28/--
    x09 += (x08 >> 28)
    x08 &= M28L // x09:29/25, x08:28/--
    t = x08 >>> 27
    x09 += t // x09:29/26
    x00 -= x09 * L0 // x00:55/53
    x01 -= x09 * L1 // x01:55/54
    x02 -= x09 * L2 // x02:57/--
    x03 -= x09 * L3 // x03:57/--
    x04 -= x09 * L4 // x04:57/42
    x01 += (x00 >> 28)
    x00 &= M28L
    x02 += (x01 >> 28)
    x01 &= M28L
    x03 += (x02 >> 28)
    x02 &= M28L
    x04 += (x03 >> 28)
    x03 &= M28L
    x05 += (x04 >> 28)
    x04 &= M28L
    x06 += (x05 >> 28)
    x05 &= M28L
    x07 += (x06 >> 28)
    x06 &= M28L
    x08 += (x07 >> 28)
    x07 &= M28L
    x09 = x08 >> 28
    x08 &= M28L
    x09 -= t
    x00 += x09 & L0
    x01 += x09 & L1
    x02 += x09 & L2
    x03 += x09 & L3
    x04 += x09 & L4
    x01 += (x00 >> 28)
    x00 &= M28L
    x02 += (x01 >> 28)
    x01 &= M28L
    x03 += (x02 >> 28)
    x02 &= M28L
    x04 += (x03 >> 28)
    x03 &= M28L
    x05 += (x04 >> 28)
    x04 &= M28L
    x06 += (x05 >> 28)
    x05 &= M28L
    x07 += (x06 >> 28)
    x06 &= M28L
    x08 += (x07 >> 28)
    x07 &= M28L
    val r = new Array[Byte](SCALAR_BYTES)
    encode56(x00 | (x01 << 28), r, 0)
    encode56(x02 | (x03 << 28), r, 7)
    encode56(x04 | (x05 << 28), r, 14)
    encode56(x06 | (x07 << 28), r, 21)
    encode32(x08.toInt, r, 28)
    r
  }

  def scalarMultBase(k:Array[Byte], r:PointAccum): Unit = {
    precompute()
    pointSetNeutral(r)
    val n = new Array[Int](SCALAR_INTS)
    decodeScalar(k, 0, n)
    // Recode the scalar into signed-digit form, then group comb bits in each block
    cadd(SCALAR_INTS, ~n(0) & 1, n, L, n)
    shiftDownBit(SCALAR_INTS, n, 1)
    for (i <- 0 until SCALAR_INTS) {
      n(i) = shuffle2(n(i))
    }
    val p = new PointPrecomp
    var cOff = (PRECOMP_SPACING - 1) * PRECOMP_TEETH
    breakable{
      while (true) {
        for (b <- 0 until PRECOMP_BLOCKS) {
          val w = n(b) >>> cOff
          val sign = (w >>> (PRECOMP_TEETH - 1)) & 1
          val abs = (w ^ -sign) & PRECOMP_MASK
          pointLookup(b, abs, p)
          x25519Field.cswap(sign, p.ypx_h, p.ymx_h)
          x25519Field.cnegate(sign, p.xyd)
          pointAddPrecomp(p, r)
        }
        if ({cOff -= PRECOMP_TEETH;cOff} < 0) break
        pointDouble(r)
      }
    }
  }

  def scalarMultBaseEncoded(k: Array[Byte], r: Array[Byte], rOff: Int): Unit = {
    val p = new PointAccum
    scalarMultBase(k, p)
    encodePoint(p, r, rOff)
  }

  def scalarMultStraussVar(nb: Array[Int], np: Array[Int], p:PointExt, r:PointAccum): Unit = {
    precompute()
    val width = 5
    val ws_b = getWNAF(nb, WNAF_WIDTH_BASE)
    val ws_p = getWNAF(np, width)
    val tp = pointPrecompVar(p, 1 << (width - 2))
    pointSetNeutral(r)
    var bit = 255
    while (bit > 0 && (ws_b(bit) | ws_p(bit)) == 0) bit -= 1
    breakable{
      while (true) {
        val wb = ws_b(bit)
        if (wb != 0) {
          val sign = wb >> 31
          val index = (wb ^ sign) >>> 1
          pointAddVar(sign != 0, precompBaseTable(index), r)
        }
        val wp = ws_p(bit)
        if (wp != 0) {
          val sign = wp >> 31
          val index = (wp ^ sign) >>> 1
          pointAddVar(sign != 0, tp(index), r)
        }
        if ({bit -= 1; bit} < 0) break
        pointDouble(r)
      }
    }
  }

  /**
  def prnt(input:Array[Byte]):Unit = {
    def bytesToHex(bytes: Array[Byte]): String = {
      val HEX_ARRAY = "0123456789abcdef".toCharArray
      val hexChars = new Array[Char](bytes.length * 2)
      for (j <- bytes.indices) {
        val v = bytes(j) & 0xFF
        hexChars(j * 2) = HEX_ARRAY(v >>> 4)
        hexChars(j * 2 + 1) = HEX_ARRAY(v & 0x0F)
      }
      new String(hexChars)
    }
    println(bytesToHex(input))
  }

  def prnt(input:Array[Int]):Unit = {
    println(input.mkString("[", ", ", "]"))
  }

  def printPoint(pointExt: PointExt): Unit = {
    println("pExt:")
    println(pointExt.x.mkString("[", ", ", "]"))
    println(pointExt.y.mkString("[", ", ", "]"))
    println(pointExt.z.mkString("[", ", ", "]"))
    println(pointExt.t.mkString("[", ", ", "]"))
  }

  def printPoint(pointAccum: PointAccum): Unit = {
    println("pAccum:")
    println(pointAccum.x.mkString("[", ", ", "]"))
    println(pointAccum.y.mkString("[", ", ", "]"))
    println(pointAccum.z.mkString("[", ", ", "]"))
    println(pointAccum.u.mkString("[", ", ", "]"))
    println(pointAccum.v.mkString("[", ", ", "]"))
  }

  def printPoint(pointPrecomp: PointPrecomp): Unit = {
    println("pAccum:")
    println(pointPrecomp.ypx_h.mkString("[", ", ", "]"))
    println(pointPrecomp.ymx_h.mkString("[", ", ", "]"))
    println(pointPrecomp.xyd.mkString("[", ", ", "]"))
  }
  **/

}
