package com.disneystreaming.apiregistry.socket

import cats.effect.{IO, IOApp}
import fs2.io.net.unixsocket.UnixSocketAddress
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import com.disneystreaming.apiregistry.socket.codecs.{Codec, LineDelimitedCodec}
import com.disneystreaming.apiregistry.socket.server.UnixSocketServer

/**
 * Example usage of UnixSocketServer with LineDelimitedCodec.
 *
 * This demonstrates a simple echo server that:
 * - Listens on a Unix socket
 * - Receives line-delimited text commands
 * - Responds with line-delimited text
 */
object Example extends IOApp.Simple {

  implicit val logger: Logger[IO] = Slf4jLogger.getLogger[IO]
  // Simple string-based example
  def simpleExample: IO[Unit] = {
    // Define a handler that processes string commands
    def handler(command: String): IO[String] = IO.pure {
      s"Echo: $command"
    }

    // Create and run server using fluent builder API
    UnixSocketServer
      .builder[IO, String, String]
      .withSocket(UnixSocketAddress("/tmp/example.sock"))
      .withCodec(LineDelimitedCodec.string[IO])
      .withHandler(handler)
      .build
      .run
  }

  // Example with shutdown control
  def shutdownExample: IO[Unit] = {
    // Handler that responds to "stop" or "kill" commands
    def handler(command: String, shutdown: IO[Unit]): IO[String] =
      command.trim.toLowerCase match {
        case "stop" | "kill" =>
          // Trigger shutdown and send goodbye message
          shutdown.as("Server shutting down. Goodbye!")
        case "ping" =>
          IO.pure("pong")
        case other =>
          IO.pure(s"Echo: $other")
      }

    val socketAddr = UnixSocketAddress("/tmp/shutdown-example.sock")

    // Clean up stale socket file before starting, then create server using builder
    UnixSocketServer.cleanupSocketFile[IO](socketAddr) *>
      UnixSocketServer
        .builder[IO, String, String]
        .withSocket(socketAddr)
        .withCodec(LineDelimitedCodec.string[IO])
        .withHandlerAndShutdown(handler)
        .build
        .run
  }

  // JSON-based example (requires circe)
  /*
  import io.circe._
  import io.circe.generic.semiauto._
  import io.circe.parser.decode
  import io.circe.syntax._

  case class Command(action: String, args: List[String])
  case class Result(status: String, message: String)

  implicit val commandDecoder: Decoder[Command] = deriveDecoder
  implicit val resultEncoder: Encoder[Result] = deriveEncoder

  def jsonExample: IO[Nothing] = {
    // Define handler for typed commands
    def handler(cmd: Command): IO[Result] = IO.pure {
      Result("success", s"Executed ${cmd.action} with args: ${cmd.args.mkString(", ")}")
    }

    // Create codec with JSON serialization
    val codec: Codec[IO, Command, Result] = LineDelimitedCodec.custom[IO, Command, Result](
      line => IO.fromEither(decode[Command](line).leftMap(e => new RuntimeException(e))),
      result => IO.pure(result.asJson.noSpaces)
    )

    // Create and run server using fluent builder API
    UnixSocketServer.builder[IO, Command, Result]
      .withSocket(UnixSocketAddress("/tmp/daemon.sock"))
      .withCodec(codec)
      .withHandler(handler)
      .build
      .run
  }
   */

  // Protocol Example:
  // Client sends: "register service.yaml\n"
  // Server responds: "Echo: register service.yaml\n"

  override def run: IO[Unit] = {
    IO.println(
      "Starting example server with shutdown control on /tmp/shutdown-example.sock"
    ) *>
      IO.println("Send 'stop' or 'kill' to shutdown the server gracefully") *>
      shutdownExample
  }
}

/*
 * Testing with netcat:
 *
 * # Start server in one terminal:
 * $ sbt "socket/runMain com.disneystreaming.apiregistry.socket.Example"
 *
 * # In another terminal, test basic commands:
 * $ nc -U /tmp/shutdown-example.sock
 * hello world
 * Echo: hello world
 * ping
 * pong
 *
 * # Shutdown the server:
 * $ echo "stop" | nc -U /tmp/shutdown-example.sock
 * Server shutting down. Goodbye!
 * # (Server exits in first terminal)
 *
 * # Alternative shutdown:
 * $ echo "kill" | nc -U /tmp/shutdown-example.sock
 * Server shutting down. Goodbye!
 */
