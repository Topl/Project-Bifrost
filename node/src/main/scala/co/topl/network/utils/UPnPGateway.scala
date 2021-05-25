package co.topl.network.utils

import co.topl.settings.NetworkSettings
import co.topl.utils.Logging
import org.bitlet.weupnp.{GatewayDevice, GatewayDiscover, PortMappingEntry}

import java.net.{InetAddress, InetSocketAddress}
import scala.jdk.CollectionConverters._

class UPnPGateway(gateway: GatewayDevice, port: Int) extends Logging {

  val localAddress: InetAddress = gateway.getLocalAddress
  val externalAddress: InetAddress = InetAddress.getByName(gateway.getExternalIPAddress)
  val mappedPort: Int = port

  log.info("Using UPnP gateway device on " + localAddress.getHostAddress)
  log.info("External IP address is " + externalAddress.getHostAddress)

  try if (gateway.addPortMapping(port, port, localAddress.getHostAddress, "TCP", "BifrostClient")) {
    log.info("Mapped port [" + externalAddress.getHostAddress + "]:" + port)
  } else {
    log.info("Unable to map port " + port)
  } catch {
    case t: Throwable =>
      log.error("Unable to map port " + port + ": " + t.toString)
  }

  def deletePort(port: Int): Unit =
    try if (gateway.deletePortMapping(port, "TCP")) {
      log.info("Mapping deleted for port " + port)
    } else {
      log.info("Unable to delete mapping for port " + port)
    } catch {
      case t: Throwable =>
        log.error("Unable to delete mapping for port " + port + ": " + t.toString)
    }

  def getLocalAddressForExternalPort(externalPort: Int): Option[InetSocketAddress] =
    try {
      val entry = new PortMappingEntry
      if (gateway.getSpecificPortMappingEntry(externalPort, "TCP", entry)) {
        val host = entry.getInternalClient
        Some(new InetSocketAddress(InetAddress.getByName(host), entry.getInternalPort))
      } else {
        None
      }
    } catch {
      case t: Throwable =>
        log.error("Unable to get local address for external port " + externalPort + ": " + t.toString)
        None
    }
}

object UPnPGateway extends Logging {

  def getPort(settings: NetworkSettings): Int =
    settings.upnpUseRandom match {
      case Some(_) => scala.util.Random.nextInt(15000) + 50000
      case _       => settings.bindAddress.getPort
    }

  def apply(settings: NetworkSettings): Option[UPnPGateway] =
    try {
      log.info("Looking for UPnP gateway device...")
      val defaultHttpReadTimeout =
        settings.upnpGatewayTimeout.map(_.toMillis.toInt).getOrElse(GatewayDevice.getHttpReadTimeout)
      GatewayDevice.setHttpReadTimeout(defaultHttpReadTimeout)
      val discover = new GatewayDiscover()
      val defaultDiscoverTimeout = settings.upnpDiscoverTimeout.map(_.toMillis.toInt).getOrElse(discover.getTimeout)
      discover.setTimeout(defaultDiscoverTimeout)

      val gatewayMap = Option(discover.discover).map(_.asScala).map(_.toMap).getOrElse(Map())
      if (gatewayMap.isEmpty) {
        log.info("There are no UPnP gateway devices")
        None
      } else {
        gatewayMap.foreach { case (addr, _) =>
          log.info("UPnP gateway device found on " + addr.getHostAddress)
        }
        val gateway = Option(discover.getValidGateway)
        if (gateway.isEmpty) {
          log.info("There is no connected UPnP gateway device")
        }

        val port = getPort(settings)
        // Return UPnP Gateway with mapped port
        val upnpGateway = gateway.map(new UPnPGateway(_, port))

        // add shutdown hook for deleting the port mapping
        sys.addShutdownHook(upnpGateway.get.deletePort(port))

        upnpGateway
      }
    } catch {
      case t: Throwable =>
        log.error("Unable to discover UPnP gateway devices", t)
        None
    }
}
