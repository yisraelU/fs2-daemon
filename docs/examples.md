# Examples

A collection of practical examples demonstrating common patterns with fs2-daemon.

## Simple Echo Server

The classic echo server - responds with the message it receives:

```scala
import cats.effect.{IO, IOApp}
import fs2.io.net.unixsocket.UnixSocketAddress
import org.typelevel.log4cats.slf4j.Slf4jLogger
import com.disneystreaming.apiregistry.socket.server.UnixSocketServer
import com.disneystreaming.apiregistry.socket.codecs.LineDelimitedCodec

object EchoServer extends IOApp.Simple {
  implicit val logger = Slf4jLogger.getLogger[IO]

  def handler(msg: String): IO[String] =
    IO.pure(s"Echo: $msg")

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

## CLI Daemon with Shutdown

A daemon that responds to commands and can be gracefully stopped:

```scala
import cats.effect.{IO, IOApp}
import org.typelevel.log4cats.slf4j.Slf4jLogger
import com.disneystreaming.apiregistry.daemon.Daemon
import com.disneystreaming.apiregistry.socket.server.UnixSocketServer
import com.disneystreaming.apiregistry.socket.codecs.LineDelimitedCodec

object CliDaemon extends IOApp.Simple {
  implicit val logger = Slf4jLogger.getLogger[IO]

  def handler(cmd: String, shutdown: IO[Unit]): IO[String] = {
    cmd.trim.toLowerCase match {
      case "stop" | "kill" =>
        shutdown.as("Goodbye!")

      case "ping" =>
        IO.pure("pong")

      case "status" =>
        IO.realTime.map(t => s"Uptime: ${t.toSeconds} seconds")

      case "help" =>
        IO.pure(
          """Commands:
            |  ping   - Check server status
            |  status - Show uptime
            |  stop   - Shutdown server
            |  help   - Show this message
            |""".stripMargin
        )

      case unknown =>
        IO.pure(s"Unknown command: $unknown (try 'help')")
    }
  }

  def run: IO[Unit] = {
    Daemon.acquire[IO]("cli-daemon").use { ctx =>
      IO.println(s"Daemon started with PID ${ctx.pid.value}") *>
      IO.println(s"Socket: ${ctx.socketAddress.path}") *>
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

Usage:
```bash
$ echo "ping" | nc -U /tmp/cli-daemon/daemon.sock
pong

$ echo "status" | nc -U /tmp/cli-daemon/daemon.sock
Uptime: 42 seconds

$ echo "stop" | nc -U /tmp/cli-daemon/daemon.sock
Goodbye!
```

## Streaming Build Server

Stream build output as it's produced:

```scala
import cats.effect.{IO, IOApp}
import fs2.Stream
import fs2.io.net.unixsocket.UnixSocketAddress
import org.typelevel.log4cats.slf4j.Slf4jLogger
import com.disneystreaming.apiregistry.socket.server.UnixSocketServer
import com.disneystreaming.apiregistry.socket.codecs.LineDelimitedCodec
import scala.concurrent.duration._

object BuildServer extends IOApp.Simple {
  implicit val logger = Slf4jLogger.getLogger[IO]

  def runBuild: Stream[IO, String] = {
    Stream(
      "Starting build...",
      "Resolving dependencies...",
      "Compiling sources...",
      "Running tests...",
      "Packaging...",
      "Build complete!"
    ).evalMap { msg =>
      IO.sleep(500.millis).as(msg)  // Simulate work
    }
  }

  def handler(cmd: String): Stream[IO, String] = {
    cmd.trim.toLowerCase match {
      case "build" => runBuild
      case "clean" => Stream.emit("Cleaned build artifacts")
      case other   => Stream.emit(s"Unknown command: $other")
    }
  }

  def run: IO[Unit] = {
    UnixSocketServer
      .builder[IO, String, String]
      .withSocket(UnixSocketAddress("/tmp/build.sock"))
      .withCodec(LineDelimitedCodec.string[IO])
      .withStreamingHandler(handler)
      .build
      .run
  }
}
```

Usage:
```bash
$ echo "build" | nc -U /tmp/build.sock
Starting build...
Resolving dependencies...
Compiling sources...
Running tests...
Packaging...
Build complete!
```

## JSON API Server

Type-safe JSON API using Circe:

```scala
import cats.effect.{IO, IOApp}
import fs2.io.net.unixsocket.UnixSocketAddress
import org.typelevel.log4cats.slf4j.Slf4jLogger
import io.circe._
import io.circe.generic.semiauto._
import io.circe.parser.decode
import io.circe.syntax._
import com.disneystreaming.apiregistry.socket.server.UnixSocketServer
import com.disneystreaming.apiregistry.socket.codecs.LineDelimitedCodec

case class Request(
  method: String,
  path: String,
  body: Option[Json]
)

case class Response(
  status: Int,
  body: Json
)

object JsonApiServer extends IOApp.Simple {
  implicit val logger = Slf4jLogger.getLogger[IO]

  implicit val requestDecoder: Decoder[Request] = deriveDecoder
  implicit val responseEncoder: Encoder[Response] = deriveEncoder

  val codec = LineDelimitedCodec.custom[IO, Request, Response](
    line => IO.fromEither(
      decode[Request](line).leftMap(e => new RuntimeException(e.getMessage))
    ),
    response => IO.pure(response.asJson.noSpaces)
  )

  def handler(req: Request): IO[Response] = {
    (req.method.toUpperCase, req.path) match {
      case ("GET", "/health") =>
        IO.pure(Response(200, Json.obj("status" -> Json.fromString("healthy"))))

      case ("GET", "/time") =>
        IO.realTimeInstant.map { instant =>
          Response(200, Json.obj("timestamp" -> Json.fromLong(instant.toEpochMilli)))
        }

      case ("POST", "/echo") =>
        IO.pure(Response(200, req.body.getOrElse(Json.Null)))

      case _ =>
        IO.pure(Response(
          404,
          Json.obj("error" -> Json.fromString("Not found"))
        ))
    }
  }

  def run: IO[Unit] = {
    UnixSocketServer
      .builder[IO, Request, Response]
      .withSocket(UnixSocketAddress("/tmp/api.sock"))
      .withCodec(codec)
      .withHandler(handler)
      .build
      .run
  }
}
```

Usage:
```bash
$ echo '{"method":"GET","path":"/health","body":null}' | nc -U /tmp/api.sock
{"status":200,"body":{"status":"healthy"}}

$ echo '{"method":"POST","path":"/echo","body":{"hello":"world"}}' | nc -U /tmp/api.sock
{"status":200,"body":{"hello":"world"}}
```

## Log Tailing Server

Stream log lines in real-time:

```scala
import cats.effect.{IO, IOApp, Ref}
import fs2.Stream
import fs2.io.net.unixsocket.UnixSocketAddress
import org.typelevel.log4cats.slf4j.Slf4jLogger
import com.disneystreaming.apiregistry.socket.server.UnixSocketServer
import com.disneystreaming.apiregistry.socket.codecs.LineDelimitedCodec
import scala.concurrent.duration._

object LogServer extends IOApp.Simple {
  implicit val logger = Slf4jLogger.getLogger[IO]

  def generateLogs: Stream[IO, String] = {
    Stream
      .repeatEval(IO.realTimeInstant)
      .metered(1.second)
      .map(instant => s"[$instant] INFO: Heartbeat")
  }

  def handler(cmd: String): Stream[IO, String] = {
    cmd.trim.toLowerCase match {
      case "tail" =>
        generateLogs.take(10)

      case "follow" =>
        generateLogs  // Infinite stream

      case "last" =>
        Stream.emit("[2026-01-16T12:00:00Z] INFO: Last log entry")

      case _ =>
        Stream.emit("Commands: tail, follow, last")
    }
  }

  def run: IO[Unit] = {
    UnixSocketServer
      .builder[IO, String, String]
      .withSocket(UnixSocketAddress("/tmp/logs.sock"))
      .withCodec(LineDelimitedCodec.string[IO])
      .withStreamingHandler(handler)
      .build
      .run
  }
}
```

## State Management

Maintain state across requests:

```scala
import cats.effect.{IO, IOApp, Ref}
import fs2.io.net.unixsocket.UnixSocketAddress
import org.typelevel.log4cats.slf4j.Slf4jLogger
import com.disneystreaming.apiregistry.daemon.Daemon
import com.disneystreaming.apiregistry.socket.server.UnixSocketServer
import com.disneystreaming.apiregistry.socket.codecs.LineDelimitedCodec

object StatefulDaemon extends IOApp.Simple {
  implicit val logger = Slf4jLogger.getLogger[IO]

  case class State(counter: Int, lastMessage: Option[String])

  def handler(state: Ref[IO, State])(cmd: String, shutdown: IO[Unit]): IO[String] = {
    cmd.trim.toLowerCase match {
      case "inc" =>
        state.updateAndGet(s => s.copy(counter = s.counter + 1))
          .map(s => s"Counter: ${s.counter}")

      case "get" =>
        state.get.map(s => s"Counter: ${s.counter}, Last: ${s.lastMessage.getOrElse("none")}")

      case "reset" =>
        state.set(State(0, None)).as("Reset complete")

      case "stop" =>
        shutdown.as("Goodbye!")

      case other =>
        state.update(_.copy(lastMessage = Some(other))).as("Message stored")
    }
  }

  def run: IO[Unit] = {
    Daemon.acquire[IO]("stateful-daemon").use { ctx =>
      Ref[IO].of(State(0, None)).flatMap { stateRef =>
        UnixSocketServer
          .builder[IO, String, String]
          .withSocket(ctx.socketAddress)
          .withCodec(LineDelimitedCodec.string[IO])
          .withHandlerAndShutdown(handler(stateRef))
          .build
          .run
      }
    }
  }
}
```

Usage:
```bash
$ echo "inc" | nc -U /tmp/stateful-daemon/daemon.sock
Counter: 1

$ echo "inc" | nc -U /tmp/stateful-daemon/daemon.sock
Counter: 2

$ echo "hello world" | nc -U /tmp/stateful-daemon/daemon.sock
Message stored

$ echo "get" | nc -U /tmp/stateful-daemon/daemon.sock
Counter: 2, Last: hello world
```

## Testing

Example unit test using MUnit:

```scala
import cats.effect.{IO, Resource}
import fs2.io.net.unixsocket.UnixSocketAddress
import munit.CatsEffectSuite
import org.typelevel.log4cats.slf4j.Slf4jLogger
import com.disneystreaming.apiregistry.socket.server.UnixSocketServer
import com.disneystreaming.apiregistry.socket.codecs.LineDelimitedCodec

class ServerTest extends CatsEffectSuite {
  implicit val logger = Slf4jLogger.getLogger[IO]

  def handler(msg: String): IO[String] =
    IO.pure(s"Echo: $msg")

  val serverResource = Resource.make(
    IO {
      val socketPath = s"/tmp/test-${System.currentTimeMillis()}.sock"
      val server = UnixSocketServer
        .builder[IO, String, String]
        .withSocket(UnixSocketAddress(socketPath))
        .withCodec(LineDelimitedCodec.string[IO])
        .withHandler(handler)
        .build

      (socketPath, server.run.start)
    }
  ) { case (_, fiber) => fiber.cancel }

  test("server responds to requests") {
    // Your test implementation
    IO.pure(())
  }
}
```

## More Examples

Check out the [socket Example.scala](https://github.com/yisraelu/fs2-daemon/blob/main/modules/socket/src/main/scala/com/disneystreaming/apiregistry/socket/Example.scala) in the repository for additional working examples.
