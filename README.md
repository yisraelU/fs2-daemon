# fs2-daemon

[![Apache 2.0 License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Scala 2.13](https://img.shields.io/badge/scala-2.13-red.svg)](https://www.scala-lang.org/)
[![Scala 3](https://img.shields.io/badge/scala-3.3-red.svg)](https://www.scala-lang.org/)

Fs2 utilities for writing daemon processes that communicate over Unix domain sockets.

## Overview

This library provides type-safe, composable building blocks for creating long-running daemon processes with robust IPC over Unix sockets. Built on FS2 and Cats Effect, it offers:

- **Daemon Management**: Process locking, PID files, metadata tracking
- **Unix Socket Server**: High-performance local IPC with multiple codec options
- **Type Safety**: Generic request/response types with compile-time guarantees
- **Resource Safety**: Automatic cleanup via Cats Effect's `Resource`
- **Streaming Support**: Both single-response and streaming handlers

## Quick Example

```scala
import cats.effect.{IO, IOApp}
import org.typelevel.log4cats.slf4j.Slf4jLogger
import com.disneystreaming.apiregistry.daemon.Daemon
import com.disneystreaming.apiregistry.socket.server.UnixSocketServer
import com.disneystreaming.apiregistry.socket.codecs.LineDelimitedCodec

object MyDaemon extends IOApp.Simple {
  implicit val logger = Slf4jLogger.getLogger[IO]

  def handler(cmd: String, shutdown: IO[Unit]): IO[String] =
    cmd.trim.toLowerCase match {
      case "ping" => IO.pure("pong")
      case "stop" => shutdown.as("Goodbye!")
      case other  => IO.pure(s"Echo: $other")
    }

  def run: IO[Unit] = {
    Daemon.acquire[IO]("my-daemon").use { ctx =>
      UnixSocketServer
        .builder[IO, String, String]
        .withSocket(ctx.socketAddress)
        .withCodec(LineDelimitedCodec.string[IO])
        .withHandlerAndShutdown(handler)
        .build
        .run
    }
  }
}
```

## Documentation

For complete documentation, guides, and examples, visit the [**documentation site**](https://yisraelu.github.io/fs2-daemon).

- [Getting Started Guide](https://yisraelu.github.io/fs2-daemon/guide/getting-started.html)
- [Daemon Module](https://yisraelu.github.io/fs2-daemon/daemon.html)
- [Socket Module](https://yisraelu.github.io/fs2-daemon/socket.html)
- [Examples](https://yisraelu.github.io/fs2-daemon/examples.html)

## Installation

Add to your `build.sbt`:

```scala
libraryDependencies ++= Seq(
  "io.github.yisraelu" %% "daemon" % "0.1.0-SNAPSHOT",
  "io.github.yisraelu" %% "unix-socket" % "0.1.0-SNAPSHOT"
)
```

Cross-published for Scala 2.13 and Scala 3.

**Requirements**: JDK 11+ (JDK 16+ recommended for native Unix socket support). This library includes JNR-UnixSocket for JDK 11-15 compatibility; JDK 16+ users can optionally exclude it.

## License

Apache License 2.0 - Copyright 2026 Yisrael Union
