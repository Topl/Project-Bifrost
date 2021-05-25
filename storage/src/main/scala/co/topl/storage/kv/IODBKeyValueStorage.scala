package co.topl.storage.kv

import akka.Done
import akka.actor.ActorSystem
import akka.dispatch.Dispatchers
import cats.data._
import cats.implicits._
import co.topl.utils.IdiomaticScalaTransition.implicits.toValidatedOps
import co.topl.utils.codecs._
import co.topl.utils.codecs.implicits._
import com.github.benmanes.caffeine.cache.Caffeine
import io.iohk.iodb.{ByteArrayWrapper, LSMStore}
import scalacache.caffeine._
import scalacache.modes.scalaFuture._
import scalacache.{put => scPut, _}
import scorex.util.encode.Base58

import java.util.NoSuchElementException
import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}

class IODBKeyValueStorage[Version, Key, Value](storage: LSMStore, cacheExpire: Duration, cacheSize: Int)(implicit
  system:                                               ActorSystem,
  versionAsBytes:                                       AsBytes[Any, Version],
  keyAsBytes:                                           AsBytes[Any, Key],
  valueAsBytes:                                         AsBytes[Any, Value],
  valueFromBytes:                                       FromBytes[Any, Value]
) extends KeyValueStorage[Version, Key, Value] {

  import system.dispatcher

  private val blockingExecutionContext: ExecutionContext =
    system.dispatchers.lookup(Dispatchers.DefaultBlockingDispatcherId)

  implicit private val cache: CaffeineCache[Value] = {
    val underlying = Caffeine.newBuilder().maximumSize(cacheSize).build[String, Entry[Value]]
    CaffeineCache(underlying)
  }

  override def get(key: Key): EitherT[Future, KeyValueStorage.Error, Value] =
    for {
      cacheKey <- EitherT.fromEither[Future](toCacheKey(key))
      storageKey <- EitherT.fromEither[Future](
        key.encodeAsBytes.toEither
          .leftMap(errors =>
            KeyValueStorage.DomainError(IODBKeyValueStorage.EncodeFailure(errors.head)): KeyValueStorage.Error
          )
          .map(ByteArrayWrapper(_))
      )
      value <- EitherT(
        cachingF(cacheKey)(ttl = Some(cacheExpire))(
          OptionT(blockingOperation(storage.get(storageKey)))
            .map(_.data.decodeTo[Any, Value].getOrThrow())
            .value
            .flatMap {
              case Some(value) => Future.successful(value)
              case _           => Future.failed(new NoSuchElementException)
            }
        )
          .map(v => Right(v))
          .recover {
            case _: NoSuchElementException => Left(KeyValueStorage.NotFound(key): KeyValueStorage.Error)
            case t                         => Left(KeyValueStorage.ExceptionError(t): KeyValueStorage.Error)
          }
      )
    } yield value

  override def put(version: Version)(
    items:                  (Key, Value)*
  ): EitherT[Future, KeyValueStorage.Error, Done] =
    for {
      cachableItems <- EitherT.fromEither[Future](items.toList.traverse { case (key, value) =>
        toCacheKey(key).map(_ -> value)
      })
      storableItems <- EitherT
        .fromEither[Future](items.toList.traverse { case (key, value) =>
          key.encodeAsBytes.toEither.flatMap(k =>
            value.encodeAsBytes.toEither.map(v => ByteArrayWrapper(k) -> ByteArrayWrapper(v))
          )
        })
        .leftMap(e => KeyValueStorage.DomainError(IODBKeyValueStorage.EncodeFailure(e.head)): KeyValueStorage.Error)
      versionBytes <- EitherT
        .fromEither[Future](version.encodeAsBytes.toEither)
        .leftMap(e => KeyValueStorage.DomainError(IODBKeyValueStorage.EncodeFailure(e.head)): KeyValueStorage.Error)
      _ = cachableItems.foreach { case (key, value) => scPut(key)(value, ttl = Some(cacheExpire)) }
      result <- EitherT(
        blockingOperation(
          storage.update(
            ByteArrayWrapper(versionBytes),
            Nil,
            storableItems
          )
        )
          .map(_ => Right(Done))
          .recover { case e => Left(KeyValueStorage.ExceptionError(e): KeyValueStorage.Error) }
      )
    } yield result

  override def delete(version: Version)(keys: Key*): EitherT[Future, KeyValueStorage.Error, Done] =
    EitherT.leftT(KeyValueStorage.ExceptionError(new UnsupportedOperationException))

  override def contains(key: Key): EitherT[Future, KeyValueStorage.Error, Boolean] =
    get(key)
      .map(_ => true)
      .leftFlatMap {
        case KeyValueStorage.NotFound(_) => EitherT.rightT(false)
        case e                           => EitherT.leftT(e)
      }

  override def rollbackTo(version: Version): EitherT[Future, KeyValueStorage.Error, Done] =
    EitherT
      .fromEither[Future](version.encodeAsBytes.toEither)
      .leftMap(e => KeyValueStorage.DomainError(IODBKeyValueStorage.EncodeFailure(e.head)): KeyValueStorage.Error)
      .flatMap(versionBytes =>
        EitherT(
          cache
            .removeAll()
            .flatMap(_ => blockingOperation(storage.rollback(ByteArrayWrapper(versionBytes))))
            .map(_ => Right(Done))
            .recover { case e => Left(KeyValueStorage.ExceptionError(e)) }
        )
      )

  override def close(): EitherT[Future, KeyValueStorage.Error, Done] =
    EitherT.liftF[Future, KeyValueStorage.Error, Done](blockingOperation(storage.close()).map(_ => Done))

  private def blockingOperation[R](f: => R): Future[R] =
    Future(f)(blockingExecutionContext)

  private def toCacheKey(key: Key): Either[KeyValueStorage.Error, String] =
    key.encodeAsBytes.toEither
      .leftMap(failures =>
        KeyValueStorage.DomainError(IODBKeyValueStorage.EncodeFailure(failures.head)): KeyValueStorage.Error
      )
      .map(Base58.encode)
}

object IODBKeyValueStorage {
  sealed abstract class Error
  case class EncodeFailure[Failure](failure: Failure) extends Error
  case class DecodeFailure[Failure](failure: Failure) extends Error
}
