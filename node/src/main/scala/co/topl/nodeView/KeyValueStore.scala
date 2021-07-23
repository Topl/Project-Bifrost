package co.topl.nodeView

import com.google.common.cache.{CacheBuilder, CacheLoader, LoadingCache}
import io.iohk.iodb.{ByteArrayWrapper, LSMStore}

import scala.concurrent.duration.{FiniteDuration, MILLISECONDS}

trait KeyValueStore extends AutoCloseable {

  def update(
    version:  ByteArrayWrapper,
    toRemove: Iterable[ByteArrayWrapper],
    toAdd:    Iterable[(ByteArrayWrapper, ByteArrayWrapper)]
  ): Unit
  def rollback(version: ByteArrayWrapper): Unit
  def get(key:          ByteArrayWrapper): Option[ByteArrayWrapper]
  def latestVersion(): Option[ByteArrayWrapper]
}

class LSMKeyValueStore(lsmStore: LSMStore) extends KeyValueStore {

  override def update(
    version:  ByteArrayWrapper,
    toRemove: Iterable[ByteArrayWrapper],
    toAdd:    Iterable[(ByteArrayWrapper, ByteArrayWrapper)]
  ): Unit =
    lsmStore.update(version, toRemove, toAdd)

  override def rollback(version: ByteArrayWrapper): Unit =
    lsmStore.rollback(version)

  override def get(key: ByteArrayWrapper): Option[ByteArrayWrapper] =
    lsmStore.get(key)

  override def close(): Unit =
    lsmStore.close()

  override def latestVersion(): Option[ByteArrayWrapper] =
    lsmStore.lastVersionID
}

class CacheLayerKeyValueStore(underlying: KeyValueStore, cacheExpiration: FiniteDuration, cacheSize: Int)
    extends KeyValueStore {

  type KEY = ByteArrayWrapper
  type VAL = ByteArrayWrapper

  private[nodeView] val cache: LoadingCache[KEY, Option[VAL]] = CacheBuilder
    .newBuilder()
    .expireAfterAccess(cacheExpiration.toMillis, MILLISECONDS)
    .maximumSize(cacheSize)
    .build[KEY, Option[VAL]](
      new CacheLoader[KEY, Option[VAL]] {

        def load(key: KEY): Option[VAL] =
          underlying.get(key)
      }
    )

  override def update(
    version:  ByteArrayWrapper,
    toRemove: Iterable[ByteArrayWrapper],
    toAdd:    Iterable[(ByteArrayWrapper, ByteArrayWrapper)]
  ): Unit = {
    underlying.update(version, toRemove, toAdd)
    toRemove.foreach(cache.invalidate)
    toAdd
      .map { case (key, value) => key -> Some(value) }
      .foreach((cache.put _).tupled)
  }

  override def rollback(version: ByteArrayWrapper): Unit = {
    underlying.rollback(version)
    cache.invalidateAll()
  }

  override def get(key: ByteArrayWrapper): Option[ByteArrayWrapper] =
    cache.get(key)

  override def close(): Unit =
    underlying.close()

  override def latestVersion(): Option[KEY] =
    underlying.latestVersion()
}
