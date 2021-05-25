package co.topl.storage.kv

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit

class InMemoryKeyValueStorageSpec extends ScalaTestWithActorTestKit with KeyValueStorageTestKit {

  override def createStorage(): KeyValueStorage[String, String, String] =
    new InMemoryKeyValueStorage[String, String, String]()
}
