package co.topl.client

import co.topl.akkahttprpc.{CustomError, RpcClientFailure, RpcErrorFailure}
import co.topl.attestation.keyManagement.{KeyRing, KeyfileCurve25519, KeyfileCurve25519Companion, PrivateKeyCurve25519}
import co.topl.attestation.{Address, EvidenceProducer, Proof, Proposition}
import co.topl.modifier.transaction._
import co.topl.utils.Identifiable
import co.topl.utils.NetworkType.NetworkPrefix
import io.circe.Json

import scala.collection.immutable.{ListMap, Set}
import scala.util.{Failure, Success}

object Brambl {
  type KeyRing_PK25519 = KeyRing[PrivateKeyCurve25519, KeyfileCurve25519]

  /**
   * Helper function to import a keyfile from JSON format into the keyring
   * @param keyfile json representation of the keyfile to be imported
   * @param password the password used to decrypt the private key from the keyfile
   * @return if successful, the address will be returned and the key is now available in the keyring
   */
  def importCurve25519JsonToKeyRing(keyfile: Json, password: String, keyRing: KeyRing_PK25519)(implicit
    networkPrefix:                           NetworkPrefix
  ): Either[RpcClientFailure, Address] =
    KeyfileCurve25519.jsonDecoder(networkPrefix)(keyfile.hcursor) match {
      case Left(_) => Left(RpcErrorFailure(CustomError(7091, "Failed to decode JSON key")))
      case Right(kf) =>
        keyRing.importKeyPair(kf, password) match {
          case Failure(ex) =>
            Left(RpcErrorFailure(CustomError(7091, s"Failed to import JSON key to key ring. Reason: ${ex.getMessage}")))
          case Success(value) => Right(value)
        }
    }

  /**
   * Generatees a new keyfile and address in the keyring
   * @param password string used to encrypt the returned keyfile
   * @return
   */
  def generateNewCurve25519Keyfile(password: String, keyRing: KeyRing_PK25519)(implicit
    networkPrefix:                           NetworkPrefix
  ): Either[RpcClientFailure, KeyfileCurve25519] =
    keyRing.generateNewKeyPairs() match {
      case Failure(_) => Left(RpcErrorFailure(CustomError(7091, "Error occurred during key creation")))
      case Success(value) =>
        value match {
          case s: Set.Set1[PrivateKeyCurve25519] => Right(KeyfileCurve25519Companion.encryptSecret(s.head, password))
          case _ =>
            Left(RpcErrorFailure(CustomError(7091, "More than one key was generated when only was was asked for")))
        }
    }

  /**
   * Signs a provided transactions using the key associated with a particular address
   * @param address address used to lookup the private key needed to generate a signature
   * @param transaction transaction to be signed
   * @return a transaction with the
   */
  def signTransaction[P <: Proposition: EvidenceProducer: Identifiable, TX <: Transaction[_, P]](
    addresses:   Set[Address],
    transaction: TX
  )(f:           Address => Array[Byte] => Map[P, Proof[P]]): Either[RpcClientFailure, Transaction.TX] = {

    val msg2Sign = transaction.messageToSign
    val signFunc = (addr: Address) => f(addr)(msg2Sign)
    val signatures = ListMap.from(addresses.map(signFunc).reduce(_ ++ _))

    // I know this is eliminated by erasure but unsure how to fix at the moment and I've already restrited
    // Brambl to only work with PublicKey props at the moment so this shouldn't fail.
    transaction match {
      case tx: ArbitTransfer[P @unchecked] => Right(tx.copy(attestation = signatures))
      case tx: PolyTransfer[P @unchecked]  => Right(tx.copy(attestation = signatures))
      case tx: AssetTransfer[P @unchecked] => Right(tx.copy(attestation = signatures))
      case _                               => Left(RpcErrorFailure(CustomError(7091, "Transaction type not supported")))
    }

  }

}
