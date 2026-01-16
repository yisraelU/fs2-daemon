import Dependencies._

ThisBuild / version := "0.1.0-SNAPSHOT"

// Cross-build configuration for Scala 2.13 and Scala 3
// Use `sbt +compile`, `sbt +test`, `sbt +publish` to build/test/publish for all versions
ThisBuild / scalaVersion := Versions.scala213
ThisBuild / crossScalaVersions := Seq(Versions.scala213, Versions.scala3)
ThisBuild / versionScheme := Some("early-semver")

Global / onChangedBuildSource := ReloadOnSourceChanges

// Scalafix configuration
ThisBuild / semanticdbEnabled := true
ThisBuild / scalafixDependencies += "com.github.liancheng" %% "organize-imports" % "0.6.0"

lazy val root = (project in file("."))
  .aggregate(daemon, socket)
  .settings(
    publish / skip := true
  )

lazy val socket = (project in file("modules/socket"))
  .settings(
    name := "unix-socket",
    testFrameworks += new TestFramework("munit.Framework"),
    libraryDependencies ++= Socket.dependencies ++ Socket.testDependencies
  )

lazy val daemon = (project in file("modules/daemon"))
  .settings(
    name := "daemon",
    testFrameworks += new TestFramework("munit.Framework"),
    libraryDependencies ++= Daemon.dependencies ++ Daemon.testDependencies
  )

addCommandAlias(
  "lint",
  ";scalafmtAll;scalafmtSbt;scalafixAll;"
)
