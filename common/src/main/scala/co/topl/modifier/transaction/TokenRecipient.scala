package co.topl.modifier.transaction

import co.topl.attestation.{Address, Evidence}
import co.topl.modifier.box.Box.Nonce
import co.topl.modifier.box._

/*
JAA - This is the start of an attempt to create a general multi-token transaction. The idea was to replace the 'to'
with a list of TokenRecipients
 */

case class TokenRecipient[T <: TokenValueHolder, BX <: TokenBox[T]](
  address:   Address,
  outputBox: TokenBoxOutput[T, BX],
  value:     TokenValueHolder
)

sealed abstract class TokenBoxOutput[T <: TokenValueHolder, BX <: TokenBox[T]] {
  def generateBox: (Evidence, Box.Nonce, T) => BX
}

case object ArbitBoxOutput extends TokenBoxOutput[SimpleValue, ArbitBox] {
  override def generateBox: (Evidence, Nonce, SimpleValue) => ArbitBox = ArbitBox.apply
}

case object PolyBoxOutput extends TokenBoxOutput[SimpleValue, PolyBox] {
  override def generateBox: (Evidence, Nonce, SimpleValue) => PolyBox = PolyBox.apply
}

case object AssetBoxOutput extends TokenBoxOutput[AssetValue, AssetBox] {
  override def generateBox: (Evidence, Nonce, AssetValue) => AssetBox = AssetBox.apply
}
