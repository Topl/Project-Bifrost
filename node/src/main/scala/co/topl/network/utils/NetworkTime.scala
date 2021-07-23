package co.topl.network.utils

import akka.Done
import akka.actor.CoordinatedShutdown
import akka.actor.typed.{ActorSystem, DispatcherSelector}
import co.topl.utils.{Logging, TimeProvider}
import org.apache.commons.net.ntp.NTPUDPClient

import java.net.InetAddress
import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

object NetworkTime {
  def localWithOffset(offset: Long): Long = System.currentTimeMillis() + offset
  type Offset = Long
}

case class NetworkTimeProviderSettings(server: String, updateEvery: FiniteDuration, timeout: FiniteDuration)

class NetworkTimeProvider(ntpSettings: NetworkTimeProviderSettings)(implicit system: ActorSystem[_])
    extends TimeProvider
    with Logging {

  import system.executionContext

  private val blockingExecutionContext: ExecutionContext =
    system.dispatchers.lookup(DispatcherSelector.fromConfig("bifrost.application.ntp.dispatcher"))

  private val lastUpdate = new AtomicLong(0)
  private[NetworkTimeProvider] val offset = new AtomicLong(0)
  private val client = new NTPUDPClient()
  client.setDefaultTimeout(ntpSettings.timeout.toMillis.toInt)
  client.open()
  CoordinatedShutdown(system).addTask(CoordinatedShutdown.PhaseServiceStop, "Shutdown-NTP")(() =>
    Future.successful {
      client.close()
      Done
    }
  )

  /**
   * Check if the NTP offset should be updated and returns current time (milliseconds)
   *
   * @return the current timestamp in milliseconds
   */
  override def time: TimeProvider.Time = {
    checkUpdateRequired()
    NetworkTime.localWithOffset(offset.get())
  }

  /**
   * Fetches the offset from the NTP client using a blocking call.  This blocking call is run in an async computation,
   * but it must run on a special execution context meant specifically for blocking operations
   */
  private def updateOffset(): Future[NetworkTime.Offset] = Future {
    val info = client.getTime(InetAddress.getByName(ntpSettings.server))
    info.computeDetails()
    info.getOffset: NetworkTime.Offset
  }(blockingExecutionContext)

  private def checkUpdateRequired(): Unit = {
    val time = NetworkTime.localWithOffset(offset.get())

    // set lastUpdate to current time so other threads won't start to update it
    val lu = lastUpdate.getAndSet(time)

    if (time > lu + ntpSettings.updateEvery.toMillis) {
      // time to update offset
      updateOffset().onComplete {
        case Success(newOffset) =>
          offset.set(newOffset)
          log.info("New offset adjusted: " + offset)
          lastUpdate.set(time)
        case Failure(e) =>
          log.warn("Problems with NTP: ", e)
          lastUpdate.compareAndSet(time, lu)
      }
    } else {
      // No update required. Set lastUpdate back to it's initial value
      lastUpdate.compareAndSet(time, lu)
    }
  }
}
