package co.topl.nodeView.history

import co.topl.nodeView.KeyValueStore
import io.iohk.iodb.ByteArrayWrapper

class InMemoryKeyValueStore extends KeyValueStore {
  import InMemoryKeyValueStore._
  var state: Map[ByteArrayWrapper, ByteArrayWrapper] = Map.empty
  var changes: List[ChangeSet] = Nil

  override def update(
    version:  ByteArrayWrapper,
    toRemove: Iterable[ByteArrayWrapper],
    toAdd:    Iterable[(ByteArrayWrapper, ByteArrayWrapper)]
  ): Unit = {
    val changeSet = ChangeSet(
      version,
      toRemove.toList.map(key => Remove(key, state(key))) ++ toAdd.map { case (key, value) =>
        if (state.contains(key))
          Update(key, state(key), value)
        else
          Insert(key, value)
      }
    )
    state --= toRemove
    state ++= toAdd.map { case (k, v) => k -> v }
    changes :+= changeSet
  }

  override def rollback(version: ByteArrayWrapper): Unit = {
    val wrappedVersion = version
    require(changes.exists(_.version == wrappedVersion))
    def revertLatest(): Unit = {
      val latest = changes.last
      changes = changes.init
      latest.changes.foreach {
        case Insert(key, _) =>
          state -= key
        case Update(key, previous, _) =>
          state += (key -> previous)
        case Remove(key, previous) =>
          state += (key -> previous)
      }
    }
    while (changes.nonEmpty && changes.head.version != wrappedVersion) revertLatest()
  }

  override def get(key: ByteArrayWrapper): Option[ByteArrayWrapper] =
    state.get(key)

  override def close(): Unit = {}

  override def latestVersion(): Option[ByteArrayWrapper] =
    changes.lastOption.map(_.version)
}

object InMemoryKeyValueStore {

  def empty(): InMemoryKeyValueStore = new InMemoryKeyValueStore

  case class ChangeSet(version: ByteArrayWrapper, changes: List[Change])
  sealed abstract class Change
  case class Insert(key: ByteArrayWrapper, value: ByteArrayWrapper) extends Change
  case class Update(key: ByteArrayWrapper, previous: ByteArrayWrapper, value: ByteArrayWrapper) extends Change
  case class Remove(key: ByteArrayWrapper, previous: ByteArrayWrapper) extends Change
}
