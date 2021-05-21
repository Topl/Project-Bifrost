package co.topl.storage.kv

import akka.Done
import cats.data.EitherT

import scala.concurrent.Future

trait KeyValueStorage {

  /**
   * Retrieve a byte-array value by byte-array key
   */
  def get(key: Array[Byte]): EitherT[Future, KeyValueStorage.Error, Array[Byte]]

  /**
   * Atomically insert items into storage.  If one fails, all must fail (and changes must be reverted).
   */
  def put(items: (Array[Byte], Array[Byte])*): EitherT[Future, KeyValueStorage.Error, Done]

  /**
   * Atomically remove the values associated with the given keys.  If one fails, all must fail (and changes must be reverted)
   */
  def delete(keys: Array[Byte]*): EitherT[Future, KeyValueStorage.Error, Done]
}

object KeyValueStorage {
  sealed abstract class Error
  case class NotFound(key: Array[Byte]) extends Error
}
