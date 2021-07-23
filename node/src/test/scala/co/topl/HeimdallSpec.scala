package co.topl

import akka.actor.testkit.typed.scaladsl.{ManualTime, ScalaTestWithActorTestKit}
import co.topl.nodeView.NodeViewTestHelpers
import co.topl.utils.{CommonGenerators, InMemoryKeyFileTestHelper, TestSettings}
import org.scalamock.scalatest.MockFactory
import org.scalatest.{Inspectors, OptionValues}
import org.scalatest.flatspec.AnyFlatSpecLike

class HeimdallSpec
    extends ScalaTestWithActorTestKit(ManualTime.config.withFallback(TestSettings.defaultConfig))
    with AnyFlatSpecLike
    with TestSettings
    with InMemoryKeyFileTestHelper
    with NodeViewTestHelpers
    with MockFactory
    with OptionValues
    with Inspectors
    with CommonGenerators {

  behavior of "Heimdall"

  it should "crash if a child actor crashes" in {
    val ref = spawn(
      Heimdall.apply(
        settings.copy(forging =
          // Setting `numTestnetAccts` to 0 results in a KeyManager exception
          settings.forging.copy(privateTestnet = settings.forging.privateTestnet.map(_.copy(numTestnetAccts = 0)))
        ),
        appContext
      )
    )

    createTestProbe().expectTerminated(ref)
  }

}
