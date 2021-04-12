import sbt.Keys.{homepage, organization, scmInfo}
import sbtassembly.MergeStrategy

val scala212 = "2.12.13"
val scala213 = "2.13.5"
val GraalVM8 = "graalvm-ce-java8@20.2.0"

inThisBuild(List(
  organization := "co.topl",
  scalaVersion := scala212,
  crossScalaVersions := Seq(scala212, scala213),
  dynverSeparator := "-",
  githubWorkflowJavaVersions := Seq(GraalVM8),
  githubWorkflowTargetBranches := Seq("main", "dev", "sbt-release"),
  githubWorkflowTargetTags ++= Seq("v*"),
  githubWorkflowPublishTargetBranches += RefPredicate.StartsWith(Ref.Tag("v")),
  githubWorkflowPublish := Seq(
    WorkflowStep.Sbt(
      List("ci-release"),
      env = Map(
        "PGP_PASSPHRASE" -> "${{ secrets.PGP_PASSPHRASE }}",
        "PGP_SECRET" -> "${{ secrets.PGP_SECRET }}",
        "SONATYPE_PASSWORD" -> "${{ secrets.SONATYPE_PASSWORD }}",
        "SONATYPE_USERNAME" -> "${{ secrets.SONATYPE_USERNAME }}"
      )
    )
  )
))


lazy val commonSettings = Seq(
  semanticdbEnabled := true, // enable SemanticDB for Scalafix
  semanticdbVersion := scalafixSemanticdb.revision, // use Scalafix compatible version
  // wartremoverErrors := Warts.unsafe // settings for wartremover
  Compile / unmanagedSourceDirectories += {
    val sourceDir = (sourceDirectory in Compile).value
    CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2, n)) if n >= 13 => sourceDir / "scala-2.13+"
      case _ => sourceDir / "scala-2.12-"
    }
  },
  testOptions in Test ++= Seq(
    Tests.Argument("-oD", "-u", "target/test-reports"),
    Tests.Argument(TestFrameworks.ScalaCheck, "-verbosity", "2"),
    Tests.Argument(TestFrameworks.ScalaTest, "-f", "sbttest.log", "-oDG")
  ),
  parallelExecution in Test := false,
  logBuffered in Test := false,
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
  scmInfo := Some(ScmInfo(url("https://github.com/Topl/Bifrost"), "scm:git:git@github.com:Topl/Bifrost.git")),
  sonatypeCredentialHost := "s01.oss.sonatype.org",
  publishArtifact in Test := false,
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

lazy val doNotPublishSettings = Seq(
  publish := {},
  publishLocal := {},
  publishArtifact := false
)

lazy val assemblySettings = Seq(
  mainClass in assembly := Some("co.topl.BifrostApp"),
  test in assembly := {},
  assemblyJarName := s"bifrost-${version.value}.jar",
  assemblyMergeStrategy in assembly ~= { old: ((String) => MergeStrategy) => {
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
  assemblyExcludedJars in assembly := {
    val cp = (fullClasspath in assembly).value
    cp filter { el => el.data.getName == "ValkyrieInstrument-1.0.jar" }
  }
)

val akkaVersion = "2.6.13"
val akkaHttpVersion = "10.2.4"
val circeVersion = "0.13.0"
val kamonVersion = "2.1.13"
val graalVersion = "21.0.0.2"

val akkaDependencies = Seq(
  "com.typesafe.akka" %% "akka-actor"          % akkaVersion,
  "com.typesafe.akka" %% "akka-cluster"        % akkaVersion,
  "com.typesafe.akka" %% "akka-stream"         % akkaVersion,
  "com.typesafe.akka" %% "akka-http"           % akkaHttpVersion,
  "com.typesafe.akka" %% "akka-http-core"      % akkaHttpVersion,
  "com.typesafe.akka" %% "akka-remote"         % akkaVersion,
  "com.typesafe.akka" %% "akka-slf4j"          % akkaVersion,
  "com.typesafe.akka" %% "akka-stream-testkit" % akkaVersion     % Test,
  "com.typesafe.akka" %% "akka-http-testkit"   % akkaHttpVersion % Test
)

val networkDependencies = Seq(
  "org.bitlet"  % "weupnp"      % "0.1.4",
  "commons-net" % "commons-net" % "3.8.0"
)

val apiDependencies = Seq(
  "io.circe" %% "circe-core"    % circeVersion,
  "io.circe" %% "circe-generic" % circeVersion,
  "io.circe" %% "circe-parser"  % circeVersion,
  "io.circe" %% "circe-literal" % circeVersion
)

val loggingDependencies = Seq(
  "com.typesafe.scala-logging" %% "scala-logging"   % "3.9.3",
  "ch.qos.logback"              % "logback-classic" % "1.2.3",
  "ch.qos.logback"              % "logback-core"    % "1.2.3",
  "org.slf4j"                   % "slf4j-api"       % "1.7.30"
)

lazy val scalatest = "org.scalatest" %% "scalatest" % "3.2.6"

val testingDependencies = Seq(
  "org.scalactic"      %% "scalactic"         % "3.2.6"   % Test,
  "org.scalacheck"     %% "scalacheck"        % "1.15.3"  % Test,
  "org.scalatestplus"  %% "scalacheck-1-14"   % "3.2.2.0" % Test,
  "com.spotify"         % "docker-client"     % "8.16.0"  % Test,
  "org.asynchttpclient" % "async-http-client" % "2.12.3"  % Test
) ++ Seq(scalatest)

val cryptoDependencies = Seq(
  "org.scorexfoundation" %% "scrypto"         % "2.1.10",
  "org.bouncycastle"      % "bcprov-jdk15on"  % "1.68",
  "org.whispersystems"    % "curve25519-java" % "0.5.0"
)

val miscDependencies = Seq(
  "org.scorexfoundation"  %% "iodb"        % "0.3.2",
  "com.chuusai"           %% "shapeless"   % "2.3.3",
  "com.iheart"            %% "ficus"       % "1.5.0",
  "org.rudogma"           %% "supertagged" % "1.5",
  "com.joefkelley"        %% "argyle"      % "1.0.0",
  "org.scalanlp"          %% "breeze"      % "1.1",
  "io.netty"               % "netty"       % "3.10.6.Final",
  "com.google.guava"       % "guava"       % "30.1.1-jre",
  "com.typesafe"           % "config"      % "1.4.1",
  "com.github.pureconfig" %% "pureconfig"  % "0.14.1"
)

val monitoringDependencies = Seq(
  "io.kamon" %% "kamon-bundle"   % kamonVersion,
  "io.kamon" %% "kamon-core"     % kamonVersion,
  "io.kamon" %% "kamon-influxdb" % kamonVersion,
  "io.kamon" %% "kamon-zipkin"   % kamonVersion
)

val graalDependencies = Seq(
  // https://mvnrepository.com/artifact/org.graalvm.sdk/graal-sdk
  // https://mvnrepository.com/artifact/org.graalvm.js/js
  // https://mvnrepository.com/artifact/org.graalvm.truffle/truffle-api
  "org.graalvm.sdk"     % "graal-sdk"   % graalVersion,
  "org.graalvm.js"      % "js"          % graalVersion,
  "org.graalvm.truffle" % "truffle-api" % graalVersion
)

libraryDependencies ++= (akkaDependencies ++ networkDependencies ++ apiDependencies ++ loggingDependencies
++ testingDependencies ++ cryptoDependencies ++ miscDependencies ++ monitoringDependencies ++ graalDependencies)

scalacOptions ++= Seq(
  "-deprecation",
  "-feature",
  "-language:higherKinds",
  "-language:postfixOps",
  "-unchecked",
  "-Xfatal-warnings",
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

connectInput in run := true
outputStrategy := Some(StdoutOutput)

connectInput in run := true
outputStrategy := Some(StdoutOutput)

lazy val bifrost = project.in(file("."))
  .settings(
    moduleName := "bifrost",
    doNotPublishSettings,
    crossScalaVersions := Nil
  )
  .aggregate(
    node,
    common,
    gjallarhorn,
    benchmarking,
    it
  )

lazy val node = project.in(file("node"))
  .settings(
    name := "node",
    commonSettings,
    publish / skip := true,
    buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion),
    buildInfoPackage := "co.topl.buildinfo.bifrost",
    dockerBaseImage := "ghcr.io/graalvm/graalvm-ce:java8-21.0.0",
    dockerExposedPorts := Seq(9084, 9085),
    dockerExposedVolumes += "/opt/docker/.bifrost",
    dockerLabels ++= Map(
      "bifrost.version" -> version.value
    ),
    libraryDependencies ++= (akkaDependencies ++ networkDependencies ++ apiDependencies ++ loggingDependencies
      ++ testingDependencies ++ cryptoDependencies ++ miscDependencies ++ monitoringDependencies ++ graalDependencies)
  )
  .enablePlugins(BuildInfoPlugin, JavaAppPackaging, DockerPlugin)
  .dependsOn(common)

lazy val common = project.in(file("common"))
  .settings(
    name := "common",
    commonSettings,
    publishSettings,
    libraryDependencies ++= akkaDependencies ++ loggingDependencies ++ apiDependencies ++ cryptoDependencies
  )

lazy val gjallarhorn = project.in(file("gjallarhorn"))
  .settings(
    name := "gjallarhorn",
    commonSettings,
    doNotPublishSettings,
    crossScalaVersions := Nil,
    Defaults.itSettings,
    libraryDependencies ++= akkaDependencies ++ testingDependencies ++ cryptoDependencies ++ apiDependencies
    ++ loggingDependencies ++ miscDependencies,
    libraryDependencies += scalatest % "it, test"
  )
  .configs(IntegrationTest)
  .disablePlugins(sbtassembly.AssemblyPlugin)

lazy val benchmarking = project.in(file("benchmark"))
  .settings(
    name := "benchmark",
    commonSettings,
    doNotPublishSettings,
    crossScalaVersions := Nil
  )
  .dependsOn(node % "compile->compile;test->test")
  .enablePlugins(JmhPlugin)
  .disablePlugins(sbtassembly.AssemblyPlugin)

lazy val it = project.in(file("it"))
  .settings(
    name := "it",
    commonSettings,
    doNotPublishSettings,
    crossScalaVersions := Nil,
    Defaults.itSettings,
    libraryDependencies += scalatest % "it, test"
  )
  .dependsOn(node % "compile->compile;test->test")
  .configs(IntegrationTest)
  .disablePlugins(sbtassembly.AssemblyPlugin)
