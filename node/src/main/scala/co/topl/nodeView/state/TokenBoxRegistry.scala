package co.topl.nodeView.state

import co.topl.attestation.Address
import co.topl.modifier.box.{Box, BoxId, TokenBox, TokenValueHolder}
import co.topl.db.LDBVersionedStore
import co.topl.nodeView.state.MinimalState.VersionTag
import co.topl.settings.AppSettings
import co.topl.utils.Logging
import com.google.common.primitives.Longs

import java.io.File
import scala.util.Try

/**
 * A registry containing mappings from public keys to a sequence of boxIds
 *
 * @param storage Persistent storage object for saving the TokenBoxRegistry to disk
 * @param nodeKeys set of node keys that denote the state this node will maintain (useful for personal wallet nodes)
 */
class TokenBoxRegistry(protected val storage: LDBVersionedStore, nodeKeys: Option[Set[Address]])
    extends Registry[TokenBoxRegistry.K, TokenBoxRegistry.V] {

  import TokenBoxRegistry.{K, V}

  //----- input and output transformation functions
  override protected val registryInput: K => Array[Byte] = (key: K) => key.bytes

  override protected val registryOutput: Array[Byte] => Seq[V] =
    (value: Array[Byte]) => value.grouped(Longs.BYTES).toSeq.map(Longs.fromByteArray)

  override protected val registryOut2StateIn: (K, V) => BoxId = (key: K, value: V) =>
    BoxId.idFromEviNonce(key.evidence, value)

  protected[state] def getBox(key: K, state: SR): Option[Seq[TokenBox[TokenValueHolder]]] =
    super.getBox[TokenBox[TokenValueHolder]](key, state)

  /** Helper function to filter updates by node keys if they are present */
  private def filterByNodeKeys(updates: Map[K, Seq[V]]): Map[K, Seq[V]] = nodeKeys match {
    case Some(keys) => updates.filter(b => keys.contains(b._1))
    case None       => updates
  }

  /**
   * Updates the key-value store to a new version by updating keys with their new state. LSM Store.
   * @param newVersion - block id
   * @param toRemove map of public keys to a sequence of boxIds that should be removed
   * @param toAppend map of public keys to a sequence of boxIds that should be added
   * @return - instance of updated TokenBoxRegistry
   *
   *         Runtime complexity of below function is O(MN) + O(L)
   *         where M = Number of boxes to remove
   *         N = Number of boxes owned by an address
   *         L = Number of boxes to append
   */
  protected[state] def update(
    newVersion: VersionTag,
    toRemove:   Map[K, Seq[V]],
    toAppend:   Map[K, Seq[V]]
  ): Try[TokenBoxRegistry] = {
    val (filteredRemove, filteredAppend) = (filterByNodeKeys(toRemove), filterByNodeKeys(toAppend))

    val (deleted: Seq[K], updated: Seq[(K, Seq[V])]) = formatUpdates(filteredRemove, filteredAppend)

    saveToStore(newVersion, deleted, updated).map { _ =>
      log.debug(s"${Console.GREEN} Update TokenBoxRegistry to version: ${newVersion.toString}${Console.RESET}")
      new TokenBoxRegistry(storage, nodeKeys)
    }
  }

  /**
   * The algorithm below roughly outlines what is happening in this process (James Aman 2021.04.07)
   * - filter the changes by the keys we care about
   * - create the updated state for each address by constructing a list of all effected keys
   * - iterate through that list of addresses and evaluate their state
   *   - if it is empty, that address data needs to be removed in the key-value store
   *   - if it is non-empty, the updated state needs to be calculated and updated in the key-value store
   */
  private def formatUpdates(
    filteredRemove: Map[K, Seq[V]],
    filteredAppend: Map[K, Seq[V]]
  ): (Seq[K], Seq[(K, Seq[V])]) = {
    // helper function to find the updated state of an address from the given changes
    val newStateForAddress: K => Seq[V] = (address: K) => {
      val boxes = lookupRaw(address).getOrElse(Seq[V]())
      val toRemove = filteredRemove.getOrElse(address, Seq[V]())
      val toAppend = filteredAppend.getOrElse(address, Seq[V]())

      boxes.filterNot(toRemove.contains) ++ toAppend
    }

    // a sequence containing tuples for each affected key, the
    val combinedListOfUpdates: Iterable[(Option[K], Option[(K, Seq[V])])] =
      (filteredRemove.keys ++ filteredAppend.keys).map { address =>
        val newState = newStateForAddress(address)
        if (newState.isEmpty) (Some(address), None)
        else (None, Some((address, newState)))
      }

    // determine the new state of each account from the changes

    combinedListOfUpdates.foldLeft((Seq[K](), Seq[(K, Seq[V])]())) { (accumulator, address) =>
      (accumulator._1 ++ address._1, accumulator._2 ++ address._2)
    }
  }

  private def saveToStore(newVersion: VersionTag, toDelete: Seq[K], toUpdate: Seq[(K, Seq[V])]): Try[Unit] = Try {
    storage.update(
      newVersion.bytes,
      toDelete.map(k => registryInput(k)),
      toUpdate.map { case (key, value) =>
        registryInput(key) -> value.flatMap(Longs.toByteArray).toArray
      }
    )
  }

  override def rollbackTo(version: VersionTag): Try[TokenBoxRegistry] = Try {
    if (storage.lastVersionID.exists(_ sameElements version.bytes)) {
      this
    } else {
      log.debug(s"Rolling back TokenBoxRegistry to: ${version.toString}")
      storage.rollbackTo(version.bytes)
      new TokenBoxRegistry(storage, nodeKeys)
    }
  }
}

object TokenBoxRegistry extends Logging {

  type K = Address
  type V = Box.Nonce

  def readOrGenerate(settings: AppSettings, nodeKeys: Option[Set[Address]]): Option[TokenBoxRegistry] =
    if (settings.application.enableTBR) {
      log.info("Initializing state with Token Box Registry")

      val dataDir = settings.application.dataDir.ensuring(_.isDefined, "data dir must be specified").get

      val file = new File(s"$dataDir/tokenBoxRegistry")
      file.mkdirs()
      val storage = new LDBVersionedStore(file, 1000)

      Some(new TokenBoxRegistry(storage, nodeKeys))

    } else None
}
