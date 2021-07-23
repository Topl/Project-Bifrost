package co.topl.utils

import co.topl.attestation.keyManagement._
import co.topl.attestation.{PublicKeyPropositionCurve25519, PublicKeyPropositionEd25519}
import org.scalatest.{BeforeAndAfterAll, Suite}

import java.nio.file.{Files, Path}
import java.util.Comparator

trait KeyFileTestHelper {

  protected var keyRingCurve25519: KeyRing[PrivateKeyCurve25519, KeyfileCurve25519] = _

  protected var keyRingEd25519: KeyRing[PrivateKeyEd25519, KeyfileEd25519] = _
}

trait DiskKeyFileTestHelper extends BeforeAndAfterAll with NetworkPrefixTestHelper with KeyFileTestHelper {

  self: Suite =>

  protected var keyFileDir: Path = _

  protected val propTypeCurve25519: String = PublicKeyPropositionCurve25519.typeString

  protected val propTypeEd25519: String = PublicKeyPropositionEd25519.typeString

  override def beforeAll(): Unit = {
    super.beforeAll()
    keyFileDir = Files.createTempDirectory("bifrost-test-keyring")
    keyRingCurve25519 = KeyRing.empty[PrivateKeyCurve25519, KeyfileCurve25519](Some(keyFileDir.toString))(
      networkPrefix,
      PrivateKeyCurve25519.secretGenerator,
      KeyfileCurve25519Companion
    )
    keyRingEd25519 = KeyRing.empty[PrivateKeyEd25519, KeyfileEd25519](Some(keyFileDir.toString))(
      networkPrefix,
      PrivateKeyEd25519.secretGenerator,
      KeyfileEd25519Companion
    )
    import org.scalatest.TryValues._
    keyRingCurve25519.generateNewKeyPairs(num = 3).success.value
    keyRingEd25519.generateNewKeyPairs(num = 3).success.value
  }

  override protected def afterAll(): Unit = {
    super.afterAll()
    import scala.jdk.CollectionConverters._
    Files
      .walk(keyFileDir)
      .sorted(Comparator.reverseOrder[Path]())
      .iterator()
      .asScala
      .foreach(Files.delete)
  }
}

trait InMemoryKeyFileTestHelper extends NetworkPrefixTestHelper with BeforeAndAfterAll with KeyFileTestHelper {
  self: Suite =>

  keyRingCurve25519 = {
    implicit def keyfileCurve25519Companion: KeyfileCurve25519Companion.type = KeyfileCurve25519Companion
    KeyRing.empty[PrivateKeyCurve25519, KeyfileCurve25519]()
  }

  keyRingEd25519 = {
    implicit def companion: KeyfileEd25519Companion.type = KeyfileEd25519Companion
    KeyRing.empty[PrivateKeyEd25519, KeyfileEd25519]()
  }

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    keyRingCurve25519.generateNewKeyPairs(3)
    keyRingEd25519.generateNewKeyPairs(3)
  }
}
