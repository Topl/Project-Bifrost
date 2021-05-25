package co.topl.utils

import java.net.{Inet4Address, InetSocketAddress, NetworkInterface}

import scala.jdk.CollectionConverters._

object NetworkUtils {

  def getListenAddresses(bindAddress: InetSocketAddress): Set[InetSocketAddress] =
    if (bindAddress.getAddress.isAnyLocalAddress || bindAddress.getAddress.isLoopbackAddress) {
      NetworkInterface.getNetworkInterfaces.asScala
        .flatMap(_.getInetAddresses.asScala)
        .collect { case a: Inet4Address => a }
        .map(a => new InetSocketAddress(a, bindAddress.getPort))
        .toSet
    } else {
      Set(bindAddress)
    }

  def isSelf(
    peerAddress:         InetSocketAddress,
    bindAddress:         InetSocketAddress,
    externalNodeAddress: Option[InetSocketAddress]
  ): Boolean =
    NetworkUtils.getListenAddresses(bindAddress).contains(peerAddress) ||
    externalNodeAddress.contains(peerAddress)

}
