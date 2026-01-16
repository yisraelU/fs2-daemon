# Getting Started

This guide will walk you through creating your first daemon with fs2-daemon.

## Requirements

- **Scala**: 2.13.16+ or 3.3.4+
- **JDK**: 11 or higher
  - JDK 16+: Native Unix socket support (recommended)
  - JDK 11-15: Requires JNR-UnixSocket (included automatically)

### Unix Socket Support

This library includes JNR-UnixSocket as a dependency to ensure compatibility across all JDK versions:

- **JDK 16+**: Uses native `java.nio.channels.UnixDomainSocketChannel`
  - JNR dependency is included but not used at runtime
  - You can optionally exclude it if you only target JDK 16+ (see below)

- **JDK 11-15**: Uses JNR-UnixSocket for Unix domain socket support
  - JNR dependency is required and will be used at runtime

### Excluding JNR on JDK 16+

If you're only targeting JDK 16 or higher, you can exclude the JNR dependency:

```scala
libraryDependencies ++= Seq(
  ("io.github.yisraelu" %% "unix-socket" % "@VERSION@")
    .exclude("com.github.jnr", "jnr-unixsocket")
)
```

However, keeping the dependency is harmless - it won't affect performance on JDK 16+ since the native implementation is used instead.

## Installation

Add the following to your `build.sbt`:

```scala
libraryDependencies ++= Seq(
  "io.github.yisraelu" %% "daemon" % "@VERSION@",
  "io.github.yisraelu" %% "unix-socket" % "@VERSION@"
)
```

## Your First Echo Server

Let's create a simple echo server that listens on a Unix socket:

```scala
import cats.effect.{IO, IOApp}
import fs2.io.net.unixsocket.UnixSocketAddress
import org.typelevel.log4cats.slf4j.Slf4jLogger
import com.disneystreaming.apiregistry.socket.server.UnixSocketServer
import com.disneystreaming.apiregistry.socket.codecs.LineDelimitedCodec

object EchoServer extends IOApp.Simple {
  implicit val logger = Slf4jLogger.getLogger[IO]

  def handler(command: String): IO[String] =
    IO.pure(s"Echo: $command")

  def run: IO[Unit] = {
    UnixSocketServer
      .builder[IO, String, String]
      .withSocket(UnixSocketAddress("/tmp/echo.sock"))
      .withCodec(LineDelimitedCodec.string[IO])
      .withHandler(handler)
      .build
      .run
  }
}
```

Run the server:
```bash
$ sbt "socket/run"
```

Test it with netcat in another terminal:
```bash
$ nc -U /tmp/echo.sock
hello world
Echo: hello world
```

## Adding Daemon Management

Now let's enhance the server with proper daemon lifecycle management:

```scala
import cats.effect.{IO, IOApp}
import org.typelevel.log4cats.slf4j.Slf4jLogger
import com.disneystreaming.apiregistry.daemon.Daemon
import com.disneystreaming.apiregistry.socket.server.UnixSocketServer
import com.disneystreaming.apiregistry.socket.codecs.LineDelimitedCodec

object MyDaemon extends IOApp.Simple {
  implicit val logger = Slf4jLogger.getLogger[IO]

  def handler(command: String, shutdown: IO[Unit]): IO[String] =
    command.trim.toLowerCase match {
      case "stop" | "kill" =>
        shutdown.as("Server shutting down. Goodbye!")
      case "ping" =>
        IO.pure("pong")
      case other =>
        IO.pure(s"Echo: $other")
    }

  def run: IO[Unit] = {
    Daemon.acquire[IO]("my-daemon").use { ctx =>
      IO.println(s"Daemon started with PID ${ctx.pid.value}") *>
      IO.println(s"Socket at ${ctx.socketAddress.path}") *>
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

This version:
- Creates a lock directory at `/tmp/my-daemon/`
- Ensures only one instance runs at a time
- Writes PID and socket information
- Supports graceful shutdown via "stop" or "kill" commands

Check the daemon status:
```bash
$ cat /tmp/my-daemon/daemon.info
pid=12345
socket=/tmp/my-daemon/daemon.sock
timestamp=1705428123456
started=2026-01-16T10:15:23.456Z
```

Interact with the daemon:
```bash
$ echo "ping" | nc -U /tmp/my-daemon/daemon.sock
pong

$ echo "stop" | nc -U /tmp/my-daemon/daemon.sock
Server shutting down. Goodbye!
```

## Next Steps

- Explore [Examples](../examples.md) for more patterns
- Learn about [Codecs](../socket.md#codecs) for different serialization formats
- Read about [Daemon management](../daemon.md) in detail
