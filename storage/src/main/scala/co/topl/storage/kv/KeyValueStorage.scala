package co.topl.storage.kv

import akka.Done
import cats.data.EitherT

import scala.concurrent.Future

trait KeyValueStorage[Version, Key, Value] {

  /**
   * Retrieve a byte-array value by byte-array key
   */
  def get(key: Key): EitherT[Future, KeyValueStorage.Error, Value]

  /**
   * Atomically insert items into storage.  If one fails, all must fail (and changes must be reverted).
   */
  def put(version: Version)(items: (Key, Value)*): EitherT[Future, KeyValueStorage.Error, Done]

  /**
   * Atomically remove the values associated with the given keys.  If one fails, all must fail (and changes must be reverted)
   */
  def delete(version: Version)(keys: Key*): EitherT[Future, KeyValueStorage.Error, Done]

  def contains(key: Key): EitherT[Future, KeyValueStorage.Error, Boolean]

  def rollbackTo(version: Version): EitherT[Future, KeyValueStorage.Error, Done]
}

object KeyValueStorage {
  sealed abstract class Error
  case class NotFound[Key](key: Key) extends Error
  case class ExceptionError(throwable: Throwable) extends Error
  case class DomainError[T](error: T) extends Error
}
