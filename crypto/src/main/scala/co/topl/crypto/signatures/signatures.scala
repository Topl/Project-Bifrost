package co.topl.crypto

import io.estatico.newtype.macros.newtype

import scala.language.implicitConversions

/* Forked from https://github.com/input-output-hk/scrypto */

package object signatures {

  @newtype
  case class Signature(value: Array[Byte])

  type MessageToSign = Array[Byte]
}
