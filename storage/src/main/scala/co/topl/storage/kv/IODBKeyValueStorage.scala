package co.topl.storage.kv

import akka.Done
import akka.actor.ActorSystem
import akka.dispatch.Dispatchers
import cats.data.EitherT
import cats.implicits._
import com.github.benmanes.caffeine.cache.Caffeine
import io.iohk.iodb.{ByteArrayWrapper, LSMStore}
import scalacache.caffeine._
import scalacache.modes.scalaFuture._
import scalacache.{put => scPut, _}
import scorex.util.encode.Base58

import java.util.NoSuchElementException
import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}

class IODBKeyValueStorage(storage: LSMStore, cacheExpire: Duration, cacheSize: Int)(implicit system: ActorSystem)
    extends KeyValueStorage {

  import system.dispatcher

  private val blockingExecutionContext: ExecutionContext =
    system.dispatchers.lookup(Dispatchers.DefaultBlockingDispatcherId)

  implicit private val cache: CaffeineCache[Array[Byte]] = {
    val underlying = Caffeine.newBuilder().maximumSize(cacheSize).build[String, Entry[Array[Byte]]]
    CaffeineCache(underlying)
  }

  override def get(key: Array[Byte]): EitherT[Future, KeyValueStorage.Error, Array[Byte]] =
    EitherT(
      cachingF(toCacheKey(key))(ttl = Some(cacheExpire))(
        blockingOperation(
          storage.get(ByteArrayWrapper(key)).map(_.data)
        )
          .flatMap {
            case Some(bytes) => Future.successful(bytes)
            case _           => Future.failed(new NoSuchElementException)
          }
      )
        .map(v => Right(v))
        .recover {
          case _: NoSuchElementException => Left(KeyValueStorage.NotFound(key))
          case t                         => Left(KeyValueStorage.ExceptionError(t))
        }
    )

  override def put(version: Array[Byte])(
    items:                  (Array[Byte], Array[Byte])*
  ): EitherT[Future, KeyValueStorage.Error, Done] = {
    items.foreach { case (key, value) => scPut(toCacheKey(key))(value, ttl = Some(cacheExpire)) }

    EitherT(
      blockingOperation(
        storage.update(
          ByteArrayWrapper(version),
          Nil,
          items.map { case (key, value) => ByteArrayWrapper(key) -> ByteArrayWrapper(value) }
        )
      )
        .map(_ => Right(Done))
        .recover { case e => Left(KeyValueStorage.ExceptionError(e)) }
    )
  }

  override def delete(version: Array[Byte])(keys: Array[Byte]*): EitherT[Future, KeyValueStorage.Error, Done] =
    EitherT.leftT(KeyValueStorage.ExceptionError(new UnsupportedOperationException))

  override def contains(key: Array[Byte]): EitherT[Future, KeyValueStorage.Error, Boolean] =
    get(key)
      .map(_ => true)
      .leftFlatMap {
        case KeyValueStorage.NotFound(_) => EitherT.rightT(false)
        case e                           => EitherT.leftT(e)
      }

  override def rollbackTo(version: Array[Byte]): EitherT[Future, KeyValueStorage.Error, Done] =
    EitherT(
      cache
        .removeAll()
        .flatMap(_ => blockingOperation(storage.rollback(ByteArrayWrapper(version))))
        .map(_ => Right(Done))
        .recover { case e => Left(KeyValueStorage.ExceptionError(e)) }
    )

  private def blockingOperation[R](f: => R): Future[R] =
    Future(f)(blockingExecutionContext)

  private def toCacheKey(bytes: Array[Byte]): String =
    Base58.encode(bytes)
}
