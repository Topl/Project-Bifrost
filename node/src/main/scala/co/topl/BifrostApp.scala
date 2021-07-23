package co.topl

import akka.actor.typed._
import co.topl.modifier.block.Block
import co.topl.modifier.transaction.Transaction
import co.topl.network.message.BifrostSyncInfo
import co.topl.network.utils.UPnPGateway
import co.topl.nodeView.history.History
import co.topl.nodeView.mempool.MemPool
import co.topl.nodeView.state.State
import co.topl.settings._
import co.topl.utils.Logging
import com.sun.management.{HotSpotDiagnosticMXBean, VMOption}
import com.typesafe.config.{Config, ConfigFactory}
import kamon.Kamon
import mainargs.ParserForClass

import java.lang.management.ManagementFactory
import scala.concurrent.Await

class BifrostApp(startupOpts: StartupOpts) extends NodeLogging {

  /**
   * Configure logging backend to set debug logging level if verbose mode is enabled. Needs to be placed
   *  before any log output to set the level correctly.
   */
  if (startupOpts.verbose.value) setLogLevel()

  /** Setup settings file to be passed into the application */
  private val (settings: AppSettings, config: Config) = AppSettings.read(startupOpts)
  log.debug(s"Starting application with settings \n$settings")

  /** check for gateway device and setup port forwarding */
  private val upnpGateway: Option[UPnPGateway] =
    if (settings.network.upnpEnabled) UPnPGateway(settings.network) else None

  /** save runtime environment into a variable for reference throughout the application */
  protected val appContext = new AppContext(settings, startupOpts, upnpGateway)
  log.debug(
    s"${Console.MAGENTA}Runtime network parameters:" +
    s"type - ${appContext.networkType.verboseName}, " +
    s"prefix - ${appContext.networkType.netPrefix}, " +
    s"forging status: ${settings.forging.forgeOnStartup}" +
    s"${Console.RESET}"
  )

  require(settings.network.agentName.length <= settings.network.applicationNameLimit)
  log.debug(s"Available processors: ${Runtime.getRuntime.availableProcessors}")
  log.debug(s"Max memory available: ${Runtime.getRuntime.maxMemory}")
  log.debug(s"RPC is allowed at: ${settings.rpcApi.bindAddress}")
  /* ----------------- */ /* ----------------- */ /* ----------------- */ /* ----------------- */ /* ---------------- */
  /** Setup the execution environment for running the application */

  /* ----------------- */ /* ----------------- */ /* ----------------- */ /* ----------------- */ /* ---------------- */
  /** Am I running on a JDK that supports JVMCI? */
  val vm_version: String = System.getProperty("java.vm.version")
  System.out.printf("java.vm.version = %s%n", vm_version)

  val bean: HotSpotDiagnosticMXBean = ManagementFactory.getPlatformMXBean(classOf[HotSpotDiagnosticMXBean])

  // Is JVMCI enabled?
  try {
    val enableJVMCI: VMOption = bean.getVMOption("EnableJVMCI")
    log.debug(s"$enableJVMCI")
  } catch {
    case e: Throwable =>
      log.error(s"${Console.RED}Unexpected error when checking for JVMCI: $e ${Console.RESET}")
      throw e
  }

  /** Is the system using the JVMCI compiler for normal compilations? */
  val useJVMCICompiler: VMOption = bean.getVMOption("UseJVMCICompiler")
  log.debug(s"$useJVMCICompiler")

  /** What compiler is selected? */
  val compiler: String = System.getProperty("jvmci.Compiler")
  System.out.printf("jvmci.Compiler = %s%n", compiler)

  implicit val actorSystem: ActorSystem[Heimdall.ReceivableMessage] =
    ActorSystem(Heimdall(settings, appContext), settings.network.agentName, config)

  sys.addShutdownHook {
    actorSystem.terminate()
    import scala.concurrent.duration._
    Await.result(actorSystem.whenTerminated, 1.minute)
  }
}

/** This is the primary application object and is the entry point for Bifrost to begin execution */
object BifrostApp extends Logging {

  import StartupOptsImplicits._

  /** Check if Kamon instrumentation should be started. */
  /** DO NOT MOVE!! This must happen before anything else! */
  private val conf: Config = ConfigFactory.load("application")
  if (conf.getBoolean("kamon.enable")) Kamon.init()

  ////////////////////////////////////////////////////////////////////////////////////
  //////////////////////////////// METHOD DEFINITIONS ////////////////////////////////

  def main(args: Array[String]): Unit = ParserForClass[StartupOpts].constructEither(args.toIndexedSeq) match {
    case Right(parsedArgs) => new BifrostApp(parsedArgs)
    case Left(e)           => throw new Exception(e)
  }

  def forceStopApplication(code: Int = 1): Nothing = sys.exit(code)
}
