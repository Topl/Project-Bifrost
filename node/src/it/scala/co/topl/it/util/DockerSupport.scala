package co.topl.it.util

import akka.actor.ActorSystem
import co.topl.buildinfo.bifrost.BuildInfo
import co.topl.utils.Logging
import com.spotify.docker.client.DockerClient
import com.spotify.docker.client.messages.{ContainerConfig, HostConfig, NetworkConfig, NetworkCreation}

import scala.jdk.CollectionConverters._

class DockerSupport(dockerClient: DockerClient)(implicit system: ActorSystem) extends Logging {

  private var nodeCache: Set[BifrostDockerNode] = Set.empty

  private var networkCache: Set[NetworkCreation] = Set.empty

  def createNode(name: String, nodeGroupName: String): BifrostDockerNode = {
    val networkName = DockerSupport.networkNamePrefix + nodeGroupName
    val containerConfig = buildContainerConfig(name, Map.empty)
    val containerCreation = dockerClient.createContainer(containerConfig, name)

    val node = BifrostDockerNode(containerCreation.id())
    nodeCache += node

    val networkId =
      dockerClient.listNetworks().asScala.find(_.name == networkName) match {
        case Some(network) => network.id()
        case None =>
          val network =
            dockerClient.createNetwork(NetworkConfig.builder().name(networkName).build())
          networkCache += network
          network.id()
      }

    dockerClient.connectToNetwork(node.containerId, networkId)

    node
  }

  private def buildContainerConfig(name: String, environment: Map[String, String]): ContainerConfig = {
    val env =
      environment.toList.map { case (key, value) => s"$key=$value" }

    val cmd =
      List(
        "-Dcom.sun.management.jmxremote.port=9083",
        "-Dcom.sun.management.jmxremote.rmi.port=9083",
        "-Dcom.sun.management.jmxremote.ssl=false",
        "-Dcom.sun.management.jmxremote.local.only=false",
        "-Dcom.sun.management.jmxremote.authenticate=false",
        "--apiKeyHash",
        NodeRpcApi.ApiKeyHashBase58,
        "-c",
        "/opt/docker/config/testConfig.conf",
        "--debug"
      )

    val hostConfig =
      HostConfig.builder().nanoCpus((1d * 1000000000).toLong).build()

    ContainerConfig
      .builder()
      .image(DockerSupport.bifrostImage)
      .env(env: _*)
      .volumes("/opt/docker/config")
      .cmd(cmd: _*)
      .hostname(name)
      .hostConfig(hostConfig)
      .exposedPorts(BifrostDockerNode.RpcPort.toString, BifrostDockerNode.NetworkPort.toString, "9083")
      .build()
  }

  def close(): Unit = {
    nodeCache
      .map(_.containerId)
      .foreach(containerId => dockerClient.removeContainer(containerId, DockerClient.RemoveContainerParam.forceKill))
    networkCache
      .map(_.id())
      .foreach(dockerClient.removeNetwork)
  }

}

object DockerSupport {

  val bifrostImage: String = s"bifrost-node:${BuildInfo.version}"
  val networkNamePrefix: String = "bifrost-it"
}
