package co.topl.nodeView.state

import co.topl.nodeView.KeyValueStore
import io.iohk.iodb.ByteArrayWrapper

trait StoreInterface extends AutoCloseable {

  protected val storage: KeyValueStore

  /** method to retrieve data from storage */
  protected def getFromStorage(id: Array[Byte]): Option[Array[Byte]] =
    storage.get(ByteArrayWrapper(id)).map(_.data)

  /** method to close storage on termination */
  override def close(): Unit =
    storage.close()

}
