import Dependencies.*

ThisBuild / version := "0.1.0-SNAPSHOT"

// Cross-build configuration for Scala 2.13 and Scala 3
// Use `sbt +compile`, `sbt +test`, `sbt +publish` to build/test/publish for all versions
ThisBuild / scalaVersion := Versions.scala213
ThisBuild / versionScheme := Some("early-semver")

Global / onChangedBuildSource := ReloadOnSourceChanges

// Scalafix configuration
ThisBuild / semanticdbEnabled := true
ThisBuild / scalafixDependencies += "com.github.liancheng" %% "organize-imports" % "0.6.0"

// Scala versions for cross-compilation
val scalaVersions = Seq(Versions.scala213, Versions.scala3)

lazy val root = (project in file("."))
  .aggregate(socket.projectRefs ++ daemon.projectRefs: _*)
  .settings(
    publish / skip := true
  )

lazy val socket = (projectMatrix in file("modules/socket"))
  .settings(
    name := "unix-socket",
    testFrameworks += new TestFramework("munit.Framework"),
    libraryDependencies ++= Socket.dependencies ++ Socket.testDependencies
  )
  .jvmPlatform(scalaVersions)

lazy val daemon = (projectMatrix in file("modules/daemon"))
  .settings(
    name := "daemon",
    testFrameworks += new TestFramework("munit.Framework"),
    libraryDependencies ++= Daemon.dependencies ++ Daemon.testDependencies
  )
  .jvmPlatform(scalaVersions)

lazy val docs = projectMatrix
  .in(file("site"))
  .jvmPlatform(List(Versions.scala213))
  .enablePlugins(TypelevelSitePlugin)

addCommandAlias(
  "lint",
  ";scalafmtAll;scalafmtSbt;scalafixAll;"
)
