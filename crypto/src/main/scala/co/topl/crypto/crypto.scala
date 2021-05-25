package co.topl

import cats.Eq
import co.topl.crypto.hash.digest
import io.estatico.newtype.macros.newtype

import scala.language.implicitConversions

package object crypto {

  @newtype
  case class PrivateKey(value: Array[Byte])

  object PrivateKey {

    trait Instances {
      implicit val eqPrivateKey: Eq[PrivateKey] = _.value sameElements _.value
    }
  }

  @newtype
  case class PublicKey(value: Array[Byte])

  @newtype
  case class SharedSecret(value: Array[Byte])

  object implicits
      extends digest.Instances
      with digest.Digest.ToDigestOps
      with digest.Extensions
      with hash.Instances
      with PrivateKey.Instances

}
