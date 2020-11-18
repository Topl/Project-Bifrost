package co.topl.api.program

import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.{HttpEntity, HttpMethods, HttpRequest, MediaTypes}
import akka.pattern.ask
import akka.util.ByteString
import co.topl.api.RPCMockState
import co.topl.nodeView.NodeViewHolder.ReceivableMessages.GetDataFromCurrentView
import co.topl.nodeView.history.History
import co.topl.nodeView.mempool.MemPool
import co.topl.nodeView.state.State
import co.topl.nodeView.state.box._
import co.topl.nodeView.state.box.proposition.PublicKey25519Proposition
import co.topl.nodeView.{CurrentView, state}
import io.circe.syntax._
import org.scalatest.matchers.should

import scala.concurrent.Await
import scala.concurrent.duration._

trait ProgramRPCMockState extends RPCMockState
  with should.Matchers {



  def httpPOST(jsonRequest: ByteString): HttpRequest = {
    HttpRequest(
      HttpMethods.POST,
      uri = "/program/",
      entity = HttpEntity(MediaTypes.`application/json`, jsonRequest)
    ).withHeaders(RawHeader("x-api-key", "test_key"))
  }

  protected def view(): CurrentView[History, State, MemPool] = Await.result(
    (nodeViewHolderRef ? GetDataFromCurrentView).mapTo[CurrentView[History, State, MemPool]],
    10.seconds)

  def directlyAddPBRStorage(version: Int, boxes: Seq[ProgramBox]): Unit = {
    // Manually manipulate state
    state.directlyAddPBRStorage(version, boxes, view().state)
  }

  lazy val (signSk, signPk) = sampleUntilNonEmpty(keyPairSetGen).head

  val publicKeys = Map(
    "investor" -> "6sYyiTguyQ455w2dGEaNbrwkAWAEYV1Zk6FtZMknWDKQ",
    "producer" -> "A9vRt6hw7w4c7b4qEkQHYptpqBGpKM5MGoXyrkGCbrfb",
    "hub" -> "F6ABtYMsJABDLH2aj7XVPwQr5mH7ycsCE4QGQrLeB3xU"
    )

  val publicKey: PublicKey25519Proposition = propositionGen.sample.get

  val polyBoxes: Seq[TokenBox] = view().state.getTokenBoxes(publicKey).getOrElse(Seq())

  val fees: Map[String, Int] = Map(publicKey.toString -> 500)

  val program: String =
    s"""
       |var a = 0
       |var b = 1
       |
       |add = function(x,y) {
       |  a = x + y
       |  return a
       |}
       |""".stripMargin

  val stateBox: StateBox = StateBox(publicKey, 0L, programIdGen.sample.get, Map("a" -> 0, "b" -> 1).asJson)
  val codeBox: CodeBox = CodeBox(publicKey, 1L, programIdGen.sample.get, Seq("add = function(x,y) { a = x + y; return a }"), Map("add" -> Seq("Number", "Number")))
  val executionBox: ExecutionBox = ExecutionBox(publicKey, 2L, programIdGen.sample.get, Seq(stateBox.value), Seq(codeBox.value))
}
