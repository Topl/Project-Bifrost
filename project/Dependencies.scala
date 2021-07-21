import sbt._

object Dependencies {

  val akkaVersion = "2.6.14"
  val akkaHttpVersion = "10.2.4"
  val circeVersion = "0.13.0"
  val kamonVersion = "2.1.17"
  val graalVersion = "21.1.0"

  val logging = Seq(
    "com.typesafe.scala-logging" %% "scala-logging"   % "3.9.3",
    "ch.qos.logback"              % "logback-classic" % "1.2.3",
    "ch.qos.logback"              % "logback-core"    % "1.2.3",
    "org.slf4j"                   % "slf4j-api"       % "1.7.30"
  )

  val test = Seq(
    "org.scalatest"      %% "scalatest"         % "3.2.8"   % "test",
    "org.scalactic"      %% "scalactic"         % "3.2.8"   % "test",
    "org.scalacheck"     %% "scalacheck"        % "1.15.4"  % "test",
    "org.scalatestplus"  %% "scalacheck-1-14"   % "3.2.2.0" % "test",
    "com.spotify"         % "docker-client"     % "8.16.0"  % "test",
    "org.asynchttpclient" % "async-http-client" % "2.12.3"  % "test",
    "org.scalamock"      %% "scalamock"         % "5.1.0"   % "test",
    "com.ironcorelabs"   %% "cats-scalatest"    % "3.0.5"   % "test"
  )

  val it = Seq(
    "org.scalatest"     %% "scalatest"           % "3.2.6"         % "it",
    "com.spotify"        % "docker-client"       % "8.16.0"        % "it",
    "com.typesafe.akka" %% "akka-stream-testkit" % akkaVersion     % "it",
    "com.typesafe.akka" %% "akka-http-testkit"   % akkaHttpVersion % "it"
  )

  val akka = Seq(
    "com.typesafe.akka" %% "akka-actor"          % akkaVersion,
    "com.typesafe.akka" %% "akka-stream"         % akkaVersion,
    "com.typesafe.akka" %% "akka-http"           % akkaHttpVersion,
    "com.typesafe.akka" %% "akka-http-core"      % akkaHttpVersion,
    "com.typesafe.akka" %% "akka-slf4j"          % akkaVersion,
    "com.typesafe.akka" %% "akka-stream-testkit" % akkaVersion     % Test,
    "com.typesafe.akka" %% "akka-http-testkit"   % akkaHttpVersion % Test
  )

  val network = Seq(
    "org.bitlet"  % "weupnp"      % "0.1.4",
    "commons-net" % "commons-net" % "3.8.0"
  )

  val circe = Seq(
    "io.circe" %% "circe-core"   % circeVersion,
    "io.circe" %% "circe-parser" % circeVersion
  )

  val misc = Seq(
    "com.chuusai"     %% "shapeless" % "2.3.5",
    "com.iheart"      %% "ficus"     % "1.5.0",
    "org.scalanlp"    %% "breeze"    % "1.1",
    "io.netty"         % "netty"     % "3.10.6.Final",
    "com.google.guava" % "guava"     % "30.1.1-jre",
    "io.estatico"     %% "newtype"   % "0.4.4"
  )

  val monitoring = Seq(
    "io.kamon" %% "kamon-core"     % kamonVersion,
    "io.kamon" %% "kamon-bundle"   % kamonVersion % Runtime,
    "io.kamon" %% "kamon-influxdb" % kamonVersion % Runtime,
    "io.kamon" %% "kamon-zipkin"   % kamonVersion % Runtime
  )

  val graal = Seq(
    "org.graalvm.sdk"     % "graal-sdk"   % graalVersion,
    "org.graalvm.js"      % "js"          % graalVersion,
    "org.graalvm.truffle" % "truffle-api" % graalVersion
  )

  val node: Seq[ModuleID] = {
    Seq(
      "com.typesafe.akka"          %% "akka-cluster"  % akkaVersion,
      "com.typesafe.akka"          %% "akka-remote"   % akkaVersion,
      "com.typesafe"                % "config"        % "1.4.1",
      "com.lihaoyi"                %% "mainargs"      % "0.2.1",
      "net.jpountz.lz4"             % "lz4"           % "1.3.0",
      "com.github.julien-truffaut" %% "monocle-core"  % "3.0.0-M5",
      "com.github.julien-truffaut" %% "monocle-macro" % "3.0.0-M5"
    ) ++
    logging ++
    test ++
    it ++
    akka ++
    network ++
    circe ++
    misc ++
    monitoring
  }

  lazy val common: Seq[ModuleID] = {
    Seq(
      "com.typesafe.akka"      %% "akka-actor"              % akkaVersion,
      "org.typelevel"          %% "simulacrum"              % "1.0.0",
      "org.scala-lang.modules" %% "scala-collection-compat" % "2.4.4",
      "org.scodec"             %% "scodec-bits"             % "1.1.27"
    ) ++
    logging ++
    circe ++
    test
  }

  lazy val chainProgram: Seq[ModuleID] =
    Seq(
      "io.circe"               %% "circe-core"              % circeVersion,
      "io.circe"               %% "circe-parser"            % circeVersion,
      "org.scala-lang.modules" %% "scala-collection-compat" % "2.4.4"
    ) ++
    test ++
    graal

  lazy val brambl: Seq[ModuleID] =
    test

  lazy val akkaHttpRpc: Seq[ModuleID] =
    Seq(
      "de.heikoseeberger"      %% "akka-http-circe"         % "1.36.0",
      "io.circe"               %% "circe-optics"            % circeVersion,
      "io.circe"               %% "circe-generic"           % circeVersion,
      "org.scala-lang.modules" %% "scala-collection-compat" % "2.4.4"
    ) ++
    circe ++
    akka ++
    test

  lazy val toplRpc: Seq[ModuleID] =
    Seq(
      "io.circe"               %% "circe-generic"           % circeVersion,
      "org.scala-lang.modules" %% "scala-collection-compat" % "2.4.4"
    ) ++
    circe ++
    test

  lazy val gjallarhorn: Seq[ModuleID] = {
    Seq(
      "com.typesafe.akka"     %% "akka-cluster" % akkaVersion,
      "com.typesafe.akka"     %% "akka-remote"  % akkaVersion,
      "com.github.pureconfig" %% "pureconfig"   % "0.15.0"
    ) ++
    akka ++
    test ++
    circe ++
    logging ++
    misc ++
    it
  }

  lazy val benchmarking: Seq[ModuleID] = Seq()

  lazy val crypto: Seq[ModuleID] =
    Seq(
      "org.typelevel"     %% "simulacrum"      % "1.0.0",
      "org.typelevel"     %% "cats-core"       % "2.3.0",
      "org.bouncycastle"   % "bcprov-jdk15on"  % "1.68",
      "org.whispersystems" % "curve25519-java" % "0.5.0"
    ) ++
    misc ++
    test
}
