package co.topl.utils.serialization

import scala.language.implicitConversions

trait BytesSerializable extends Serializable {

  type M >: this.type <: BytesSerializable

  lazy val bytes: Array[Byte] = serializer.toBytes(this)

  def serializer: BifrostSerializer[M]
}
