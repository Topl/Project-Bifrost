package bifrost.modifier.transaction.bifrostTransaction

import bifrost.crypto.{ PrivateKey25519, Signature25519 }
import bifrost.modifier.box._
import bifrost.modifier.box.proposition.{ ProofOfKnowledgeProposition, PublicKey25519Proposition }
import bifrost.modifier.transaction.BoxTransaction
import bifrost.nodeView.NodeViewModifier
import bifrost.nodeView.NodeViewModifier.ModifierTypeId
import bifrost.wallet.Wallet
import com.google.common.primitives.Longs
import scorex.util.encode.Base58
import scorex.crypto.signatures.{PublicKey, Signature}
import supertagged.@@

trait TransactionSettings

trait Transaction extends BoxTransaction[ProofOfKnowledgeProposition[PrivateKey25519], Any, Box] {
  lazy val bloomTopics: Option[IndexedSeq[Array[Byte]]] = None

  override val modifierTypeId: ModifierTypeId = Transaction.modifierTypeId

  val boxIdsToOpen: IndexedSeq[Array[Byte]]
}

object Transaction {
  type Nonce = Long
  type Value = Long

  val modifierTypeId: Byte @@ NodeViewModifier.ModifierTypeId.Tag = ModifierTypeId @@ (2: Byte)

  def stringToPubKey ( rawString: String ): PublicKey25519Proposition =
    PublicKey25519Proposition(PublicKey @@ Base58.decode(rawString).get)

  def stringToSignature ( rawString: String ): Signature25519 = Signature25519(Signature @@ Base58.decode(rawString).get)

  def nonceFromDigest ( digest: Array[Byte] ): Nonce = Longs.fromByteArray(digest.take(Longs.BYTES))

  def signTx ( w: Wallet, props: IndexedSeq[PublicKey25519Proposition], message: Array[Byte] ):
  Map[PublicKey25519Proposition, Signature25519] = props.map { prop =>
    val secret = w.secretByPublicImage(prop).get
    val signature = PrivateKey25519.sign(secret, message)
    prop -> signature
  }.toMap
}
