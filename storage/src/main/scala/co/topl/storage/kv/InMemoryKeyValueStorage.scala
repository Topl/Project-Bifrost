package co.topl.storage.kv

import akka.Done
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.util.Timeout
import cats.data.EitherT
import scorex.util.encode.Base58

import scala.concurrent.Future

class InMemoryKeyValueStorage(implicit system: ActorSystem[_]) extends KeyValueStorage {
  private val actor = system.systemActorOf(InMemoryKeyValueStorage.StorageActor(), "kv-store")

  import akka.actor.typed.scaladsl.AskPattern._

  import scala.concurrent.duration._
  implicit private val timeout: Timeout = Timeout(1.seconds)

  override def get(key: Array[Byte]): EitherT[Future, KeyValueStorage.Error, Array[Byte]] =
    EitherT(actor.ask(InMemoryKeyValueStorage.StorageActor.Get(key, _)))

  override def put(version: Array[Byte])(
    items:                  (Array[Byte], Array[Byte])*
  ): EitherT[Future, KeyValueStorage.Error, Done] =
    EitherT(actor.ask(InMemoryKeyValueStorage.StorageActor.Put(version, items.toList, _)))

  override def delete(version: Array[Byte])(keys: Array[Byte]*): EitherT[Future, KeyValueStorage.Error, Done] =
    EitherT(actor.ask(InMemoryKeyValueStorage.StorageActor.Delete(version, keys.toList, _)))

  override def contains(key: Array[Byte]): EitherT[Future, KeyValueStorage.Error, Boolean] =
    EitherT(actor.ask(InMemoryKeyValueStorage.StorageActor.Contains(key, _)))

  override def rollbackTo(version: Array[Byte]): EitherT[Future, KeyValueStorage.Error, Done] =
    EitherT(actor.ask(InMemoryKeyValueStorage.StorageActor.RollbackTo(version, _)))
}

object InMemoryKeyValueStorage {

  private object StorageActor {
    sealed abstract class Message

    case class Get(key: Array[Byte], replyTo: ActorRef[Either[KeyValueStorage.Error, Array[Byte]]]) extends Message

    case class Put(
      version: Array[Byte],
      items:   List[(Array[Byte], Array[Byte])],
      replyTo: ActorRef[Either[KeyValueStorage.Error, Done]]
    ) extends Message

    case class Delete(
      version: Array[Byte],
      keys:    List[Array[Byte]],
      replyTo: ActorRef[Either[KeyValueStorage.Error, Done]]
    ) extends Message

    case class Contains(key: Array[Byte], replyTo: ActorRef[Either[KeyValueStorage.Error, Boolean]]) extends Message

    case class RollbackTo(version: Array[Byte], replyTo: ActorRef[Either[KeyValueStorage.Error, Done]]) extends Message

    private def keyify(key: Array[Byte]): String = Base58.encode(key)

    def apply(): Behavior[Message] = apply(State(Nil))

    def apply(state: State): Behavior[Message] =
      Behaviors.receiveMessage {
        case Get(key, replyTo) =>
          replyTo ! state.currentView.get(keyify(key)).toRight(KeyValueStorage.NotFound(key))
          Behaviors.same
        case Put(version, items, replyTo) =>
          val newState = state.copy(revisions = state.revisions :+ (keyify(version) -> items.map { case (key, value) =>
            State.Put(keyify(key), value)
          }))
          replyTo ! Right(Done)
          apply(newState)
        case Delete(version, keys, replyTo) =>
          val newState =
            state.copy(revisions = state.revisions :+ (keyify(version) -> keys.map(keyify).map(State.Remove)))
          replyTo ! Right(Done)
          apply(newState)
        case RollbackTo(version, replyTo) =>
          val versionKey = keyify(version)
          val newState =
            state.copy(revisions = state.revisions.take(state.revisions.lastIndexWhere(_._1 == versionKey) + 1))
          replyTo ! Right(Done)
          apply(newState)
      }

    case class State(
      revisions: List[(String, List[State.Change])]
    ) {

      lazy val currentView: Map[String, Array[Byte]] =
        revisions.map(_._2).foldLeft(Map.empty[String, Array[Byte]]) { case (curr, next) =>
          next.foldLeft(curr) {
            case (subCurr, State.Put(key, value)) => subCurr + (key -> value)
            case (subCurr, State.Remove(key))     => subCurr - key
          }
        }
    }

    object State {
      sealed abstract class Change
      case class Put(key: String, value: Array[Byte]) extends Change
      case class Remove(key: String) extends Change
    }
  }
}
