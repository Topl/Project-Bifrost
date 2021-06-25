package co.topl.utils

import cats.implicits._
import co.topl.attestation.PublicKeyPropositionCurve25519.evProducer
import co.topl.attestation.{keyManagement, _}
import co.topl.attestation.keyManagement.{proof, _}
import co.topl.crypto.hash.digest.Digest32
import co.topl.crypto.signatures.{Curve25519, Signature}
import co.topl.modifier.ModifierId
import co.topl.modifier.block.Block
import co.topl.modifier.block.PersistentNodeViewModifier.PNVMVersion
import co.topl.modifier.box.Box.Nonce
import co.topl.modifier.box.{ProgramId, _}
import co.topl.modifier.transaction._
import co.topl.utils.StringDataTypes.Latin1Data
import co.topl.utils.StringDataTypes.implicits._
import co.topl.utils.encode.Base58
import co.topl.utils.codecs.implicits._
import io.circe.Json
import io.circe.syntax._
import org.scalacheck.rng.Seed
import org.scalacheck.{Arbitrary, Gen}
import org.scalatest.Suite

import scala.collection.SortedSet
import scala.util.Random

/**
 * Created by cykoz on 4/12/17.
 */
trait CommonGenerators extends Logging with NetworkPrefixTestHelper {
  self: Suite =>

  type P = Proposition
  type S = Secret

  def sampleUntilNonEmpty[T](generator: Gen[T]): T =
    generator.pureApply(Gen.Parameters.default, Seed.random())

  lazy val stringGen: Gen[String] = Gen.alphaNumStr.suchThat(_.nonEmpty)

  lazy val shortNameGen: Gen[String] = for {
    n   <- Gen.choose(0, AssetCode.shortNameLimit)
    str <- Gen.listOfN(n, Gen.alphaNumChar).map(_.mkString)
  } yield str

  val jsonTypes: Seq[String] = Seq("Object", "Array", "Boolean", "String", "Number")

  lazy val jsonTypeGen: Gen[String] = Gen.oneOf(jsonTypes)

  private val booleanGen: Gen[Boolean] = Gen.oneOf(Seq(true, false))

  def jsonGen(depth: Int = 0): Gen[Json] = for {
    numFields <- positiveTinyIntGen
  } yield (0 until numFields)
    .map { _ =>
      sampleUntilNonEmpty(stringGen) -> (sampleUntilNonEmpty(jsonTypeGen) match {
        case "Object" if depth < 2 => sampleUntilNonEmpty(jsonGen(depth + 1))
        case "Array" if depth < 3  => sampleUntilNonEmpty(jsonArrayGen(depth + 1))
        case "Boolean"             => sampleUntilNonEmpty(booleanGen).asJson
        case "String"              => sampleUntilNonEmpty(stringGen).asJson
        case "Number"              => sampleUntilNonEmpty(positiveDoubleGen).asJson
        case _                     => sampleUntilNonEmpty(stringGen).asJson
      })
    }
    .toMap
    .asJson

  def jsonArrayGen(depth: Int = 0): Gen[Json] = for {
    numFields <- positiveTinyIntGen
  } yield ((0 until numFields) map { _ =>
    sampleUntilNonEmpty(jsonTypeGen) match {
      case "Object" if depth < 2 => sampleUntilNonEmpty(jsonGen(depth + 1))
      case "Array" if depth < 3  => sampleUntilNonEmpty(jsonArrayGen(depth + 1))
      case "Boolean"             => sampleUntilNonEmpty(booleanGen).asJson
      case "String"              => sampleUntilNonEmpty(stringGen).asJson
      case "Number"              => sampleUntilNonEmpty(positiveDoubleGen).asJson
      case _                     => sampleUntilNonEmpty(stringGen).asJson
    }
  }).asJson

  private lazy val intMin = 1
  private lazy val tinyIntMax = 10
  private lazy val medIntMax = 100

  private lazy val int128Min: Int128 = Int128.MaxValue
  private lazy val int128Max: Int128 = Int128.MaxValue

  implicit lazy val int128Chooser: Gen.Choose[Int128] =
    (min, max) => Gen.Choose.chooseBigInt.choose(min.bigInt, max.bigInt).map(Int128(_))

  lazy val positiveTinyIntGen: Gen[Int] = Gen.choose(intMin, tinyIntMax)
  lazy val positiveMediumIntGen: Gen[Int] = Gen.choose(intMin, medIntMax)

  lazy val positiveDoubleGen: Gen[Double] = Gen.choose(0, Double.MaxValue)

  lazy val positiveInt128Gen: Gen[Int128] = Gen.choose[Int128](0, int128Max)
  lazy val smallInt128Gen: Gen[Int128] = Gen.choose[Int128](0, Int.MaxValue)
  lazy val largeInt128Gen: Gen[Int128] = Gen.choose[Int128](Long.MaxValue, int128Max)

  def samplePositiveDouble: Double = Random.nextFloat()

  lazy val tokenBoxesGen: Gen[Seq[TokenBox[TokenValueHolder]]] = for {
    tx <- Gen.someOf(polyBoxGen, arbitBoxGen, assetBoxGen)
  } yield tx

  lazy val polyBoxGen: Gen[PolyBox] = for {
    evidence <- evidenceGen
    nonce    <- positiveLongGen
    value    <- positiveLongGen
  } yield PolyBox(evidence, nonce, SimpleValue(value))

  lazy val arbitBoxGen: Gen[ArbitBox] = for {
    evidence <- evidenceGen
    nonce    <- positiveLongGen
    value    <- positiveLongGen
  } yield ArbitBox(evidence, nonce, SimpleValue(value))

  lazy val assetBoxGen: Gen[AssetBox] = for {
    evidence <- evidenceGen
    nonce    <- positiveLongGen
    quantity <- positiveLongGen
    // TODO: Hard coded as 1, but change this to arbitrary in the future
    //assetVersion <- Arbitrary.arbitrary[Byte]
    shortName <- shortNameGen
    issuer    <- addressGen
    data      <- stringGen
  } yield {
    // TODO: Hard coded as 1, but change this to arbitrary in the future
    val assetVersion = 1: Byte
    val assetCode = AssetCode(assetVersion, issuer, Latin1Data.unsafe(shortName))
    val value = AssetValue(quantity, assetCode, metadata = Some(data))
    AssetBox(evidence, nonce, value)
  }

  lazy val stateBoxGen: Gen[StateBox] = for {
    evidence  <- evidenceGen
    state     <- stringGen
    nonce     <- positiveLongGen
    programId <- programIdGen
  } yield StateBox(evidence, nonce, programId, state.asJson)

  lazy val codeBoxGen: Gen[CodeBox] = for {
    evidence  <- evidenceGen
    nonce     <- positiveLongGen
    methodLen <- positiveTinyIntGen
    methods   <- Gen.containerOfN[Seq, String](methodLen, stringGen)
    paramLen  <- positiveTinyIntGen
    programId <- programIdGen
  } yield {

    val interface: Map[String, Seq[String]] = methods.map {
      _ -> sampleUntilNonEmpty(Gen.containerOfN[Seq, String](paramLen, Gen.oneOf(jsonTypes)))
    }.toMap

    CodeBox(evidence, nonce, programId, methods, interface)
  }

  lazy val executionBoxGen: Gen[ExecutionBox] = for {
    evidence   <- evidenceGen
    codeBox_1  <- codeBoxGen
    codeBox_2  <- codeBoxGen
    nonce      <- positiveLongGen
    stateBox_1 <- stateBoxGen
    stateBox_2 <- stateBoxGen
    programId  <- programIdGen
  } yield ExecutionBox(
    evidence,
    nonce,
    programId,
    Seq(stateBox_1.value, stateBox_2.value),
    Seq(codeBox_1.value, codeBox_2.value)
  )

  lazy val signatureGen: Gen[proof.SignatureCurve25519] =
    genBytesList(SignatureCurve25519.signatureSize).map(bytes => proof.SignatureCurve25519(Signature(bytes)))

  lazy val programIdGen: Gen[ProgramId] = for {
    seed <- specificLengthBytesGen(ProgramId.size)
  } yield ProgramId.create(seed)

  def preFeeBoxGen(minFee: Long = 0, maxFee: Long = Long.MaxValue): Gen[(Nonce, Long)] = for {
    nonce  <- Gen.choose(Long.MinValue, Long.MaxValue)
    amount <- Gen.choose(minFee, maxFee)
  } yield (nonce, amount)

  lazy val fromGen: Gen[(Address, Nonce)] = for {
    address <- addressGen
    nonce   <- positiveLongGen
  } yield (address, nonce)

  lazy val fromSeqGen: Gen[IndexedSeq[(Address, Nonce)]] = for {
    seqLen <- positiveTinyIntGen
  } yield (0 until seqLen) map { _ => sampleUntilNonEmpty(fromGen) }

  lazy val simpleValueGen: Gen[SimpleValue] = for {
    value <- positiveLongGen
  } yield SimpleValue(value)

  lazy val toGen: Gen[(Address, SimpleValue)] = for {
    address <- addressGen
    value   <- positiveLongGen
  } yield (address, SimpleValue(value))

  lazy val assetCodeGen: Gen[AssetCode] = for {
    // TODO: Hard coded as 1, but change this to arbitrary in the future
    // assetVersion <- Arbitrary.arbitrary[Byte]
    issuer    <- addressGen
    shortName <- shortNameGen
  } yield AssetCode(1: Byte, issuer, Latin1Data.unsafe(shortName))

  lazy val assetValueGen: Gen[AssetValue] = for {
    quantity  <- positiveLongGen
    assetCode <- assetCodeGen
    data      <- stringGen
  } yield AssetValue(quantity, assetCode, metadata = Some(data))

  lazy val assetToGen: Gen[(Address, AssetValue)] = for {
    assetValue <- assetValueGen
  } yield (assetValue.assetCode.issuer, assetValue)

  lazy val securityRootGen: Gen[SecurityRoot] = for {
    root <- specificLengthBytesGen(Digest32.size)
  } yield SecurityRoot(Base58.encode(root))

  lazy val toSeqGen: Gen[IndexedSeq[(Address, SimpleValue)]] = for {
    seqLen <- positiveTinyIntGen
  } yield (0 until seqLen) map { _ => sampleUntilNonEmpty(toGen) }

  lazy val assetToSeqGen: Gen[IndexedSeq[(Address, TokenValueHolder)]] = for {
    seqLen <- positiveTinyIntGen
  } yield (0 until seqLen) map { _ => sampleUntilNonEmpty(assetToGen) }

  lazy val sigSeqGen: Gen[IndexedSeq[proof.SignatureCurve25519]] = for {
    seqLen <- positiveTinyIntGen
  } yield (0 until seqLen) map { _ => sampleUntilNonEmpty(signatureGen) }

  lazy val polyTransferGen: Gen[PolyTransfer[keyManagement.proposition.PublicKeyPropositionCurve25519]] = for {
    from        <- fromSeqGen
    to          <- toSeqGen
    attestation <- attestationGen
    fee         <- positiveLongGen
    timestamp   <- positiveLongGen
    data        <- stringGen
  } yield PolyTransfer(from, to, attestation, fee, timestamp, Some(data), minting = false)

  lazy val arbitTransferGen: Gen[ArbitTransfer[keyManagement.proposition.PublicKeyPropositionCurve25519]] = for {
    from        <- fromSeqGen
    to          <- toSeqGen
    attestation <- attestationGen
    fee         <- positiveLongGen
    timestamp   <- positiveLongGen
    data        <- stringGen
  } yield ArbitTransfer(from, to, attestation, fee, timestamp, Some(data), minting = false)

  lazy val assetTransferGen: Gen[AssetTransfer[keyManagement.proposition.PublicKeyPropositionCurve25519]] = for {
    from        <- fromSeqGen
    to          <- assetToSeqGen
    attestation <- attestationGen
    fee         <- positiveLongGen
    timestamp   <- positiveLongGen
    data        <- stringGen
  } yield AssetTransfer(from, to, attestation, fee, timestamp, Some(data), minting = true)

  lazy val publicKeyPropositionCurve25519Gen: Gen[(PrivateKeyCurve25519, keyManagement.proposition.PublicKeyPropositionCurve25519)] =
    key25519Gen.map(key => key._1 -> key._2)

  lazy val thresholdPropositionCurve25519Gen: Gen[(Set[PrivateKeyCurve25519], ThresholdPropositionCurve25519)] = for {
    numKeys   <- positiveMediumIntGen
    threshold <- positiveTinyIntGen
  } yield {
    val setOfKeys = (0 until numKeys)
      .map { _ =>
        val key = sampleUntilNonEmpty(key25519Gen)
        (key._1, key._2)
      }
      .foldLeft((Set[PrivateKeyCurve25519](), Set[keyManagement.proposition.PublicKeyPropositionCurve25519]())) { (set, cur) =>
        (set._1 + cur._1, set._2 + cur._2)
      }
    val props = SortedSet[keyManagement.proposition.PublicKeyPropositionCurve25519]() ++ setOfKeys._2
    val thresholdProp = ThresholdPropositionCurve25519(threshold, props)

    (setOfKeys._1, thresholdProp)
  }

  lazy val thresholdSignatureCurve25519Gen: Gen[ThresholdSignatureCurve25519] = for {
    numKeys <- positiveMediumIntGen
    message <- nonEmptyBytesGen
  } yield {
    val sigs = (0 until numKeys).map { _ =>
      val key = sampleUntilNonEmpty(key25519Gen)
      key._1.sign(message)
    }.toSet
    ThresholdSignatureCurve25519(sigs)
  }

  lazy val propTypes: Gen[String] = sampleUntilNonEmpty(
    Gen.oneOf(PublicKeyPropositionCurve25519.typeString, ThresholdPropositionCurve25519.typeString)
  )

  lazy val keyPairGen: Gen[(Set[_ <: Secret], _ <: KnowledgeProposition[_ <: Secret])] = for {
    propType <- propTypes
  } yield propType match {
    case PublicKeyPropositionCurve25519.typeString =>
      val key = sampleUntilNonEmpty(publicKeyPropositionCurve25519Gen)
      Set(key._1) -> key._2
    case ThresholdPropositionCurve25519.typeString =>
      sampleUntilNonEmpty(thresholdPropositionCurve25519Gen)
  }

  lazy val attestationGen: Gen[Map[keyManagement.proposition.PublicKeyPropositionCurve25519, Proof[keyManagement.proposition.PublicKeyPropositionCurve25519]]] = for {
    prop <- propositionGen
    sig  <- signatureGen
  } yield Map(prop -> sig)

  lazy val oneOfNPropositionGen: Gen[(Set[PrivateKeyCurve25519], ThresholdPropositionCurve25519)] = for {
    n <- positiveTinyIntGen
  } yield {
    val setOfKeys = (0 until n)
      .map { _ =>
        val key = sampleUntilNonEmpty(key25519Gen)
        (key._1, key._2)
      }
      .foldLeft((Set[PrivateKeyCurve25519](), Set[keyManagement.proposition.PublicKeyPropositionCurve25519]())) { (set, cur) =>
        (set._1 + cur._1, set._2 + cur._2)
      }
    val pubKeyProps = SortedSet[keyManagement.proposition.PublicKeyPropositionCurve25519]() ++ setOfKeys._2
    val prop = ThresholdPropositionCurve25519(1, pubKeyProps)

    (setOfKeys._1, prop)
  }

  lazy val keyPairSetGen: Gen[Set[(PrivateKeyCurve25519, keyManagement.proposition.PublicKeyPropositionCurve25519)]] = for {
    seqLen <- positiveTinyIntGen
  } yield ((0 until seqLen) map { _ => sampleUntilNonEmpty(key25519Gen) }).toSet

  val transactionTypes: Seq[Gen[Transaction.TX]] =
    Seq(polyTransferGen, arbitTransferGen, assetTransferGen)

  lazy val bifrostTransactionSeqGen: Gen[Seq[Transaction.TX]] = for {
    seqLen <- positiveMediumIntGen
  } yield 0 until seqLen map { _ =>
    sampleUntilNonEmpty(sampleUntilNonEmpty(Gen.oneOf(transactionTypes)))
  }

  lazy val intSeqGen: Gen[Seq[Int]] = for {
    seqLen <- positiveMediumIntGen
  } yield 0 until seqLen map { _ =>
    sampleUntilNonEmpty(Gen.choose(0, 255))
  }

  lazy val nonEmptyBytesGen: Gen[Array[Byte]] = Gen
    .nonEmptyListOf(Arbitrary.arbitrary[Byte])
    .map(_.toArray)
    .retryUntil(_.length > 0)
  lazy val positiveLongGen: Gen[Long] = Gen.choose(1, Long.MaxValue)

  lazy val modifierIdGen: Gen[ModifierId] =
    Gen.listOfN(ModifierId.size, Arbitrary.arbitrary[Byte]).map(li => ModifierId.parseBytes(li.toArray).get)

  lazy val key25519Gen: Gen[(PrivateKeyCurve25519, keyManagement.proposition.PublicKeyPropositionCurve25519)] = genBytesList(Curve25519.KeyLength)
    .map(s => PrivateKeyCurve25519.secretGenerator.generateSecret(s))
  lazy val propositionGen: Gen[keyManagement.proposition.PublicKeyPropositionCurve25519] = key25519Gen.map(_._2)
  lazy val evidenceGen: Gen[Evidence] = for { address <- addressGen } yield address.evidence
  lazy val addressGen: Gen[Address] = for { key <- propositionGen } yield key.address

  def genBytesList(size: Int): Gen[Array[Byte]] = genBoundedBytes(size, size)

  def genBoundedBytes(minSize: Int, maxSize: Int): Gen[Array[Byte]] =
    Gen.choose(minSize, maxSize) flatMap { sz => Gen.listOfN(sz, Arbitrary.arbitrary[Byte]).map(_.toArray) }

  def specificLengthBytesGen(length: Int): Gen[Array[Byte]] = Gen
    .listOfN(length, Arbitrary.arbitrary[Byte])
    .map(_.toArray)

  lazy val blockGen: Gen[Block] = for {
    parentIdBytes <- specificLengthBytesGen(ModifierId.size)
    timestamp     <- positiveLongGen
    generatorBox  <- arbitBoxGen
    publicKey     <- propositionGen
    signature     <- signatureGen
    txs           <- bifrostTransactionSeqGen
  } yield {
    val parentId = ModifierId.fromBase58(parentIdBytes.encodeAsBase58)
    val height: Long = 1L
    val difficulty = 1000000000000000000L
    val version: PNVMVersion = 1: Byte

    Block(parentId, timestamp, generatorBox, publicKey, signature, height, difficulty, txs, version)
  }

}
