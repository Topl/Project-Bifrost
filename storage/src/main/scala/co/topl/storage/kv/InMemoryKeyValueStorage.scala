package co.topl.storage.kv

import akka.Done
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.util.Timeout
import cats.data.EitherT
import cats.implicits._

import scala.concurrent.Future

class InMemoryKeyValueStorage[Version, Key, Value](implicit system: ActorSystem[_])
    extends KeyValueStorage[Version, Key, Value] {
  private val actor = system.systemActorOf(StorageActor(), "kv-store")

  import system.executionContext

  import akka.actor.typed.scaladsl.AskPattern._

  import scala.concurrent.duration._
  implicit private val timeout: Timeout = Timeout(1.seconds)

  override def get(key: Key): EitherT[Future, KeyValueStorage.Error, Value] =
    EitherT(actor.ask(StorageActor.Get(key, _)))

  override def put(version: Version)(
    items:                  (Key, Value)*
  ): EitherT[Future, KeyValueStorage.Error, Done] =
    EitherT(actor.ask(StorageActor.Put(version, items.toList, _)))

  override def delete(version: Version)(keys: Key*): EitherT[Future, KeyValueStorage.Error, Done] =
    EitherT(actor.ask(StorageActor.Delete(version, keys.toList, _)))

  override def contains(key: Key): EitherT[Future, KeyValueStorage.Error, Boolean] =
    EitherT(actor.ask(StorageActor.Contains(key, _)))

  override def rollbackTo(version: Version): EitherT[Future, KeyValueStorage.Error, Done] =
    EitherT(actor.ask(StorageActor.RollbackTo(version, _)))

  override def close(): EitherT[Future, KeyValueStorage.Error, Done] =
    EitherT.liftF[Future, KeyValueStorage.Error, Done](actor.ask(StorageActor.Stop(_)))

  private object StorageActor {
    sealed abstract class Message

    case class Get(key: Key, replyTo: ActorRef[Either[KeyValueStorage.Error, Value]]) extends Message

    case class Put(
      version: Version,
      items:   List[(Key, Value)],
      replyTo: ActorRef[Either[KeyValueStorage.Error, Done]]
    ) extends Message

    case class Delete(
      version: Version,
      keys:    List[Key],
      replyTo: ActorRef[Either[KeyValueStorage.Error, Done]]
    ) extends Message

    case class Contains(key: Key, replyTo: ActorRef[Either[KeyValueStorage.Error, Boolean]]) extends Message

    case class RollbackTo(version: Version, replyTo: ActorRef[Either[KeyValueStorage.Error, Done]]) extends Message

    case class Stop(replyTo: ActorRef[Done]) extends Message

    def apply(): Behavior[Message] = apply(State(Nil))

    def apply(state: State): Behavior[Message] =
      Behaviors.receiveMessage {
        case Get(key, replyTo) =>
          replyTo ! state.currentView.get(key).toRight(KeyValueStorage.NotFound(key))
          Behaviors.same
        case Put(version, items, replyTo) =>
          val newState = state.copy(revisions = state.revisions :+ (version -> items.map { case (key, value) =>
            State.Put(key, value)
          }))
          replyTo ! Right(Done)
          apply(newState)
        case Delete(version, keys, replyTo) =>
          val newState =
            state.copy(revisions = state.revisions :+ (version -> keys.map(State.Remove)))
          replyTo ! Right(Done)
          apply(newState)
        case RollbackTo(version, replyTo) =>
          val newState =
            state.copy(revisions = state.revisions.take(state.revisions.lastIndexWhere(_._1 == version) + 1))
          replyTo ! Right(Done)
          apply(newState)
        case Contains(key, replyTo) =>
          replyTo ! state.currentView.contains(key).asRight[KeyValueStorage.Error]
          Behaviors.same
        case Stop(replyTo) =>
          replyTo ! Done
          Behaviors.stopped
      }

    case class State(
      revisions: List[(Version, List[State.Change])]
    ) {

      lazy val currentView: Map[Key, Value] =
        revisions.map(_._2).foldLeft(Map.empty[Key, Value]) { case (curr, next) =>
          next.foldLeft(curr) {
            case (subCurr, State.Put(key, value)) => subCurr + (key -> value)
            case (subCurr, State.Remove(key))     => subCurr - key
          }
        }
    }

    object State {
      sealed abstract class Change
      case class Put(key: Key, value: Value) extends Change
      case class Remove(key: Key) extends Change
    }
  }
}
