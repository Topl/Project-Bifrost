package co.topl.settings

import co.topl.network.utils.NetworkTimeProviderSettings
import co.topl.utils.Logging
import com.typesafe.config.{Config, ConfigFactory}
import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.ArbitraryTypeReader._

import java.io.File
import java.net.InetSocketAddress
import scala.concurrent.duration._

case class ApplicationSettings(
  cacheExpire:      Int,
  cacheSize:        Int,
  dataDir:          Option[String],
  keyFileDir:       Option[String],
  enablePBR:        Boolean,
  enableTBR:        Boolean,
  mempoolTimeout:   FiniteDuration,
  nodeKeys:         Option[Set[String]],
  rebroadcastCount: Int,
  version:          Version
)

case class RPCApiSettings(
  bindAddress:       InetSocketAddress,
  disableAuth:       Boolean,
  apiKeyHash:        String,
  timeout:           FiniteDuration,
  verboseAPI:        Boolean,
  namespaceSelector: NamespaceSelector
)

case class NetworkSettings(
  agentName:               String,
  applicationNameLimit:    Int,
  bindAddress:             InetSocketAddress,
  connectionTimeout:       FiniteDuration,
  controllerTimeout:       Option[FiniteDuration],
  deadConnectionTimeout:   FiniteDuration,
  declaredAddress:         Option[InetSocketAddress],
  deliveryTimeout:         FiniteDuration,
  desiredInvObjects:       Int,
  getPeersInterval:        FiniteDuration,
  handshakeTimeout:        FiniteDuration,
  knownPeers:              Seq[InetSocketAddress],
  magicBytes:              Array[Byte],
  maxChainCacheDepth:      Int,
  maxConnections:          Int,
  maxDeliveryChecks:       Int,
  maxHandshakeSize:        Int,
  maxInvObjects:           Int,
  maxModifiersCacheSize:   Int,
  maxPacketSize:           Int,
  maxPeerSpecObjects:      Int,
  nodeName:                String,
  penaltySafeInterval:     FiniteDuration,
  penaltyScoreThreshold:   Int,
  syncInterval:            FiniteDuration,
  syncIntervalStable:      FiniteDuration,
  syncStatusRefresh:       FiniteDuration,
  syncStatusRefreshStable: FiniteDuration,
  syncTimeout:             Option[FiniteDuration],
  temporalBanDuration:     FiniteDuration,
  upnpDiscoverTimeout:     Option[FiniteDuration],
  upnpEnabled:             Boolean,
  upnpUseRandom:           Option[Boolean],
  upnpGatewayTimeout:      Option[FiniteDuration]
)

case class ForgingSettings(
  blockGenerationDelay: FiniteDuration,
  minTransactionFee:    Long,
  protocolVersions:     List[ProtocolSettings],
  forgeOnStartup:       Boolean,
  rewardsAddress:       Option[String], //String here since we don't know netPrefix when settings are read
  privateTestnet:       Option[PrivateTestnetSettings]
)

case class PrivateTestnetSettings(
  numTestnetAccts:   Int,
  testnetBalance:    Long,
  initialDifficulty: Long,
  genesisSeed:       Option[String]
)

case class GjallarhornSettings(
  enableWallet:   Boolean,
  clusterEnabled: Boolean,
  clusterHost:    Option[String],
  clusterPort:    Option[Int]
)

case class AppSettings(
  application: ApplicationSettings,
  network:     NetworkSettings,
  gjallarhorn: GjallarhornSettings,
  forging:     ForgingSettings,
  rpcApi:      RPCApiSettings,
  ntp:         NetworkTimeProviderSettings
)

object AppSettings extends Logging with SettingsReaders {

  protected val configPath: String = "bifrost"

  /**
   * Produces an application settings class, and modify the default settings if user options are provided
   *
   * @param startupOpts startup options such as the path of the user defined config and network type
   * @return application settings
   */
  def read(startupOpts: StartupOpts = StartupOpts()): (AppSettings, Config) = {
    val config = readConfig(startupOpts)
    val settingFromConfig = fromConfig(config)
    val completeConfig = clusterConfig(settingFromConfig, config)
    (startupOpts.runtimeParams.overrideWithCmdArgs(settingFromConfig), completeConfig)
  }

  /**
   * Produces an application settings class by reading the specified HOCON configuration file
   *
   * @param config config factory compatible configuration
   * @return application settings
   */
  def fromConfig(config: Config): AppSettings = config.as[AppSettings](configPath)

  /**
   * Based on the startup arguments given by the user, modify and return the default application config
   *
   * @param args startup options such as the path of the user defined config and network type
   * @return config factory compatible configuration
   */
  def readConfig(args: StartupOpts): Config = {

    val userConfig = args.userConfigPathOpt.fold(ConfigFactory.empty()) { uc =>
      val userFile = new File(uc)
      log.info(
        s"${Console.YELLOW}Attempting to load custom configuration from " +
        s"${userFile.getAbsolutePath}${Console.RESET}"
      )

      ConfigFactory.parseFile(userFile)
    }

    val networkConfig = args.networkTypeOpt match {
      case Some(value) =>
        log.info(s"${Console.YELLOW}Loading ${args.networkTypeOpt.get.verboseName} settings${Console.RESET}")
        ConfigFactory.load(this.getClass.getClassLoader, value.verboseName + ".conf")
      case None =>
        log.info(s"${Console.YELLOW}No network specified, running as private testnet.${Console.RESET}")
        ConfigFactory.empty()
    }

    // load config files from disk, if the above strings are empty then ConFigFactory will skip loading them
    ConfigFactory
      .defaultOverrides()
      .withFallback(userConfig)
      .withFallback(networkConfig)
      .withFallback(ConfigFactory.defaultApplication())
      .resolve()

  }

  def clusterConfig(settings: AppSettings, config: Config): Config =
    if (settings.gjallarhorn.clusterEnabled) {
      ConfigFactory
        .parseString(s"""
      akka {
        actor.provider = cluster
        remote = {
          artery = {
            canonical.hostname = ${settings.gjallarhorn.clusterHost.getOrElse("0.0.0.0")}
            canonical.port = ${settings.gjallarhorn.clusterPort.getOrElse(0)}
          }
        }
      }
      """)
        .withFallback(config)
    } else {
      config
    }
}
