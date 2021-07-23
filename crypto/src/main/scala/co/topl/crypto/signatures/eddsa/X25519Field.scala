package co.topl.crypto.signatures.eddsa

/**
  * AMS 2021:
  * Curve point operations ported from BouncyCastle used in all EC primitives in crypto.primitives.eddsa
  * Licensing: https://www.bouncycastle.org/licence.html
  * Copyright (c) 2000 - 2021 The Legion of the Bouncy Castle Inc. (https://www.bouncycastle.org)
  * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
  * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
  * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
  */

class X25519Field {

  val SIZE = 10
  val M24 = 0x00FFFFFF
  val M25 = 0x01FFFFFF
  val M26 = 0x03FFFFFF
  val ROOT_NEG_ONE: Array[Int] = Array[Int](0x020EA0B0, 0x0386C9D2, 0x00478C4E, 0x0035697F, 0x005E8630, 0x01FBD7A7, 0x0340264F, 0x01F0B2B4, 0x00027E0E, 0x00570649)

  def add(x: Array[Int], y: Array[Int], z: Array[Int]): Unit = {
    for (i <- 0 until SIZE) {
      z(i) = x(i) + y(i)
    }
  }

  def addOne(z: Array[Int]): Unit = {
    z(0) += 1
  }

  def addOne(z: Array[Int], zOff: Int): Unit = {
    z(zOff) += 1
  }

  def apm(x: Array[Int], y: Array[Int], zp: Array[Int], zm: Array[Int]): Unit = {
    for (i <- 0 until SIZE) {
      val xi = x(i)
      val yi = y(i)
      zp(i) = xi + yi
      zm(i) = xi - yi
    }
  }

  def carry(z: Array[Int]): Unit = {
    var z0 = z(0)
    var z1 = z(1)
    var z2 = z(2)
    var z3 = z(3)
    var z4 = z(4)
    var z5 = z(5)
    var z6 = z(6)
    var z7 = z(7)
    var z8 = z(8)
    var z9 = z(9)
    z3 += (z2 >> 25)
    z2 &= M25
    z5 += (z4 >> 25)
    z4 &= M25
    z8 += (z7 >> 25)
    z7 &= M25
    z0 += (z9 >> 25) * 38
    z9 &= M25
    z1 += (z0 >> 26)
    z0 &= M26
    z6 += (z5 >> 26)
    z5 &= M26
    z2 += (z1 >> 26)
    z1 &= M26
    z4 += (z3 >> 26)
    z3 &= M26
    z7 += (z6 >> 26)
    z6 &= M26
    z9 += (z8 >> 26)
    z8 &= M26
    z(0) = z0
    z(1) = z1
    z(2) = z2
    z(3) = z3
    z(4) = z4
    z(5) = z5
    z(6) = z6
    z(7) = z7
    z(8) = z8
    z(9) = z9
  }

  def cmov(cond: Int, x: Array[Int], xOff: Int, z: Array[Int], zOff: Int): Unit = {
    for (i <- 0 until SIZE) {
      var z_i = z(zOff + i)
      val diff = z_i ^ x(xOff + i)
      z_i ^= (diff & cond)
      z(zOff + i) = z_i
    }
  }

  def cnegate(negate: Int, z: Array[Int]): Unit = {
    val mask = 0 - negate
    for (i <- 0 until SIZE) {
      z(i) = (z(i) ^ mask) - mask
    }
  }

  def copy(x: Array[Int], xOff: Int, z: Array[Int], zOff: Int): Unit = {
    for (i <- 0 until SIZE) {
      z(zOff + i) = x(xOff + i)
    }
  }

  def create = new Array[Int](SIZE)

  def createTable(n: Int) = new Array[Int](SIZE * n)

  def cswap(swap: Int, a: Array[Int], b: Array[Int]): Unit = {
    val mask = 0 - swap
    for (i <- 0 until SIZE) {
      val ai = a(i)
      val bi = b(i)
      val dummy = mask & (ai ^ bi)
      a(i) = ai ^ dummy
      b(i) = bi ^ dummy
    }
  }

  def decode(x: Array[Byte], xOff: Int, z: Array[Int]): Unit = {
    decode128(x, xOff, z, 0)
    decode128(x, xOff + 16, z, 5)
    z(9) &= M24
  }

  def decode128(bs: Array[Byte], off: Int, z: Array[Int], zOff: Int): Unit = {
    val t0 = decode32(bs, off + 0)
    val t1 = decode32(bs, off + 4)
    val t2 = decode32(bs, off + 8)
    val t3 = decode32(bs, off + 12)
    z(zOff + 0) = t0 & M26
    z(zOff + 1) = ((t1 << 6) | (t0 >>> 26)) & M26
    z(zOff + 2) = ((t2 << 12) | (t1 >>> 20)) & M25
    z(zOff + 3) = ((t3 << 19) | (t2 >>> 13)) & M26
    z(zOff + 4) = t3 >>> 7
  }

  def decode32(bs: Array[Byte], off: Int):Int = {
    var n = bs(off) & 0xFF
    n |= (bs(off+1) & 0xFF) << 8
    n |= (bs(off+2) & 0xFF) << 16
    n |= bs(off+3) << 24
    n
  }

  def encode(x: Array[Int], z: Array[Byte], zOff: Int): Unit = {
    encode128(x, 0, z, zOff)
    encode128(x, 5, z, zOff + 16)
  }

  def encode128(x: Array[Int], xOff: Int, bs: Array[Byte], off: Int): Unit = {
    val x0 = x(xOff + 0)
    val x1 = x(xOff + 1)
    val x2 = x(xOff + 2)
    val x3 = x(xOff + 3)
    val x4 = x(xOff + 4)
    val t0 = x0 | (x1 << 26)
    encode32(t0, bs, off + 0)
    val t1 = (x1 >>> 6) | (x2 << 20)
    encode32(t1, bs, off + 4)
    val t2 = (x2 >>> 12) | (x3 << 13)
    encode32(t2, bs, off + 8)
    val t3 = (x3 >>> 19) | (x4 << 7)
    encode32(t3, bs, off + 12)
  }

  def encode32(n: Int, bs: Array[Byte], off: Int): Unit = {
    bs(off) = n.toByte
    bs(off+1) = (n >>> 8).toByte
    bs(off+2) = (n >>> 16).toByte
    bs(off+3) = (n >>> 24).toByte
  }

  def inv(x: Array[Int], z: Array[Int]): Unit = {
    // (250 1s) (1 0s) (1 1s) (1 0s) (2 1s)
    // Addition chain: [1] [2] 3 5 10 15 25 50 75 125 [250]
    val x2 = create
    val t = create
    powPm5d8(x, x2, t)
    sqr(t, 3, t)
    mul(t, x2, z)
  }

  def isZero(x: Array[Int]): Int = {
    var d = 0
    for (i <- 0 until SIZE) {
      d |= x(i)
    }
    d = (d >>> 1) | (d & 1)
    (d - 1) >> 31
  }

  def isZeroVar(x: Array[Int]): Boolean = 0 != isZero(x)

  def mul(x: Array[Int], y: Int, z: Array[Int]): Unit = {
    val x0 = x(0)
    val x1 = x(1)
    var x2 = x(2)
    val x3 = x(3)
    var x4 = x(4)
    val x5 = x(5)
    val x6 = x(6)
    var x7 = x(7)
    val x8 = x(8)
    var x9 = x(9)
    var c0 = 0L
    var c1 = 0L
    var c2 = 0L
    var c3 = 0L
    c0 = x2.toLong * y
    x2 = c0.toInt & M25
    c0 >>= 25
    c1 = x4.toLong * y
    x4 = c1.toInt & M25
    c1 >>= 25
    c2 = x7.toLong * y
    x7 = c2.toInt & M25
    c2 >>= 25
    c3 = x9.toLong * y
    x9 = c3.toInt & M25
    c3 >>= 25
    c3 *= 38
    c3 += x0.toLong * y
    z(0) = c3.toInt & M26
    c3 >>= 26
    c1 += x5.toLong * y
    z(5) = c1.toInt & M26
    c1 >>= 26
    c3 += x1.toLong * y
    z(1) = c3.toInt & M26
    c3 >>= 26
    c0 += x3.toLong * y
    z(3) = c0.toInt & M26
    c0 >>= 26
    c1 += x6.toLong * y
    z(6) = c1.toInt & M26
    c1 >>= 26
    c2 += x8.toLong * y
    z(8) = c2.toInt & M26
    c2 >>= 26
    z(2) = x2 + c3.toInt
    z(4) = x4 + c0.toInt
    z(7) = x7 + c1.toInt
    z(9) = x9 + c2.toInt
  }

  def mul(x: Array[Int], y: Array[Int], z: Array[Int]): Unit = {
    var x0 = x(0)
    var y0 = y(0)
    var x1 = x(1)
    var y1 = y(1)
    var x2 = x(2)
    var y2 = y(2)
    var x3 = x(3)
    var y3 = y(3)
    var x4 = x(4)
    var y4 = y(4)
    val u0 = x(5)
    val v0 = y(5)
    val u1 = x(6)
    val v1 = y(6)
    val u2 = x(7)
    val v2 = y(7)
    val u3 = x(8)
    val v3 = y(8)
    val u4 = x(9)
    val v4 = y(9)
    var a0 = x0.toLong * y0
    var a1 = x0.toLong * y1 + x1.toLong * y0
    var a2 = x0.toLong * y2 + x1.toLong * y1 + x2.toLong * y0
    var a3 = x1.toLong * y2 + x2.toLong * y1
    a3 <<= 1
    a3 += x0.toLong * y3 + x3.toLong * y0
    var a4 = x2.toLong * y2
    a4 <<= 1
    a4 += x0.toLong * y4 + x1.toLong * y3 + x3.toLong * y1 + x4.toLong * y0
    var a5 = x1.toLong * y4 + x2.toLong * y3 + x3.toLong * y2 + x4.toLong * y1
    a5 <<= 1
    var a6 = x2.toLong * y4 + x4.toLong * y2
    a6 <<= 1
    a6 += x3.toLong * y3
    var a7 = x3.toLong * y4 + x4.toLong * y3
    var a8 = x4.toLong * y4
    a8 <<= 1
    val b0 = u0.toLong * v0
    val b1 = u0.toLong * v1 + u1.toLong * v0
    val b2 = u0.toLong * v2 + u1.toLong * v1 + u2.toLong * v0
    var b3 = u1.toLong * v2 + u2.toLong * v1
    b3 <<= 1
    b3 += u0.toLong * v3 + u3.toLong * v0
    var b4 = u2.toLong * v2
    b4 <<= 1
    b4 += u0.toLong * v4 + u1.toLong * v3 + u3.toLong * v1 + u4.toLong * v0
    val b5 = u1.toLong * v4 + u2.toLong * v3 + u3.toLong * v2 + u4.toLong * v1
    var b6 = u2.toLong * v4 + u4.toLong * v2
    b6 <<= 1
    b6 += u3.toLong * v3
    val b7 = u3.toLong * v4 + u4.toLong * v3
    val b8 = u4.toLong * v4
    a0 -= b5 * 76
    a1 -= b6 * 38
    a2 -= b7 * 38
    a3 -= b8 * 76
    a5 -= b0
    a6 -= b1
    a7 -= b2
    a8 -= b3
    x0 += u0
    y0 += v0
    x1 += u1
    y1 += v1
    x2 += u2
    y2 += v2
    x3 += u3
    y3 += v3
    x4 += u4
    y4 += v4
    val c0 = x0.toLong * y0
    val c1 = x0.toLong * y1 + x1.toLong * y0
    val c2 = x0.toLong * y2 + x1.toLong * y1 + x2.toLong * y0
    var c3 = x1.toLong * y2 + x2.toLong * y1
    c3 <<= 1
    c3 += x0.toLong * y3 + x3.toLong * y0
    var c4 = x2.toLong * y2
    c4 <<= 1
    c4 += x0.toLong * y4 + x1.toLong * y3 + x3.toLong * y1 + x4.toLong * y0
    var c5 = x1.toLong * y4 + x2.toLong * y3 + x3.toLong * y2 + x4.toLong * y1
    c5 <<= 1
    var c6 = x2.toLong * y4 + x4.toLong * y2
    c6 <<= 1
    c6 += x3.toLong * y3
    val c7 = x3.toLong * y4 + x4.toLong * y3
    var c8 = x4.toLong * y4
    c8 <<= 1
    var z8 = 0
    var z9 = 0
    var t = 0L
    t = a8 + (c3 - a3)
    z8 = t.toInt & M26
    t >>= 26
    t += (c4 - a4) - b4
    z9 = t.toInt & M25
    t >>= 25
    t = a0 + (t + c5 - a5) * 38
    z(0) = t.toInt & M26
    t >>= 26
    t += a1 + (c6 - a6) * 38
    z(1) = t.toInt & M26
    t >>= 26
    t += a2 + (c7 - a7) * 38
    z(2) = t.toInt & M25
    t >>= 25
    t += a3 + (c8 - a8) * 38
    z(3) = t.toInt & M26
    t >>= 26
    t += a4 + b4 * 38
    z(4) = t.toInt & M25
    t >>= 25
    t += a5 + (c0 - a0)
    z(5) = t.toInt & M26
    t >>= 26
    t += a6 + (c1 - a1)
    z(6) = t.toInt & M26
    t >>= 26
    t += a7 + (c2 - a2)
    z(7) = t.toInt & M25
    t >>= 25
    t += z8
    z(8) = t.toInt & M26
    t >>= 26
    z(9) = z9 + t.toInt
  }

  def negate(x: Array[Int], z: Array[Int]): Unit = {
    for (i <- 0 until SIZE) {
      z(i) = -x(i)
    }
  }

  def normalize(z: Array[Int]): Unit = {
    val x = (z(9) >>> 23) & 1
    reduce(z, x)
    reduce(z, -x)
  }

  def one(z: Array[Int]): Unit = {
    z(0) = 1
    for (i <- 1 until SIZE) {
      z(i) = 0
    }
  }

  def powPm5d8(x: Array[Int], rx2: Array[Int], rz: Array[Int]): Unit = {
    // (250 1s) (1 0s) (1 1s)
    // Addition chain: [1] 2 3 5 10 15 25 50 75 125 [250]
    val x2 = rx2
    sqr(x, x2)
    mul(x, x2, x2)
    val x3 = create
    sqr(x2, x3)
    mul(x, x3, x3)
    val x5 = x3
    sqr(x3, 2, x5)
    mul(x2, x5, x5)
    val x10 = create
    sqr(x5, 5, x10)
    mul(x5, x10, x10)
    val x15 = create
    sqr(x10, 5, x15)
    mul(x5, x15, x15)
    val x25 = x5
    sqr(x15, 10, x25)
    mul(x10, x25, x25)
    val x50 = x10
    sqr(x25, 25, x50)
    mul(x25, x50, x50)
    val x75 = x15
    sqr(x50, 25, x75)
    mul(x25, x75, x75)
    val x125 = x25
    sqr(x75, 50, x125)
    mul(x50, x125, x125)
    val x250 = x50
    sqr(x125, 125, x250)
    mul(x125, x250, x250)
    val t = x125
    sqr(x250, 2, t)
    mul(t, x, rz)
  }

  def reduce(z: Array[Int], c: Int): Unit = {
    var z9 = z(9)
    var t = z9
    z9 = t & M24
    t >>= 24
    t += c
    t *= 19
    t += z(0)
    z(0) = t & M26
    t >>= 26
    t += z(1)
    z(1) = t & M26
    t >>= 26
    t += z(2)
    z(2) = t & M25
    t >>= 25
    t += z(3)
    z(3) = t & M26
    t >>= 26
    t += z(4)
    z(4) = t & M25
    t >>= 25
    t += z(5)
    z(5) = t & M26
    t >>= 26
    t += z(6)
    z(6) = t & M26
    t >>= 26
    t += z(7)
    z(7) = t & M25
    t >>= 25
    t += z(8)
    z(8) = t & M26
    t >>= 26
    t += z9
    z(9) = t
  }

  def sqr(x: Array[Int], z: Array[Int]): Unit = {
    var x0 = x(0)
    var x1 = x(1)
    var x2 = x(2)
    var x3 = x(3)
    var x4 = x(4)
    val u0 = x(5)
    val u1 = x(6)
    val u2 = x(7)
    val u3 = x(8)
    val u4 = x(9)
    var x1_2 = x1 * 2
    var x2_2 = x2 * 2
    var x3_2 = x3 * 2
    var x4_2 = x4 * 2
    var a0 = x0.toLong * x0
    var a1 = x0.toLong * x1_2
    var a2 = x0.toLong * x2_2 + x1.toLong * x1
    var a3 = x1_2.toLong * x2_2 + x0.toLong * x3_2
    val a4 = x2.toLong * x2_2 + x0.toLong * x4_2 + x1.toLong * x3_2
    var a5 = x1_2.toLong * x4_2 + x2_2.toLong * x3_2
    var a6 = x2_2.toLong * x4_2 + x3.toLong * x3
    var a7 = x3.toLong * x4_2
    var a8 = x4.toLong * x4_2
    val u1_2 = u1 * 2
    val u2_2 = u2 * 2
    val u3_2 = u3 * 2
    val u4_2 = u4 * 2
    val b0 = u0.toLong * u0
    val b1 = u0.toLong * u1_2
    val b2 = u0.toLong * u2_2 + u1.toLong * u1
    val b3 = u1_2.toLong * u2_2 + u0.toLong * u3_2
    val b4 = u2.toLong * u2_2 + u0.toLong * u4_2 + u1.toLong * u3_2
    val b5 = u1_2.toLong * u4_2 + u2_2.toLong * u3_2
    val b6 = u2_2.toLong * u4_2 + u3.toLong * u3
    val b7 = u3.toLong * u4_2
    val b8 = u4.toLong * u4_2
    a0 -= b5 * 38
    a1 -= b6 * 38
    a2 -= b7 * 38
    a3 -= b8 * 38
    a5 -= b0
    a6 -= b1
    a7 -= b2
    a8 -= b3
    x0 += u0
    x1 += u1
    x2 += u2
    x3 += u3
    x4 += u4
    x1_2 = x1 * 2
    x2_2 = x2 * 2
    x3_2 = x3 * 2
    x4_2 = x4 * 2
    val c0 = x0.toLong * x0
    val c1 = x0.toLong * x1_2
    val c2 = x0.toLong * x2_2 + x1.toLong * x1
    val c3 = x1_2.toLong * x2_2 + x0.toLong * x3_2
    val c4 = x2.toLong * x2_2 + x0.toLong * x4_2 + x1.toLong * x3_2
    val c5 = x1_2.toLong * x4_2 + x2_2.toLong * x3_2
    val c6 = x2_2.toLong * x4_2 + x3.toLong * x3
    val c7 = x3.toLong * x4_2
    val c8 = x4.toLong * x4_2
    var z8 = 0
    var z9 = 0
    var t = 0L
    t = a8 + (c3 - a3)
    z8 = t.toInt & M26
    t >>= 26
    t += (c4 - a4) - b4
    z9 = t.toInt & M25
    t >>= 25
    t = a0 + (t + c5 - a5) * 38
    z(0) = t.toInt & M26
    t >>= 26
    t += a1 + (c6 - a6) * 38
    z(1) = t.toInt & M26
    t >>= 26
    t += a2 + (c7 - a7) * 38
    z(2) = t.toInt & M25
    t >>= 25
    t += a3 + (c8 - a8) * 38
    z(3) = t.toInt & M26
    t >>= 26
    t += a4 + b4 * 38
    z(4) = t.toInt & M25
    t >>= 25
    t += a5 + (c0 - a0)
    z(5) = t.toInt & M26
    t >>= 26
    t += a6 + (c1 - a1)
    z(6) = t.toInt & M26
    t >>= 26
    t += a7 + (c2 - a2)
    z(7) = t.toInt & M25
    t >>= 25
    t += z8
    z(8) = t.toInt & M26
    t >>= 26
    z(9) = z9 + t.toInt
  }

  def sqr(x: Array[Int], n: Int, z: Array[Int]): Unit = {
    var nv = n
    sqr(x, z)
    while ({nv -= 1; nv} > 0) sqr(z, z)
  }

  def sqrtRatioVar(u: Array[Int], v: Array[Int], z: Array[Int]): Boolean = {
    val uv3 = create
    val uv7 = create
    mul(u, v, uv3)
    sqr(v, uv7)
    mul(uv3, uv7, uv3)
    sqr(uv7, uv7)
    mul(uv7, uv3, uv7)
    val t = create
    val x = create
    powPm5d8(uv7, t, x)
    mul(x, uv3, x)
    val vx2 = create
    sqr(x, vx2)
    mul(vx2, v, vx2)
    sub(vx2, u, t)
    normalize(t)
    if (isZeroVar(t)) {
      copy(x, 0, z, 0)
      return true
    }
    add(vx2, u, t)
    normalize(t)
    if (isZeroVar(t)) {
      mul(x, ROOT_NEG_ONE, z)
      return true
    }
    false
  }

  def sub(x: Array[Int], y: Array[Int], z: Array[Int]): Unit = {
    for (i <- 0 until SIZE) {
      z(i) = x(i) - y(i)
    }
  }

  def subOne(z: Array[Int]): Unit = {
    z(0) -= 1
  }

  def zero(z: Array[Int]): Unit = {
    for (i <- 0 until SIZE) {
      z(i) = 0
    }
  }

}
