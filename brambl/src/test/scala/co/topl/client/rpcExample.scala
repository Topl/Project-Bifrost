package co.topl.client

import akka.actor.ActorSystem
import cats.data.{EitherT, NonEmptyChain}
import cats.implicits._
import co.topl.akkahttprpc.RpcClientFailure
import co.topl.akkahttprpc.implicits.client.rpcToClient
import co.topl.attestation.keyManagement.{KeyRing, KeyfileCurve25519, KeyfileCurve25519Companion, PrivateKeyCurve25519}
import co.topl.attestation.{Address, PublicKeyPropositionCurve25519}
import co.topl.attestation.AddressCodec.implicits._
import co.topl.client.Provider.PrivateTestNet
import co.topl.modifier.box.{AssetCode, AssetValue}
import co.topl.rpc.ToplRpc
import co.topl.rpc.ToplRpc.NodeView._
import co.topl.rpc.ToplRpc.Transaction.{BroadcastTx, RawArbitTransfer, RawAssetTransfer, RawPolyTransfer}
import co.topl.rpc.implicits.client._
import co.topl.utils.IdiomaticScalaTransition.implicits.toValidatedOps
import co.topl.utils.StringDataTypes.{Base58Data, Latin1Data}
import io.circe.syntax.EncoderOps

import scala.collection.immutable.ListMap
import scala.concurrent.{ExecutionContext, Future}

object exampleState {
  type RpcErrorOr[T] = EitherT[Future, RpcClientFailure, T]

  val provider: Provider = new PrivateTestNet(apiKey = "topl_the_world!")
  import provider._

  implicit val system: ActorSystem = ActorSystem()
  implicit val executionContext: ExecutionContext = system.dispatcher

  val externalAddress: Seq[Address] = List(
    "AUAvJqLKc8Un3C6bC4aj8WgHZo74vamvX8Kdm6MhtdXgw51cGfix",
    "AU9upSwu8MtmQz6EBMuv34bJ4G8i6Aw64xxRShJ3kpZRec5Ucp9Q",
    "AU9NkZmX5Pch2kUA28GUtv9m4bNaLNtKLoFXphcAAc9PUQXinXRm",
    "AU9avKWiVVPKyU9LoMqDpduS4knoLDMdPEK54qKDNBpdnAMwQZcS",
    "AU9Xs4B5HnsTiYGb7D71CCxg5mYhaQv1WH3ptfiGbV4LUGb87W54",
    "AUA3RmKwr39nVQFFTV1BQFELbFhJQVWfFDdS5YDx7r1om5UCbqef",
    "AU9dn9YhqL1YWxfemMfS97zjVXR6G9QX74XRq1jVLtP3snQtuuVk",
    "AUANVY6RqbJtTnQS1AFTQBjXMFYDknhV8NEixHFLmeZynMxVbp64",
    "AU9sKKy7MN7U9G6QeasZUMTirD6SeGQx8Sngmb9jmDgNB2EzA3rq",
    "AUAbSWQxzfoCN4FizrKKf6E1qCSRffHhjrvo2v7L6q8xFZ7pxKqh"
  ).map(Base58Data.unsafe).map(_.decodeAddress.getOrThrow())

  val keyRing: KeyRing[PrivateKeyCurve25519, KeyfileCurve25519] =
    KeyRing.empty[PrivateKeyCurve25519, KeyfileCurve25519]()(
      provider.networkPrefix,
      PrivateKeyCurve25519.secretGenerator,
      KeyfileCurve25519Companion
    )

  val assetCode: AssetCode = AssetCode(1: Byte, externalAddress.head, Latin1Data.unsafe("test_1"))

  def genKeys(): Unit = keyRing.generateNewKeyPairs(10, Some("test"))
  def clearKeyRing(): Unit = keyRing.addresses.map(keyRing.removeFromKeyring)

}

object CreateANewKeyInTheKeyRing {
  import exampleState._
  import provider._

  val genKeyfile: Either[RpcClientFailure, KeyfileCurve25519] = Brambl.generateNewCurve25519Keyfile("test", keyRing)

  def main(args: Array[String]): Unit =
    genKeyfile match {
      case Left(value)  => println(s"Got some error: $value")
      case Right(value) => println(s"Got a success response: ${value.asJson}")
    }
}

object ReinstateAKeyFile {
  import exampleState._
  import provider._

  val response: Either[RpcClientFailure, Address] = for {
    keyfileJson <- CreateANewKeyInTheKeyRing.genKeyfile.map { keyfile =>
      println(s"keyRing after generating a new key: ${keyRing.addresses}")
      keyRing.removeFromKeyring(keyfile.address) // side effect mutation of keyRing
      println(s"keyRing after removing generated key: ${keyRing.addresses}")
      keyfile.asJson
    }
    address <- Brambl.importCurve25519JsonToKeyRing(keyfileJson, "test", keyRing)
  } yield {
    println(s"keyRing after re-importing the generated key from Json: ${keyRing.addresses}")
    address
  }

  def main(args: Array[String]): Unit =
    response match {
      case Left(value)  => println(s"Got some error: $value")
      case Right(value) => println(s"Got a success response: $value")
    }
}

object FailedToReinstateAKeyFile {
  import exampleState._
  import provider._

  val response: Either[RpcClientFailure, Address] = for {
    keyfileJson <- CreateANewKeyInTheKeyRing.genKeyfile.map { keyfile =>
      println(s"keyRing after generating a new key: ${keyRing.addresses}")
      keyRing.removeFromKeyring(keyfile.address) // side effect mutation of keyRing
      println(s"keyRing after removing generated key: ${keyRing.addresses}")
      keyfile.asJson
    }
    address <- Brambl.importCurve25519JsonToKeyRing(keyfileJson, "someOtherPassword", keyRing)
  } yield {
    println(s"keyRing after re-importing the generated key from Json: ${keyRing.addresses}")
    address
  }

  def main(args: Array[String]): Unit =
    response match {
      case Left(value)  => println(s"Got some error: $value")
      case Right(value) => println(s"Got a success response: $value")
    }
}

object CreateAnDSendRawPolyTransfer {

  /** import starter state */
  import exampleState._

  /** bring application specific network and credential provider into scope */
  import provider._

  /** Required arguments to request a Poly transfer from Bifrost (as opposed to building the Transaction directly yourself) */
  val params: RawPolyTransfer.Params = ToplRpc.Transaction.RawPolyTransfer.Params(
    propositionType =
      PublicKeyPropositionCurve25519.typeString, // required fixed string for now, exciting possibilities in the future!
    sender =
      NonEmptyChain.fromSeq(externalAddress).get, // Set of addresses whose state you want to use for the transaction
    recipients =
      NonEmptyChain((externalAddress.head, 10)), // Chain of (Recipients, Value) tuples that represent the output boxes
    fee = 0, // fee to be paid to the network for the transaction (unit is nanoPoly)
    changeAddress = externalAddress.head, // who will get ALL the change from the transaction?
    data = None // upto 128 Latin-1 encoded characters of optional data
  )

  /**
   * Here we construct the BraodcastTx.Response which is the final response after
   * 1. Request the raw transaction from Bifrost
   * 2. Generate a signed copy of the transaction using all addresses in the keyRing
   * 3. Send the newly signed transaction back to Bifrost
   *
   * NOTE: The ToplRpc client implicits `import co.topl.rpc.implicits.client._` must be in scope to bring all of the
   * implicit Topl codecs into scope.
   */
  val response: RpcErrorOr[BroadcastTx.Response] = for {
    rawTx <- ToplRpc.Transaction.RawPolyTransfer.rpc(params).map(_.rawTx)
//    signTx <- EitherT(Future(Brambl.signTransaction(keyRing.addresses, rawTx)(keyRing.generateAttestation)))
    signTx <- EitherT.right {
      clearKeyRing()
      genKeys()
      val msg2Sign = rawTx.messageToSign
      val signFunc = (addr: Address) => keyRing.generateAttestation(addr)(msg2Sign)
      val signatures = ListMap.from(keyRing.addresses.map(signFunc).reduce(_ ++ _))
      Future(rawTx.copy(attestation = signatures))
    }
    broadcastTx <- ToplRpc.Transaction.BroadcastTx.rpc(ToplRpc.Transaction.BroadcastTx.Params(signTx))
  } yield broadcastTx

  def main(args: Array[String]): Unit =
    response.value.foreach {
      case Left(value)  => println(s"Got some error: $value")
      case Right(value) => println(s"Got a success response: $value")
    }

}

object CreateAnDSendRawArbitTransfer {

  import exampleState._
  import provider._

  val params: RawArbitTransfer.Params = ToplRpc.Transaction.RawArbitTransfer.Params(
    propositionType = PublicKeyPropositionCurve25519.typeString,
    sender = NonEmptyChain.fromSeq(externalAddress).get,
    recipients = NonEmptyChain((externalAddress.head, 10)),
    fee = 0,
    changeAddress = externalAddress.head,
    consolidationAddress = externalAddress.head,
    data = None
  )

  val response: RpcErrorOr[BroadcastTx.Response] = for {
    rawTx <- ToplRpc.Transaction.RawArbitTransfer.rpc(params).map(_.rawTx)
    signTx <- EitherT.right {
      clearKeyRing()
      genKeys()
      val msg2Sign = rawTx.messageToSign
      val signFunc = (addr: Address) => keyRing.generateAttestation(addr)(msg2Sign)
      val signatures = ListMap.from(keyRing.addresses.map(signFunc).reduce(_ ++ _))
      Future(rawTx.copy(attestation = signatures))
    }
    broadcastTx <- ToplRpc.Transaction.BroadcastTx.rpc(ToplRpc.Transaction.BroadcastTx.Params(signTx))
  } yield broadcastTx

  def main(args: Array[String]): Unit =
    response.value.foreach {
      case Left(value)  => println(s"Got some error: $value")
      case Right(value) => println(s"Got a success response: $value")
    }

}

object CreateAnDSendRawAssetMintingTransfer {

  import exampleState._
  import provider._

  val params: RawAssetTransfer.Params = ToplRpc.Transaction.RawAssetTransfer.Params(
    propositionType = PublicKeyPropositionCurve25519.typeString,
    sender = NonEmptyChain(externalAddress.head),
    recipients = NonEmptyChain((externalAddress.head, AssetValue(100, assetCode))),
    fee = 0,
    changeAddress = externalAddress.head,
    consolidationAddress = externalAddress.head,
    minting = true,
    data = None
  )

  val response: RpcErrorOr[BroadcastTx.Response] = for {
    rawTx <- ToplRpc.Transaction.RawAssetTransfer.rpc(params).map(_.rawTx)
    signTx <- EitherT.right {
      clearKeyRing()
      genKeys()
      val msg2Sign = rawTx.messageToSign
      val signFunc = (addr: Address) => keyRing.generateAttestation(addr)(msg2Sign)
      val signatures = ListMap.from(keyRing.addresses.map(signFunc).reduce(_ ++ _))
      Future(rawTx.copy(attestation = signatures))
    }
    broadcastTx <- ToplRpc.Transaction.BroadcastTx.rpc(ToplRpc.Transaction.BroadcastTx.Params(signTx))
  } yield broadcastTx

  def main(args: Array[String]): Unit =
    response.value.foreach {
      case Left(value)  => println(s"Got some error: $value")
      case Right(value) => println(s"Got a success response: $value")
    }
}

object CreateAnDSendRawAssetTransfer {

  import exampleState._
  import provider._

  val params: RawAssetTransfer.Params = ToplRpc.Transaction.RawAssetTransfer.Params(
    propositionType = PublicKeyPropositionCurve25519.typeString,
    sender = NonEmptyChain(externalAddress.head),
    recipients = NonEmptyChain((externalAddress.head, AssetValue(100, assetCode))),
    fee = 0,
    changeAddress = externalAddress.head,
    consolidationAddress = externalAddress.head,
    minting = false,
    data = None
  )

  val response: RpcErrorOr[BroadcastTx.Response] = for {
    rawTx <- ToplRpc.Transaction.RawAssetTransfer.rpc(params).map(_.rawTx)
    signTx <- EitherT.right {
      clearKeyRing()
      genKeys()
      val msg2Sign = rawTx.messageToSign
      val signFunc = (addr: Address) => keyRing.generateAttestation(addr)(msg2Sign)
      val signatures = ListMap.from(keyRing.addresses.map(signFunc).reduce(_ ++ _))
      Future(rawTx.copy(attestation = signatures))
    }
    broadcastTx <- ToplRpc.Transaction.BroadcastTx.rpc(ToplRpc.Transaction.BroadcastTx.Params(signTx))
  } yield broadcastTx

  def main(args: Array[String]): Unit =
    response.value.foreach {
      case Left(value)  => println(s"Got some error: $value")
      case Right(value) => println(s"Got a success response: $value")
    }

}

object LookupBalance {

  import exampleState._
  import provider._

  val params: Balances.Params = ToplRpc.NodeView.Balances.Params(externalAddress.toList)
  val response: RpcErrorOr[Balances.Response] = ToplRpc.NodeView.Balances.rpc(params)

  def main(args: Array[String]): Unit =
    response.value.foreach {
      case Left(value)  => println(s"Got some error: $value")
      case Right(value) => println(s"Got a success response: $value")
    }

}

object GetMempool {

  import exampleState._
  import provider._

  val params: Mempool.Params = ToplRpc.NodeView.Mempool.Params()
  val response: RpcErrorOr[Mempool.Response] = ToplRpc.NodeView.Mempool.rpc(params)

  def main(args: Array[String]): Unit =
    response.value.foreach {
      case Left(value)  => println(s"Got some error: $value")
      case Right(value) => println(s"Got a success response: $value")
    }

}

object GetHeadOfChain {

  import exampleState._
  import provider._

  val params: Head.Params = ToplRpc.NodeView.Head.Params()
  val response: RpcErrorOr[Head.Response] = ToplRpc.NodeView.Head.rpc(params)

  def main(args: Array[String]): Unit =
    response.value.foreach {
      case Left(value)  => println(s"Got some error: $value")
      case Right(value) => println(s"Got a success response: $value")
    }

}

object LookupTransactionById {

  import exampleState._
  import provider._

  val response: RpcErrorOr[TransactionById.Response] = for {
    params <- GetHeadOfChain.response.map { head =>
      ToplRpc.NodeView.TransactionById.Params(head.bestBlock.transactions.head.id)
    }
    transaction <- ToplRpc.NodeView.TransactionById.rpc(params)
  } yield transaction

  def main(args: Array[String]): Unit =
    response.value.foreach {
      case Left(value)  => println(s"Got some error: $value")
      case Right(value) => println(s"Got a success response: $value")
    }

}

object LookupTransactionFromMempool {

  import exampleState._
  import provider._

  val response: RpcErrorOr[TransactionFromMempool.Response] = for {
    params <- GetHeadOfChain.response.map { head =>
      ToplRpc.NodeView.TransactionFromMempool.Params(head.bestBlock.transactions.head.id)
    }
    transaction <- ToplRpc.NodeView.TransactionFromMempool.rpc(params)
  } yield transaction

  def main(args: Array[String]): Unit =
    response.value.foreach {
      case Left(value)  => println(s"Got some error: $value")
      case Right(value) => println(s"Got a success response: $value")
    }

}

object LookupBlockById {

  import exampleState._
  import provider._

  val response: RpcErrorOr[BlockById.Response] = for {
    params <- GetHeadOfChain.response.map(head => ToplRpc.NodeView.BlockById.Params(head.bestBlockId))
    block  <- ToplRpc.NodeView.BlockById.rpc(params)
  } yield block

  def main(args: Array[String]): Unit =
    response.value.foreach {
      case Left(value)  => println(s"Got some error: $value")
      case Right(value) => println(s"Got a success response: $value")
    }

}

object LookupBlockByHeight {

  import exampleState._
  import provider._

  val params: BlockByHeight.Params = ToplRpc.NodeView.BlockByHeight.Params(1)
  val response: RpcErrorOr[BlockByHeight.Response] = ToplRpc.NodeView.BlockByHeight.rpc(params)

  def main(args: Array[String]): Unit =
    response.value.foreach {
      case Left(value)  => println(s"Got some error: $value")
      case Right(value) => println(s"Got a success response: $value")
    }

}
