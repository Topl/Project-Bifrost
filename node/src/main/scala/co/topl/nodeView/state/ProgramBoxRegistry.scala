package co.topl.nodeView.state

import co.topl.modifier.box.{BoxId, ProgramBox, ProgramId}
import co.topl.db.LDBVersionedStore
import co.topl.nodeView.state.MinimalState.VersionTag
import co.topl.settings.AppSettings
import co.topl.utils.Logging

import java.io.File
import scala.util.{Failure, Success, Try}

/**
 * A registry containing mapping from fixed programId -> changing boxId
 *
 * @param storage Persistent storage object for saving the ProgramBoxRegistry to disk
 */
class ProgramBoxRegistry(protected val storage: LDBVersionedStore)
    extends Registry[ProgramBoxRegistry.K, ProgramBoxRegistry.V] {

  import ProgramBoxRegistry.{K, V}

  //----- input and output transformation functions
  override protected val registryInput: K => Array[Byte] = (key: K) => key.bytes

  override protected val registryOutput: Array[Byte] => Seq[V] = (value: Array[Byte]) => Seq(BoxId(value))

  override protected val registryOut2StateIn: (K, V) => V = (_, value: V) => value

  /** Helper function to retrieve boxes out of state */
  protected[state] def getBox(key: K, stateReader: SR): Option[ProgramBox] =
    super.getBox[ProgramBox](key, stateReader).map(_.head)

  /**
   * @param newVersion - block id
   * @param toRemove   map of public keys to a sequence of boxIds that should be removed
   * @param toAppend   map of public keys to a sequence of boxIds that should be added
   * @return - instance of updated ProgramBoxRegistry
   *
   *         Runtime complexity of below function is O(MN) + O(L)
   *         where M = Number of boxes to remove
   *         N = Number of boxes owned by a public key
   *         L = Number of boxes to append
   */
  // todo: James - this needs to be updated similarly to TokenBoxRegistry
  protected[state] def update(
    newVersion: VersionTag,
    toRemove:   Map[K, Seq[V]],
    toAppend:   Map[K, Seq[V]]
  ): Try[ProgramBoxRegistry] =
    Try {
      // look for addresses that will be empty after the update
      val (deleted: Seq[K], updated: Seq[(K, V)]) =
        // make a list of all accounts to consider then loop through them and determine their new state
        (toRemove.keys ++ toAppend.keys)
          .map { key =>
            val current = lookup(key).getOrElse(Seq())

            // case where the program id no longer exists
            if (current.forall(toRemove.getOrElse(key, Seq()).contains) && !toAppend.contains(key)) {
              (Some(key), None)

              // case where the boxId must be updated
            } else {
              (None, Some((key, toAppend(key).head)))
            }
          }
          .foldLeft((Seq[K](), Seq[(K, V)]()))((acc, progId) => (acc._1 ++ progId._1, acc._2 ++ progId._2))

      storage.update(
        newVersion.bytes,
        deleted.map(k => registryInput(k)),
        updated.map { case (key, value) =>
          registryInput(key) -> value.hash.value
        }
      )

    } match {
      case Success(_) =>
        log.debug(s"${Console.GREEN} Update ProgramBoxRegistry to version: ${newVersion.toString}${Console.RESET}")
        Success(new ProgramBoxRegistry(storage))

      case Failure(ex) => Failure(ex)
    }

  override def rollbackTo(version: VersionTag): Try[ProgramBoxRegistry] = Try {
    if (storage.lastVersionID.exists(_ sameElements version.bytes)) {
      this
    } else {
      log.debug(s"Rolling back ProgramBoxRegistry to: ${version.toString}")
      storage.rollbackTo(version.bytes)
      new ProgramBoxRegistry(storage)
    }
  }
}

object ProgramBoxRegistry extends Logging {

  type K = ProgramId
  type V = BoxId

  def readOrGenerate(settings: AppSettings): Option[ProgramBoxRegistry] =
    if (settings.application.enablePBR) {
      log.info("Initializing state with Program Box Registry")

      val dataDir = settings.application.dataDir.ensuring(_.isDefined, "data dir must be specified").get

      val file = new File(s"$dataDir/programBoxRegistry")
      file.mkdirs()
      val storage = new LDBVersionedStore(file, 1000)

      Some(new ProgramBoxRegistry(storage))

    } else None
}
