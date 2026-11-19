import sbt._

object Dependencies {
  // Versions
  object Versions {
    val scala213 = "2.13.16"
    val scala3 = "3.3.4"

    val smithy4s = "0.18.45"
    val smithy4sDisney = "0.14.24"
    val apiRegistry = "2025.09.02.5208"

    val catsEffect = "3.6.3"
    val fs2 = "3.12.2"
    val http4s = "0.23.39"
    val circe = "0.14.15"
    val decline = "2.5.0"
    val log4cats = "2.7.1"
    val directories = "0.1.4"
    val jnrUnixSocket = "0.38.24"

    // Test dependencies
    val munit = "1.2.1"
    val munitCatsEffect = "2.0.0"
    val munitScalacheck = "1.2.0"
  }

  // Production dependencies
  val smithy4sDynamic =
    "com.disneystreaming.smithy4s" %% "smithy4s-dynamic" % Versions.smithy4s
  val smithy4sDecline =
    "com.disneystreaming.smithy4s" %% "smithy4s-decline" % Versions.smithy4s
  val declineEffect = "com.monovore" %% "decline-effect" % Versions.decline

  val catsEffect = "org.typelevel" %% "cats-effect" % Versions.catsEffect
  val fs2Core = "co.fs2" %% "fs2-core" % Versions.fs2
  val fs2Io = "co.fs2" %% "fs2-io" % Versions.fs2

  val http4sEmberClient =
    "org.http4s" %% "http4s-ember-client" % Versions.http4s
  val http4sEmberServer =
    "org.http4s" %% "http4s-ember-server" % Versions.http4s
  val http4sDsl = "org.http4s" %% "http4s-dsl" % Versions.http4s

  val circeCore = "io.circe" %% "circe-core" % Versions.circe
  val circeGeneric = "io.circe" %% "circe-generic" % Versions.circe
  val circeParser = "io.circe" %% "circe-parser" % Versions.circe

  val log4catsSlf4j = "org.typelevel" %% "log4cats-slf4j" % Versions.log4cats

  val directories =
    "io.get-coursier.util" % "directories" % Versions.directories
  val jnrUnixSocket =
    "com.github.jnr" % "jnr-unixsocket" % Versions.jnrUnixSocket

  // Disney-specific
  val apiRegistry =
    "com.disneystreaming.api.registry" % "apiregistry" % Versions.apiRegistry
  val smithy4sDisneyHttp4s =
    "com.disneystreaming.api.registry" %% "smithy4s-disney-http4s" % Versions.smithy4sDisney

  // Test dependencies
  val munit = "org.scalameta" %% "munit" % Versions.munit % Test
  val munitCatsEffect =
    "org.typelevel" %% "munit-cats-effect" % Versions.munitCatsEffect % Test
  val munitScalacheck =
    "org.scalameta" %% "munit-scalacheck" % Versions.munitScalacheck % Test

  // Module-specific dependency groups
  object Dynamic {
    val dependencies = Seq(
      smithy4sDynamic,
      smithy4sDecline,
      declineEffect,
      circeCore,
      fs2Core,
      http4sEmberClient,
      apiRegistry,
      smithy4sDisneyHttp4s
    )

    val testDependencies = Seq(
      munit,
      munitScalacheck,
      munitCatsEffect,
      http4sDsl % Test,
      http4sEmberServer % Test
    )
  }

  object Socket {
    val dependencies = Seq(
      fs2Io,
      catsEffect,
      log4catsSlf4j,
      circeCore,
      circeGeneric,
      circeParser,
      jnrUnixSocket
    )

    val testDependencies = Seq(
      munit,
      munitCatsEffect
    )
  }

  object Daemon {
    val dependencies = Seq(
      fs2Io
    )

    val testDependencies = Seq(
      munit,
      munitCatsEffect
    )
  }

}
