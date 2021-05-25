import sbt.Keys.{homepage, organization, test}
import sbtassembly.MergeStrategy
import Dependencies._

val scala212 = "2.12.13"
val scala213 = "2.13.5"

inThisBuild(List(
  organization := "co.topl",
  scalaVersion := scala213,
  crossScalaVersions := Seq(scala212, scala213),
  versionScheme := Some("early-semver"),
  dynverSeparator := "-",
  version := dynverGitDescribeOutput.value.mkVersion(versionFmt, fallbackVersion(dynverCurrentDate.value)),
  dynver := {
    val d = new java.util.Date
    sbtdynver.DynVer.getGitDescribeOutput(d).mkVersion(versionFmt, fallbackVersion(d))
  },
  parallelExecution := false
))

lazy val commonSettings = Seq(
  sonatypeCredentialHost := "s01.oss.sonatype.org",
  scalacOptions ++= commonScalacOptions,
  semanticdbEnabled := true, // enable SemanticDB for Scalafix
  semanticdbVersion := scalafixSemanticdb.revision, // use Scalafix compatible version
  // wartremoverErrors := Warts.unsafe // settings for wartremover
  Compile / unmanagedSourceDirectories += {
    val sourceDir = (Compile / sourceDirectory).value
    CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2, n)) if n >= 13 => sourceDir / "scala-2.13+"
      case _                       => sourceDir / "scala-2.12-"
    }
  },
  Test / testOptions ++= Seq(
    Tests.Argument("-oD", "-u", "target/test-reports"),
    Tests.Argument(TestFrameworks.ScalaCheck, "-verbosity", "2"),
    Tests.Argument(TestFrameworks.ScalaTest, "-f", "sbttest.log", "-oDG")
  ),
  Test / parallelExecution := false,
  Test / logBuffered := false,
  classLoaderLayeringStrategy := ClassLoaderLayeringStrategy.Flat,
  Test / fork := false,
  Compile / run / fork := true,
  resolvers ++= Seq(
    "Typesafe Repository" at "https://repo.typesafe.com/typesafe/releases/",
    "Sonatype Staging" at "https://s01.oss.sonatype.org/content/repositories/staging",
    "Sonatype Snapshots" at "https://s01.oss.sonatype.org/content/repositories/snapshots/"
  )
)

lazy val publishSettings = Seq(
  homepage := Some(url("https://github.com/Topl/Bifrost")),
  licenses := Seq("MPL2.0" -> url("https://www.mozilla.org/en-US/MPL/2.0/")),
  Test / publishArtifact := false,
  pomIncludeRepository := { _ => false },
  usePgpKeyHex("CEE1DC9E7C8E9AF4441D5EB9E35E84257DCF8DCB"),
  pomExtra :=
    <developers>
      <developer>
        <id>scasplte2</id>
        <name>James Aman</name>
      </developer>
      <developer>
        <id>tuxman</id>
        <name>Nicholas Edmonds</name>
      </developer>
    </developers>
)

lazy val assemblySettings = Seq(
  assembly / mainClass := Some("co.topl.BifrostApp"),
  assembly / test := {},
  assemblyJarName := s"bifrost-${version.value}.jar",
  assembly / assemblyMergeStrategy ~= { old: ((String) => MergeStrategy) =>
    {
      case ps if ps.endsWith(".SF")  => MergeStrategy.discard
      case ps if ps.endsWith(".DSA") => MergeStrategy.discard
      case ps if ps.endsWith(".RSA") => MergeStrategy.discard
      case ps if ps.endsWith(".xml") => MergeStrategy.first
      case PathList(ps @ _*) if ps.last endsWith "module-info.class" =>
        MergeStrategy.discard // https://github.com/sbt/sbt-assembly/issues/370
      case PathList("module-info.java")  => MergeStrategy.discard
      case PathList("local.conf")        => MergeStrategy.discard
      case "META-INF/truffle/instrument" => MergeStrategy.concat
      case "META-INF/truffle/language"   => MergeStrategy.rename
      case x                             => old(x)
    }
  },
  assembly / assemblyExcludedJars := {
    val cp = (assembly / fullClasspath).value
    cp filter { el => el.data.getName == "ValkyrieInstrument-1.0.jar" }
  }
)

lazy val scalamacrosParadiseSettings =
  Seq(
    libraryDependencies ++= {
      CrossVersion.partialVersion(scalaVersion.value) match {
        case Some((2, v)) if v <= 12 =>
          Seq(
            compilerPlugin("org.scalamacros" % "paradise" % "2.1.1" cross CrossVersion.full)
          )
        case _ =>
          Nil
      }
    },
    scalacOptions ++= {
      CrossVersion.partialVersion(scalaVersion.value) match {
        case Some((2, v)) if v >= 13 =>
          Seq(
            "-Ymacro-annotations"
          )
        case _ =>
          Nil
      }
    }
  )
lazy val commonScalacOptions = Seq(
  "-deprecation",
  "-feature",
  "-language:higherKinds",
  "-language:postfixOps",
  "-unchecked",
  "-Xlint:",
  "-Ywarn-unused:-implicits,-privates"
)

javaOptions ++= Seq(
  "-Xbootclasspath/a:ValkyrieInstrument-1.0.jar",
  // from https://groups.google.com/d/msg/akka-user/9s4Yl7aEz3E/zfxmdc0cGQAJ
  "-XX:+UseG1GC",
  "-XX:+UseNUMA",
  "-XX:+AlwaysPreTouch",
  "-XX:+PerfDisableSharedMem",
  "-XX:+ParallelRefProcEnabled",
  "-XX:+UseStringDeduplication",
  "-XX:+ExitOnOutOfMemoryError",
  "-Xss64m"
)

connectInput / run := true
outputStrategy := Some(StdoutOutput)

connectInput / run := true
outputStrategy := Some(StdoutOutput)

def versionFmt(out: sbtdynver.GitDescribeOutput): String = {
  val dirtySuffix = out.dirtySuffix.dropPlus.mkString("-", "")
  if (out.isCleanAfterTag) out.ref.dropPrefix + dirtySuffix // no commit info if clean after tag
  else out.ref.dropPrefix + out.commitSuffix.mkString("-", "-", "") + dirtySuffix
}

def fallbackVersion(d: java.util.Date): String = s"HEAD-${sbtdynver.DynVer timestamp d}"

lazy val bifrost = project
  .in(file("."))
  .settings(
    moduleName := "bifrost",
    commonSettings,
    publish / skip := true,
    crossScalaVersions := Nil
  )
  .configs(IntegrationTest)
  .aggregate(
    node,
    common,
    akkaHttpRpc,
    toplRpc,
    gjallarhorn,
    benchmarking,
    crypto,
    brambl
  )
  .dependsOn(
    node,
    common,
    gjallarhorn,
    benchmarking
  )

lazy val node = project
  .in(file("node"))
  .settings(
    name := "node",
    commonSettings,
    assemblySettings,
    Defaults.itSettings,
    publish / skip := true,
    buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion),
    buildInfoPackage := "co.topl.buildinfo.bifrost",
    Docker / packageName := "bifrost-node",
    dockerBaseImage := "ghcr.io/graalvm/graalvm-ce:java11-21.1.0",
    dockerExposedPorts := Seq(9084, 9085),
    dockerExposedVolumes += "/opt/docker/.bifrost",
    dockerLabels ++= Map(
      "bifrost.version" -> version.value
    ),
    libraryDependencies ++= Dependencies.node,
  )
  .configs(IntegrationTest)
  .settings(
    IntegrationTest / parallelExecution := false
  )
  .dependsOn(common % "compile->compile;test->test", toplRpc)
  .enablePlugins(BuildInfoPlugin, JavaAppPackaging, DockerPlugin)

lazy val common = project
  .in(file("common"))
  .settings(
    name := "common",
    commonSettings,
    publishSettings,
    libraryDependencies ++= Dependencies.common
  )
  .dependsOn(crypto)
  .settings(scalamacrosParadiseSettings)

lazy val chainProgram = project
  .in(file("chain-program"))
  .settings(
    name := "chain-program",
    commonSettings,
    publish / skip := true,
    libraryDependencies ++= Dependencies.chainProgram
  )
  .dependsOn(common)
  .disablePlugins(sbtassembly.AssemblyPlugin)

lazy val brambl = project
  .in(file("brambl"))
  .enablePlugins(BuildInfoPlugin)
  .settings(
    name := "brambl",
    commonSettings,
    publishSettings,
    libraryDependencies ++= Dependencies.brambl,
    buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion),
    buildInfoPackage := "co.topl.buildinfo.brambl"
  )
  .dependsOn(toplRpc, common)

lazy val akkaHttpRpc = project
  .in(file("akka-http-rpc"))
  .enablePlugins(BuildInfoPlugin)
  .settings(
    name := "akka-http-rpc",
    commonSettings,
    publishSettings,
    libraryDependencies ++= Dependencies.akkaHttpRpc,
    buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion),
    buildInfoPackage := "co.topl.buildinfo.akkahttprpc"
  )

lazy val toplRpc = project
  .in(file("topl-rpc"))
  .enablePlugins(BuildInfoPlugin)
  .settings(
    name := "topl-rpc",
    commonSettings,
    publishSettings,
    libraryDependencies ++= Dependencies.toplRpc,
    buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion),
    buildInfoPackage := "co.topl.buildinfo.toplrpc"
  )
  .dependsOn(akkaHttpRpc, common)

lazy val gjallarhorn = project
  .in(file("gjallarhorn"))
  .settings(
    name := "gjallarhorn",
    commonSettings,
    publish / skip := true,
    Defaults.itSettings,
    libraryDependencies ++= Dependencies.gjallarhorn
  )
  .dependsOn(crypto, common)
  .configs(IntegrationTest)
  .disablePlugins(sbtassembly.AssemblyPlugin)
  .settings(scalamacrosParadiseSettings)

lazy val benchmarking = project
  .in(file("benchmark"))
  .settings(
    name := "benchmark",
    commonSettings,
    publish / skip := true,
    libraryDependencies ++= Dependencies.benchmarking
  )
  .dependsOn(node % "compile->compile;test->test")
  .enablePlugins(JmhPlugin)
  .disablePlugins(sbtassembly.AssemblyPlugin)

lazy val crypto = project
  .in(file("crypto"))
  .enablePlugins(BuildInfoPlugin)
  .settings(
    name := "crypto",
    commonSettings,
    publishSettings,
    scalamacrosParadiseSettings,
    buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion),
    buildInfoPackage := "co.topl.buildinfo.crypto",
    libraryDependencies ++= Dependencies.cryptoModule,

  )

addCommandAlias("checkPR", "; scalafixAll --check; scalafmtCheckAll; test")
addCommandAlias("preparePR", "; scalafixAll; scalafmtAll; test")
