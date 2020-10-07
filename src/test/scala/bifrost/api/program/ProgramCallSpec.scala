package bifrost.api.program

import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.util.ByteString
import bifrost.http.api.routes.ProgramApiRoute
import bifrost.modifier.box.Box
import io.circe.parser.parse
import io.circe.syntax._
import scorex.util.encode.Base58
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class ProgramCallSpec extends AnyWordSpec
  with Matchers
  with ScalatestRouteTest
  with ProgramMockState {

  val route: Route = ProgramApiRoute(settings.restApi, nodeViewHolderRef).route

  "programCall" should {

    val boxState = Seq(stateBox, codeBox, executionBox)

    directlyAddPBRStorage(1, boxState)

    view().history.bestBlock.txs.foreach{tx =>
      println(s"${tx.toString}")
    }

    "Return variable from state of a program" in {

      val programCallTemplate =
        s"""
        {
          "jsonrpc": "2.0",
          "id": "1",
          "method": "programCall",
          "params": [{
            "programId": "${executionBox.value}",
            "stateVar": "a",
            "fees": ${fees.asJson},
            "timestamp": ${System.currentTimeMillis},
            "data": ""
          }]
        }
        """.stripMargin

      val requestBody = ByteString(programCallTemplate.stripMargin)
      httpPOST(requestBody) ~> route ~> check {
        val res = parse(responseAs[String]).right.get

        (res \\ "result").head.asNumber.isDefined shouldEqual true
        (res \\ "error").isEmpty shouldEqual true
      }
    }
  }
}
