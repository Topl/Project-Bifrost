package co.topl.settings

import co.topl.utils.NetworkType
import mainargs.{arg, main, Flag, ParserForClass, TokensReader}
import monocle.syntax.all._

/**
 * Parameters that are given at application startup. Only parameters that are
 * required for initialization should be included at the top level while all other
 * settings that control the application runtime (similar in nature to config settings)
 * are delegated to the runtime options
 *
 * @param userConfigPathOpt file path to the user defined config file
 * @param networkTypeOpt string designating the type of network to be launched
 * @param runtimeParams all runtime determined application settings
 */
@main
final case class StartupOpts(
  @arg(name = "config", short = 'c', doc = "file path to a user defined config file")
  userConfigPathOpt: Option[String] = None,
  @arg(name = "debug", short = 'd', doc = "Turn on debugging information")
  verbose: Flag = Flag(),
  @arg(name = "network", short = 'n', doc = "specify preset network by name")
  networkTypeOpt: Option[NetworkType] = None,
  runtimeParams:  RuntimeOpts = RuntimeOpts()
)

/* ----------------- */ /* ----------------- */ /* ----------------- */ /* ----------------- */ /* ----------------- */

/**
 * Parameters that control the application behavior that are specified at runtime.
 * These settings could just as well be defined in the config file but may be
 * useful to define them as command line parameters instead.
 *
 * @param seed a string used to deterministically generate key files (only applicable for local and private networks)
 * @param forgeOnStartup a boolean controlling whether the node should attempt forging immediately on start
 * @param apiKeyHash hash of API key
 */
@main
final case class RuntimeOpts(
  @arg(name = "seed", short = 's', doc = "string to deterministically generate keys on private and local networks")
  seed: Option[String] = None,
  @arg(name = "forge", short = 'f', doc = "enable forging as soon as the node starts")
  forgeOnStartup: Flag = Flag(),
  @arg(name = "disableAuth", doc = "Allow the node to receive API requests without an API key")
  disableAuth: Flag = Flag(),
  @arg(name = "apiKeyHash", doc = "hash of API key")
  apiKeyHash: Option[String] = None
) {

  /**
   * Helper method to replace settings values with parameters passed from command line arguments
   * @param appSettings application settings read from the configuration file
   * @return an updated appSettings instance
   */
  def overrideWithCmdArgs(appSettings: AppSettings): AppSettings =
    appSettings
      // seed
      .focus(_.forging.privateTestnet)
      .modify(_.map(_.focus(_.genesisSeed).modify(_.orElse(seed))))
      // forge
      .focus(_.forging.forgeOnStartup)
      .replace(appSettings.forging.forgeOnStartup || forgeOnStartup.value)
      // disableAuth
      .focus(_.rpcApi.disableAuth)
      .replace(appSettings.rpcApi.disableAuth || disableAuth.value)
      // apiKeyHash
      .focus(_.rpcApi.apiKeyHash)
      .modify(configKey =>
        apiKeyHash match {
          case Some(cliKey) => cliKey
          case None         => configKey
        }
      )
}

object StartupOptsImplicits {

  /**
   * networkReader, runtimeOptsParser, and startupOptsParser are defined here in a specific order
   *  to define the runtime command flags using mainargs. StartupOpts has to be last as it uses NetworkType
   *  and RuntimeOpts internally
   */
  implicit object networkReader
      extends TokensReader[NetworkType](
        shortName = "network",
        str =>
          NetworkType.pickNetworkType(str.head) match {
            case Some(net) => Right(net)
            case None      => Left("No valid network found with that name")
          }
      )

  implicit def runtimeOptsParser: ParserForClass[RuntimeOpts] = ParserForClass[RuntimeOpts]
  implicit def startupOptsParser: ParserForClass[StartupOpts] = ParserForClass[StartupOpts]
}
