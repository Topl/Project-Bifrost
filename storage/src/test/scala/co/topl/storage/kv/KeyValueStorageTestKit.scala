package co.topl.storage.kv

import cats.scalatest.EitherValues
import co.topl.utils.Extensions.StringOps
import org.scalacheck.Gen
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

trait KeyValueStorageTestKit
    extends AnyFlatSpecLike
    with ScalaCheckPropertyChecks
    with BeforeAndAfterEach
    with ScalaFutures
    with EitherValues
    with Matchers {

  def createStorage(): KeyValueStorage[String, String, String]

  private var storage: KeyValueStorage[String, String, String] = _

  behavior of "KeyValueStorage"

  it should "store and retrieve data" in {
    var version = 0
    forAll(Gen.alphaNumStr, Gen.alphaNumStr) { (key: String, value: String) =>
      whenever(key.nonEmpty && key.getValidLatin1Bytes.nonEmpty) {
        storage.put(s"v$version")(key -> value).value.futureValue.value
        storage.get(key).value.futureValue.value shouldBe value
        version += 1
      }
    }
  }

  it should "version data" in {
    storage.put("v1")("a" -> "1").value.futureValue.value
    storage.get("a").value.futureValue.value shouldBe "1"
    storage.put("v2")("a" -> "2")
    storage.get("a").value.futureValue.value shouldBe "2"
    storage.rollbackTo("v1").value.futureValue.value
    storage.get("a").value.futureValue.value shouldBe "1"
  }

  override def beforeEach(): Unit = {
    super.beforeEach()
    storage = createStorage()
  }

  override def afterEach(): Unit = {
    super.afterEach()
    Option(storage).foreach(_.close().value.futureValue)
  }

}
