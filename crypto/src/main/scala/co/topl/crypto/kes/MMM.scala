package co.topl.crypto.kes

import scala.math.BigInt

/**
  * AMS 2021:
  * Implementation of the MMM construction:
  * Malkin, T., Micciancio, D. and Miner, S. (2002) ‘Efficient generic
  * forward-secure signatures with an unbounded number of time
  * periods’, Advances in Cryptology Eurocrypt ’02, LNCS 2332,
  * Springer, pp.400–417.
  *
  * Provides forward secure signatures that cannot be reforged with a leaked private key that has been updated
  * Number of time steps is determined by logl argument upon key generation, practically unbounded for logl = 7
  * Sum compostion is based on underlying signing routine
  */

abstract class MMM {

  val fch:Fch
  val sig:Sig
  val seedBytes:Int
  val pkBytes:Int
  val skBytes:Int
  val sigBytes:Int
  val hashBytes:Int
  val mmmPkLength:Int = hashBytes

  val logl = 7

  type KesKeyBytes = (Tree[Array[Byte]],Tree[Array[Byte]],Array[Byte],Array[Byte],Array[Byte])
  type KesSignature = (Array[Byte],Array[Byte],Array[Byte])

  /**
    * Exponent base two of the argument
    * @param n integer
    * @return 2 to the n
    */
  private def exp(n: Int): Int = {
    scala.math.pow(2,n).toInt
  }

  /**
    * Pseudorandom number generator used for seed doubling
    * Input must be non-recoverable from output
    * Each output cannot be used to determine one from the other
    * @param k input seed
    * @return tuple of two new seeds
    */

  private def PRNG(k: Array[Byte]): (Array[Byte],Array[Byte]) = {
    val r1 = fch.hash(k)
    val r2 = fch.hash(r1++k)
    (r1,r2)
  }

  /**
    * generates a keypair for underlying SIG functionality returns it in a single byte array
    * @param seed input entropy for keypair generation
    * @return byte array sk||pk
    */
  private def sKeypairFast(seed: Array[Byte]): Array[Byte] = {
    val sk = fch.hash(seed)
    val pk = Array.fill(32){0x00.toByte}
    sig.generatePublicKey(sk,0,pk,0)
    sk++pk
  }

  /**
    * Signing routine for underlying SIG functionality
    * @param m message to be signed
    * @param sk SIG secret key to be signed
    * @return SIG signature
    */
  private def sSign(m: Array[Byte], sk: Array[Byte]): Array[Byte] = {
    val signature: Array[Byte] = Array.fill(sigBytes){0x00.toByte}
    sig.sign(sk,0,m,0,m.length,signature,0)
    signature
  }

  /**
    * Verify routine for underlying SIG functionality
    * @param m message for given signature
    * @param signature signature to be verified
    * @param pk public key corresponding to signature
    * @return true if valid signature, false if otherwise
    */
  private def sVerify(m: Array[Byte], signature: Array[Byte], pk: Array[Byte]): Boolean = {
    sig.verify(signature,0,pk,0,m,0,m.length)
  }

  /**
    * Gets the public key in the sum composition
    * @param t binary tree for which the key is to be calculated
    * @return binary array public key
    */
  private def sumGetPublicKey(t: Tree[Array[Byte]]): Array[Byte] = {
    t match {
      case n: Node[Array[Byte]] =>
        val pk0 = n.v.slice(seedBytes, seedBytes + pkBytes)
        val pk1 = n.v.slice(seedBytes + pkBytes, seedBytes + 2 * pkBytes)
        fch.hash(pk0 ++ pk1)
      case l: Leaf[Array[Byte]] =>
        fch.hash(fch.hash(l.v.slice(seedBytes, seedBytes + pkBytes))++fch.hash(l.v.slice(seedBytes, seedBytes + pkBytes)))
      case _ => Array()
    }
  }

  /**
    * Generates keys in the sum composition, recursive functions construct the tree in steps and the output is
    * the leftmost branch
    * @param seed input entropy for binary tree and keypair generation
    * @param i height of tree
    * @return binary tree at time step 0
    */
  private def sumGenerateKey(seed: Array[Byte],i:Int):Tree[Array[Byte]] = {

    // generate the binary tree with the pseudorandom number generator
    def sumKeyGenMerkle(seed: Array[Byte],i:Int): Tree[Array[Byte]] = {
      if (i==0){
        Leaf(seed)
      } else {
        val r = PRNG(seed)
        Node(r._2,sumKeyGenMerkle(r._1,i-1),sumKeyGenMerkle(r._2,i-1))
      }
    }

    // generates the SIG keypairs on each leaf
    def populateLeaf(t: Tree[Array[Byte]]): Tree[Array[Byte]] = {
      t match {
        case n: Node[Array[Byte]] =>
          Node(n.v,populateLeaf(n.l),populateLeaf(n.r))
        case l: Leaf[Array[Byte]] =>
          Leaf(l.v++sKeypairFast(l.v))
        case _ =>
          Empty
      }
    }

    // generates the Merkle tree of the public keys and stores the hash values on each node
    def merklePublicKeys(t: Tree[Array[Byte]]): Tree[Array[Byte]] = {
      def loop(t: Tree[Array[Byte]]): Tree[Array[Byte]] = {
        t match {
          case n: Node[Array[Byte]] =>
            var sk0: Array[Byte] = Array()
            var pk0: Array[Byte] = Array()
            var pk00: Array[Byte] = Array()
            var pk01: Array[Byte] = Array()
            var sk1: Array[Byte] = Array()
            var pk1: Array[Byte] = Array()
            var pk10: Array[Byte] = Array()
            var pk11: Array[Byte] = Array()
            var r0: Array[Byte] = Array()
            var r1: Array[Byte] = Array()
            var leftVal: Array[Byte] = Array()
            var rightVal: Array[Byte] = Array()
            var leafLevel = false
            val left = loop(n.l) match {
              case nn: Node[Array[Byte]] =>
                leftVal = nn.v
                nn
              case ll: Leaf[Array[Byte]] =>
                leafLevel = true
                leftVal = ll.v
                ll
            }
            val right = loop(n.r) match {
              case nn: Node[Array[Byte]] =>
                rightVal = nn.v
                nn
              case ll: Leaf[Array[Byte]] =>
                leafLevel = true
                rightVal = ll.v
                ll
            }
            if (leafLevel) {
              r0 = leftVal.slice(0, seedBytes)
              sk0 = leftVal.slice(seedBytes, seedBytes + skBytes)
              pk0 = leftVal.slice(seedBytes + skBytes, seedBytes + skBytes + pkBytes)
              r1 = rightVal.slice(0, seedBytes)
              sk1 = rightVal.slice(seedBytes, seedBytes + skBytes)
              pk1 = rightVal.slice(seedBytes + skBytes, seedBytes + skBytes + pkBytes)
              assert(n.v sameElements r1)
              Node(n.v ++ fch.hash(pk0) ++ fch.hash(pk1), Leaf(sk0 ++ pk0), Leaf(sk1 ++ pk1))
            } else {
              pk00 = leftVal.slice(seedBytes, seedBytes + pkBytes)
              pk01 = leftVal.slice(seedBytes + pkBytes, seedBytes + 2 * pkBytes)
              pk10 = rightVal.slice(seedBytes, seedBytes + pkBytes)
              pk11 = rightVal.slice(seedBytes + pkBytes, seedBytes + 2 * pkBytes)
              pk0 = fch.hash(pk00 ++ pk01)
              pk1 = fch.hash(pk10 ++ pk11)
              Node(n.v ++ pk0 ++ pk1, left, right)
            }
          case l: Leaf[Array[Byte]] =>
            l
          case _ =>
            Empty
        }
      }
      t match {
        case n: Node[Array[Byte]] =>
          loop(n)
        case l: Leaf[Array[Byte]] =>
          Leaf(l.v.drop(seedBytes))
        case _ =>
          Empty
      }
    }

    //removes all but the leftmost branch leaving the leftmost leaf
    def trimTree(t: Tree[Array[Byte]]): Tree[Array[Byte]] = {
      t match {
        case n: Node[Array[Byte]] =>
          Node(n.v,trimTree(n.l),Empty)
        case l: Leaf[Array[Byte]] =>
          l
        case _ =>
          Empty
      }
    }

    //executes the above functions in order
    trimTree(merklePublicKeys(populateLeaf(sumKeyGenMerkle(seed,i))))
  }

  /**
    * Verify a public key with a binary tree
    * @param t binary tree that contains Merkle tree hash values
    * @param pk root of the Merkle tree
    * @return true if pk is the root of the Merkle tree, false if otherwise
    */
  private def sumVerifyKeyPair(t: Tree[Array[Byte]], pk:Array[Byte]): Boolean = {
    //loops through the tree to verify Merkle witness path
    def loop(t: Tree[Array[Byte]]): Boolean = {
      t match {
        case n: Node[Array[Byte]] =>
          var pk0:Array[Byte] = Array()
          var pk00:Array[Byte] = Array()
          var pk01:Array[Byte] = Array()
          var pk1:Array[Byte] = Array()
          var pk10:Array[Byte] = Array()
          var pk11:Array[Byte] = Array()
          val left = n.l match {
            case nn: Node[Array[Byte]] =>
              pk00 = nn.v.slice(seedBytes,seedBytes+pkBytes)
              pk01 = nn.v.slice(seedBytes+pkBytes,seedBytes+2*pkBytes)
              pk0 = fch.hash(pk00++pk01)
              loop(nn) && (pk0 sameElements n.v.slice(seedBytes,seedBytes+pkBytes))
            case ll: Leaf[Array[Byte]] =>
              fch.hash(ll.v.slice(skBytes,skBytes+pkBytes)) sameElements n.v.slice(seedBytes,seedBytes+pkBytes)
            case _ => true
          }
          val right = n.r match {
            case nn: Node[Array[Byte]] =>
              pk10 = nn.v.slice(seedBytes,seedBytes+pkBytes)
              pk11 = nn.v.slice(seedBytes+pkBytes,seedBytes+2*pkBytes)
              pk1 = fch.hash(pk10++pk11)
              loop(nn) && (pk1 sameElements n.v.slice(seedBytes+pkBytes,seedBytes+2*pkBytes))
            case ll: Leaf[Array[Byte]] =>
              fch.hash(ll.v.slice(skBytes,skBytes+pkBytes)) sameElements n.v.slice(seedBytes+pkBytes,seedBytes+2*pkBytes)
            case _ => true
          }
          left && right
        case l: Leaf[Array[Byte]] =>
          fch.hash(fch.hash(l.v.slice(skBytes,skBytes+pkBytes))++fch.hash(l.v.slice(skBytes,skBytes+pkBytes))) sameElements pk
        case _ => false
      }
    }
    (pk sameElements sumGetPublicKey(t)) && loop(t)
  }

  /**
    * Updates the key in the sum composition
    * @param key binary tree to be updated
    * @param t time step key is to be updated to
    * @return updated key to be written to key
    */
  private def sumUpdate(key: Tree[Array[Byte]],t:Int): Tree[Array[Byte]] = {
    //checks if the sub tree is right most
    def isRightBranch(t: Tree[Array[Byte]]): Boolean = {
      t match {
        case n: Node[Array[Byte]] =>
          val left = n.l match {
            case _: Node[Array[Byte]] => false
            case _: Leaf[Array[Byte]] => false
            case _ => true
          }
          val right = n.r match {
            case n: Node[Array[Byte]] => isRightBranch(n)
            case _: Leaf[Array[Byte]] => true
            case _ => false
          }
          left && right
        case _: Leaf[Array[Byte]] => false
        case _ => false
      }
    }

    //main loop that steps the tree to the next time step
    def loop(t: Tree[Array[Byte]]): Tree[Array[Byte]] = {
      t match {
        case n: Node[Array[Byte]] =>
          var leftIsEmpty = false
          var leftIsLeaf = false
          var leftIsNode = false
          var leftVal: Array[Byte] = Array()
          var rightIsEmpty = false
          var rightIsLeaf = false
          var rightIsNode = false
          var rightVal: Array[Byte] = Array()
          val left = n.l match {
            case n: Node[Array[Byte]] => leftIsNode = true; leftVal=n.v; n
            case l: Leaf[Array[Byte]] => leftIsLeaf = true; leftVal=l.v; l
            case _ => leftIsEmpty = true; n.l
          }
          val right = n.r match {
            case n: Node[Array[Byte]] => rightIsNode=true; rightVal=n.v; n
            case l: Leaf[Array[Byte]] => rightIsLeaf=true; rightVal=l.v; l
            case _ => rightIsEmpty = true; n.r
          }
          val cutBranch = isRightBranch(left)
          if (rightIsEmpty && leftIsLeaf) {
            val keyPair = sKeypairFast(n.v.slice(0,seedBytes))
            assert(fch.hash(keyPair.slice(skBytes,skBytes+pkBytes)) sameElements n.v.slice(seedBytes+pkBytes,seedBytes+2*pkBytes))
            Node(n.v,Empty,Leaf(keyPair))
          } else if (cutBranch) {
            Node(n.v,Empty,sumGenerateKey(n.v.slice(0,seedBytes),n.height-1))
          } else if (leftIsNode && rightIsEmpty) {
            Node(n.v,loop(left),Empty)
          } else if (leftIsEmpty && rightIsNode) {
            Node(n.v, Empty, loop(right))
          } else {
            n
          }
        case l: Leaf[Array[Byte]] => l
        case _ => t
      }
    }
    val T = exp(key.height)
    val keyTime = sumGetKeyTimeStep(key)
    //steps key through time steps one at a time until key step == t
    if (t<T && keyTime < t){
      var tempKey = key
      for(_ <- keyTime+1 to t) {
        tempKey = loop(tempKey)
      }
      tempKey
    } else {
      println("Time step error, key not updated")
      println("T: "+T.toString+", key t:"+keyTime.toString+", t:"+t.toString)
      key
    }
  }

  /**
    * Updates the key in the sum composition
    * @param key binary tree to be updated
    * @param t time step key is to be updated to
    * @return updated key to be written to key
    */
  private def sumUpdateFast(key: Tree[Array[Byte]],t:Int): Tree[Array[Byte]] = {
    val T = exp(key.height)
    val keyTime = sumGetKeyTimeStep(key)
    if (t<T && keyTime < t){
      def constructKey(step:Int,input:Tree[Array[Byte]]):Tree[Array[Byte]] = {
        input match {
          case n: Node[Array[Byte]] =>
            var leftIsEmpty = false
            var leftIsLeaf = false
            var leftIsNode = false
            var leftVal: Array[Byte] = Array()
            var rightIsEmpty = false
            var rightIsLeaf = false
            var rightIsNode = false
            var rightVal: Array[Byte] = Array()
            val left = n.l match {
              case n: Node[Array[Byte]] => leftIsNode = true; leftVal=n.v; n
              case l: Leaf[Array[Byte]] => leftIsLeaf = true; leftVal=l.v; l
              case _ => leftIsEmpty = true; n.l
            }
            val right = n.r match {
              case n: Node[Array[Byte]] => rightIsNode=true; rightVal=n.v; n
              case l: Leaf[Array[Byte]] => rightIsLeaf=true; rightVal=l.v; l
              case _ => rightIsEmpty = true; n.r
            }
            val e = exp(n.height-1)
            val nextStep = step%e
            if (step>=e) {
              if (rightIsEmpty && leftIsLeaf) {
                val keyPair = sKeypairFast(n.v.slice(0,seedBytes))
                assert(fch.hash(keyPair.slice(skBytes,skBytes+pkBytes)) sameElements n.v.slice(seedBytes+pkBytes,seedBytes+2*pkBytes))
                Node(n.v,Empty,Leaf(keyPair))
              } else if (leftIsEmpty && rightIsNode) {
                Node(n.v, Empty, constructKey(nextStep,right))
              } else if (rightIsEmpty && leftIsNode) {
                val subKey = sumGenerateKey(n.v.slice(0,seedBytes),n.height-1)
                Node(n.v,Empty,constructKey(nextStep,subKey))
              } else {
                n
              }
            } else {
              if (rightIsEmpty) {
                Node(n.v,constructKey(nextStep,left),Empty)
              } else if (leftIsEmpty) {
                Node(n.v, Empty, constructKey(nextStep,right))
              } else {
                n
              }
            }
          case l: Leaf[Array[Byte]] => l
          case _ => input
        }
      }
      constructKey(t,key)
    } else {
      println("Time step error, key not updated")
      println("T: "+T.toString+", key t:"+keyTime.toString+", t:"+t.toString)
      key
    }
  }

  /**
    * Signature in the sum composition
    * @param sk secret key tree of the sum composition
    * @param m message to be signed
    * @param step  current time step of signing key sk
    * @return byte array signature
    */
  private def sumSign(sk: Tree[Array[Byte]],m: Array[Byte],step:Int): Array[Byte] = {
    assert(step == sumGetKeyTimeStep(sk))
    assert(sumVerifyKeyPair(sk,sumGetPublicKey(sk)))
    val stepBytesBigInt = BigInt(step).toByteArray
    val stepBytes = Array.fill(seedBytes-stepBytesBigInt.length){0x00.toByte}++stepBytesBigInt
    //loop that generates the signature of m++step and stacks up the witness path of the key
    def loop(t: Tree[Array[Byte]]): Array[Byte] = {
      t match {
        case n: Node[Array[Byte]] =>
          val left = n.l match {
            case nn: Node[Array[Byte]] =>
              loop(nn)
            case ll: Leaf[Array[Byte]] =>
              sSign(m++stepBytes,ll.v.slice(0,skBytes))++ll.v.slice(skBytes,skBytes+pkBytes)++stepBytes
            case _ => Array()
          }
          val right = n.r match {
            case nn: Node[Array[Byte]] =>
              loop(nn)
            case ll: Leaf[Array[Byte]] =>
              sSign(m++stepBytes,ll.v.slice(0,skBytes))++ll.v.slice(skBytes,skBytes+pkBytes)++stepBytes
            case _ => Array()
          }
          left++right++n.v.slice(seedBytes,seedBytes+2*pkBytes)
        case l: Leaf[Array[Byte]] =>
          sSign(m++stepBytes,l.v.slice(0,skBytes))++l.v.slice(skBytes,skBytes+pkBytes)++stepBytes++fch.hash(l.v.slice(skBytes,skBytes+pkBytes))++fch.hash(l.v.slice(skBytes,skBytes+pkBytes))
        case _ =>
          Array()
      }
    }
    loop(sk)
  }

  /**
    * Verify in the sum composition
    * @param pk public key of the sum composition
    * @param m message corresponding to the signature
    * @param sig signature to be verified
    * @return true if the signature is valid false if otherwise
    */
  private def sumVerify(pk: Array[Byte],m: Array[Byte],sig: Array[Byte]): Boolean = {
    val pkSeq = sig.drop(sigBytes+pkBytes+seedBytes)
    val stepBytes = sig.slice(sigBytes+pkBytes,sigBytes+pkBytes+seedBytes)
    val step = BigInt(stepBytes)
    var pkLogic = true
    if (step % 2 == 0) {
      pkLogic &= fch.hash(sig.slice(sigBytes,sigBytes+pkBytes)) sameElements pkSeq.slice(0,pkBytes)
    } else {
      pkLogic &= fch.hash(sig.slice(sigBytes,sigBytes+pkBytes)) sameElements pkSeq.slice(pkBytes,2*pkBytes)
    }
    for (i <- 0 to pkSeq.length/pkBytes-4 by 2) {
      val pk0:Array[Byte] = pkSeq.slice((i+2)*pkBytes,(i+3)*pkBytes)
      val pk00:Array[Byte] = pkSeq.slice(i*pkBytes,(i+1)*pkBytes)
      val pk01:Array[Byte] = pkSeq.slice((i+1)*pkBytes,(i+2)*pkBytes)
      val pk1:Array[Byte] = pkSeq.slice((i+3)*pkBytes,(i+4)*pkBytes)
      val pk10:Array[Byte] = pkSeq.slice(i*pkBytes,(i+1)*pkBytes)
      val pk11:Array[Byte] = pkSeq.slice((i+1)*pkBytes,(i+2)*pkBytes)
      if((step.toInt/exp(i/2+1)) % 2 == 0) {
        pkLogic &= pk0 sameElements fch.hash(pk00++pk01)
      } else {
        pkLogic &= pk1 sameElements fch.hash(pk10++pk11)
      }
    }
    pkLogic &= pk sameElements fch.hash(pkSeq.slice(pkSeq.length-2*pkBytes,pkSeq.length))
    sVerify(m++stepBytes,sig.slice(0,sigBytes),sig.slice(sigBytes,sigBytes+pkBytes)) && pkLogic
  }

  /**
    * Get the current time step of a sum composition key
    * @param key binary tree key
    * @return time step
    */
  private def sumGetKeyTimeStep(key: Tree[Array[Byte]]): Int = {
    key match {
      case n: Node[Array[Byte]] =>
        val left = n.l match {
          case n: Node[Array[Byte]] => sumGetKeyTimeStep(n)
          case _: Leaf[Array[Byte]] => 0
          case _ => 0
        }
        val right = n.r match {
          case n: Node[Array[Byte]] => sumGetKeyTimeStep(n)+exp(n.height)
          case _: Leaf[Array[Byte]] => 1
          case _ => 0
        }
        left+right
      case _: Leaf[Array[Byte]] => 0
      case _ => 0
    }
  }

  /**
    * Generate key in the MMM composition
    * @param seed input entropy for key generation
    * @return
    */
  def generateKey(seed: Array[Byte]): KesKeyBytes = {
    val r = PRNG(seed)
    val rp = PRNG(r._2)
    //super-scheme sum composition
    val L = sumGenerateKey(r._1,logl)
    //sub-scheme sum composition
    val Si = sumGenerateKey(rp._1,0)
    val pki = sumGetPublicKey(Si)
    val sig = sumSign(L,pki,0)
    assert(sumVerify(sumGetPublicKey(L),pki,sig))
    (L,Si,sig,pki,rp._2)
  }

  /**
    * Updates the key in the MMM composition (product composition with increasing height for
    * Si as L increments)
    * @param key  MMM key to be updated
    * @param t time step key is to be updated to
    * @return updated MMM key
    */
  def updateKey(key: KesKeyBytes, t:Int): KesKeyBytes = {
    val keyTime = getKeyTimeStep(key)
    var L = key._1
    var Si = key._2
    var sig = key._3
    var pki = key._4
    var seed = key._5
    val Tl = exp(L.height)
    var Ti = exp(Si.height)
    var tl = sumGetKeyTimeStep(L)
    var ti = sumGetKeyTimeStep(Si)
    if (keyTime < t) {
      for(_ <- keyTime+1 to t) {
        tl = sumGetKeyTimeStep(L)
        ti = sumGetKeyTimeStep(Si)
        if (ti+1 < Ti) {
          Si = sumUpdate(Si, ti + 1)
        } else if (tl < Tl) {
          val r = PRNG(seed)
          Si = sumGenerateKey(r._1, tl + 1)
          pki = sumGetPublicKey(Si)
          seed = r._2
          Ti = exp(Si.height)
          L = sumUpdate(L, tl + 1)
          tl = sumGetKeyTimeStep(L)
          sig = sumSign(L,pki,tl)
        } else {
          println("Error: max time steps reached")
        }
      }
    }
    (L,Si,sig,pki,seed)
  }

  /**
    * Fast version on updateKey, should be equivalent input and output
    * @param key input key
    * @param t_in input desired time step
    * @return  updated key
    */
  def updateKeyFast(key: KesKeyBytes, t_in:Int): KesKeyBytes = {
    val keyTime = getKeyTimeStep(key)
    var L = key._1
    var Si = key._2
    var sig = key._3
    var pki = key._4
    var seed = key._5
    def timeStepModLog2(t:Int):(Int,Int) = {
      if (t == 0) {
        (0,0)
      } else {
        var e = 2
        var n = 1
        var found = false
        while (!found) {
          val next_power = exp(n+1)
          if (t+1>next_power-1) {
            n+=1
            e = next_power
          } else {
            found = true
          }
        }
        val tl = n
        val ti = (t+1) % e
        (tl,ti)
      }
    }
    val (tl_in,ti_in) = timeStepModLog2(t_in)
    if (keyTime < t_in) {
      val Tl = exp(L.height)
      val tl = sumGetKeyTimeStep(L)
      var currentLeaf = tl
      if (tl < Tl) while(tl_in>currentLeaf) {
        val r = PRNG(seed)
        seed = r._2
        currentLeaf += 1
        if (currentLeaf == tl_in) {
          Si = sumGenerateKey(r._1, tl_in)
          pki = sumGetPublicKey(Si)
          L = sumUpdate(L, tl_in)
          sig = sumSign(L,pki,tl_in)
        } else {
          L = sumUpdate(L, currentLeaf)
        }
      } else {
        println("Error: max time steps reached")
      }
      val Ti = exp(Si.height)
      val ti = sumGetKeyTimeStep(Si)
      if (ti_in < Ti && ti_in > 0 && ti<ti_in) {
        Si = sumUpdateFast(Si, ti_in)
      }
    } else {
      println("Error: t less than given keyTime")
    }
    (L,Si,sig,pki,seed)
  }

  /**
    * Get the current time step of an MMM key
    * @param key MMM key to be inspected
    * @return Current time step of key
    */
  def getKeyTimeStep(key: KesKeyBytes): Int = {
    val L = key._1
    val Si = key._2
    val tl = sumGetKeyTimeStep(L)
    val ti = sumGetKeyTimeStep(Si)
    exp(tl)-1+ti
  }

  def getKeyTimeStep(key: PrivateKey): Long = {
    val L = key.L
    val Si = key.Si
    val tl = sumGetKeyTimeStep(L)
    val ti = sumGetKeyTimeStep(Si)
    exp(tl)-1+ti+key.offset
  }

  /**
    * Signature in the MMM composition
    * @param key signing secret key
    * @param m message to be signed
    * @return signature of m
    */
  def sign(key: KesKeyBytes, m: Array[Byte]): KesSignature = {
    val keyTime = BigInt(getKeyTimeStep(key)).toByteArray
    val Si = key._2
    val sigi = key._3
    val pki = key._4
    val ti = sumGetKeyTimeStep(Si)
    val sigm = sumSign(Si,m++keyTime,ti)
    (sigi,sigm,pki)
  }

  /**
    * Verify MMM signature
    * @param pk public key of the MMM secret key
    * @param m message corresponding to signature
    * @param sig signature to be verified
    * @return true if signature is valid false if otherwise
    */
  def verify(pk: Array[Byte], m: Array[Byte], sig: KesSignature, t: Int): Boolean = {
    val sigi = sig._1
    val sigm = sig._2
    val pki = sig._3
    val stepL = BigInt(sigi.slice(sigBytes+pkBytes,sigBytes+pkBytes+seedBytes)).toInt
    val stepSi = BigInt(sigm.slice(sigBytes+pkBytes,sigBytes+pkBytes+seedBytes)).toInt
    sumVerify(pk,pki,sigi) && sumVerify(pki,m++BigInt(t).toByteArray,sigm) && (t==exp(stepL)-1+stepSi)
  }

  /**
    * Get the public key of an MMM private key
    * @param key input key
    * @return public key
    */
  def publicKey(key: KesKeyBytes):  Array[Byte] = {
    sumGetPublicKey(key._1)
  }

}
