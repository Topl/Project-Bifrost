package co.topl.storage.kv

import akka.actor.ActorSystem
import akka.testkit.TestKit
import co.topl.utils.codecs.{AsBytes, FromBytes, InfallibleAsBytes, InfallibleFromBytes}
import io.iohk.iodb.LSMStore
import scorex.crypto.hash.Blake2b256

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.concurrent.duration._

class IODBKeyValueStorageSpec extends TestKit(ActorSystem("IODBKeyValueStorageSpec")) with KeyValueStorageTestKit {

  val versionAsBytes: InfallibleAsBytes[String] = AsBytes.infallible(Blake2b256.hash)
  val keyAsBytes: InfallibleAsBytes[String] = AsBytes.infallible(Blake2b256.hash)
  val valueAsBytes: InfallibleAsBytes[String] = AsBytes.infallible(_.getBytes(StandardCharsets.UTF_8))
  val valueFromBytes: InfallibleFromBytes[String] = FromBytes.infallible(new String(_, StandardCharsets.UTF_8))

  override def createStorage(): KeyValueStorage[String, String, String] = {
    val lsmStore = new LSMStore(Files.createTempDirectory("IODBKeyValueStorageSpec").toFile)
    new IODBKeyValueStorage[String, String, String](
      lsmStore,
      10.minutes,
      50000
    )(system, versionAsBytes, keyAsBytes, valueAsBytes, valueFromBytes)
  }
}
