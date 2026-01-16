package com.disneystreaming.apiregistry.socket.server

import cats.effect.Concurrent
import cats.effect.syntax.all._
import cats.syntax.all._
import fs2.Stream
import fs2.io.file.{Files, Path}
import fs2.io.net.unixsocket.{UnixSocketAddress, UnixSockets}
import org.typelevel.log4cats.Logger

import com.disneystreaming.apiregistry.socket.codecs.Codec

/**
 * A Unix domain socket server that handles request/response cycles.
 *
 * This server supports both single-response and streaming-response handlers.
 * The core implementation is streaming-based, with convenience methods for
 * non-streaming handlers that lift F[Resp] to Stream[F, Resp].
 */
trait UnixSocketServer[F[_]] {

  /** Run the server (runs indefinitely until error or interruption) */
  def run: F[Unit]
}

object UnixSocketServer {

  // ========== Fluent Builder API ==========

  /**
   * Entry point for the fluent builder API.
   *
   * @example
   * {{{
   * val server = UnixSocketServer.builder[IO, String, String]
   *   .withSocket(UnixSocketAddress("/tmp/daemon.sock"))
   *   .withCodec(LineDelimitedCodec.string[IO])
   *   .withStreamingHandler(req => Stream.emit(s"Echo: $req"))
   *   .build
   * }}}
   */
  def builder[F[_], Req, Resp]: InitStage[F, Req, Resp] =
    new InitStage[F, Req, Resp]

  /**
   * Builder step 1: Set the Unix socket address (required).
   */
  class InitStage[F[_], Req, Resp] {
    def withSocket(
        socketAddress: UnixSocketAddress
    ): SocketPhase[F, Req, Resp] =
      new SocketPhase(socketAddress)
  }

  /**
   * Builder step 2: Set the codec for serialization/deserialization (required).
   */
  class SocketPhase[F[_], Req, Resp](socketAddress: UnixSocketAddress) {
    def withCodec(codec: Codec[F, Req, Resp]): CodecPhase[F, Req, Resp] =
      new CodecPhase(socketAddress, codec)
  }

  /**
   * Builder step 3: Choose the handler type.
   *
   * You can choose from:
   * - `withHandler` - Non-streaming handler (Req => F[Resp])
   * - `withStreamingHandler` - Streaming handler (Req => Stream[F, Resp])
   * - `withHandlerAndShutdown` - Non-streaming with shutdown callback
   * - `withStreamingHandlerAndShutdown` - Streaming with shutdown callback
   */
  class CodecPhase[F[_], Req, Resp](
      socketAddress: UnixSocketAddress,
      codec: Codec[F, Req, Resp]
  ) {

    /** Set a non-streaming handler that returns a single response. */
    def withHandler(handler: Req => F[Resp]): BuilderReady[F, Req, Resp] =
      new BuilderReady(socketAddress, codec, HandlerType.NonStreaming(handler))

    /** Set a streaming handler that returns a stream of responses. */
    def withStreamingHandler(
        handler: Req => Stream[F, Resp]
    ): BuilderReady[F, Req, Resp] =
      new BuilderReady(socketAddress, codec, HandlerType.Streaming(handler))

    /** Set a non-streaming handler with shutdown control. */
    def withHandlerAndShutdown(
        handler: (Req, F[Unit]) => F[Resp]
    ): BuilderReady[F, Req, Resp] =
      new BuilderReady(
        socketAddress,
        codec,
        HandlerType.NonStreamingWithShutdown(handler)
      )

    /** Set a streaming handler with shutdown control. */
    def withStreamingHandlerAndShutdown(
        handler: (Req, F[Unit]) => Stream[F, Resp]
    ): BuilderReady[F, Req, Resp] =
      new BuilderReady(
        socketAddress,
        codec,
        HandlerType.StreamingWithShutdown(handler)
      )
  }

  /**
   * Final builder step: Build the server.
   *
   * Requires implicit context bounds: UnixSockets[F], Logger[F], Concurrent[F]
   */
  class BuilderReady[F[_], Req, Resp](
      socketAddress: UnixSocketAddress,
      codec: Codec[F, Req, Resp],
      handlerType: HandlerType[F, Req, Resp]
  ) {
    def build(implicit
        US: UnixSockets[F],
        L: Logger[F],
        C: Concurrent[F]
    ): UnixSocketServer[F] = {
      implicit val implicitCodec: Codec[F, Req, Resp] = codec

      handlerType match {
        case HandlerType.NonStreaming(handler) =>
          UnixSocketServer(socketAddress)(handler)

        case HandlerType.Streaming(handler) =>
          UnixSocketServer.streaming(socketAddress, handler, codec)

        case HandlerType.NonStreamingWithShutdown(handler) =>
          UnixSocketServer.withShutdown(socketAddress)(handler)

        case HandlerType.StreamingWithShutdown(handler) =>
          UnixSocketServer.streamingWithShutdown(socketAddress, handler, codec)
      }
    }
  }

  // Internal ADT for handler types
  sealed trait HandlerType[F[_], Req, Resp]
  private object HandlerType {
    case class NonStreaming[F[_], Req, Resp](handler: Req => F[Resp])
        extends HandlerType[F, Req, Resp]
    case class Streaming[F[_], Req, Resp](handler: Req => Stream[F, Resp])
        extends HandlerType[F, Req, Resp]
    case class NonStreamingWithShutdown[F[_], Req, Resp](
        handler: (Req, F[Unit]) => F[Resp]
    ) extends HandlerType[F, Req, Resp]
    case class StreamingWithShutdown[F[_], Req, Resp](
        handler: (Req, F[Unit]) => Stream[F, Resp]
    ) extends HandlerType[F, Req, Resp]
  }

  // ========== Streaming API (Core Implementation) ==========

  /**
   * Create a streaming Unix socket server.
   *
   * This is the core implementation that handles streaming responses.
   * The handler returns a Stream of responses that are sent back to the
   * client as they are produced.
   *
   * @param socketAddress The Unix socket path to bind to
   * @param handler Function that receives a request and returns a stream of responses
   * @param codec Codec for request/response serialization
   * @return Server instance
   *
   * @example
   * {{{
   * def handler(command: String): Stream[IO, String] = {
   *   // Stream output as it's produced
   *   OutputCapture.captureOutput(runCliCommand(command))
   * }
   *
   * val server = UnixSocketServer.streaming[IO, String, String](
   *   UnixSocketAddress("/tmp/daemon.sock"),
   *   handler,
   *   LineDelimitedCodec.string[IO]
   * )
   * }}}
   */
  def streaming[F[_]: UnixSockets: Logger: Concurrent, Req, Resp](
      socketAddress: UnixSocketAddress,
      handler: Req => Stream[F, Resp],
      codec: Codec[F, Req, Resp]
  ): UnixSocketServer[F] = new UnixSocketServer[F] {
    val logger = Logger[F]

    override def run: F[Unit] = {
      UnixSockets[F]
        .server(socketAddress)
        .evalMap { socket =>
          handleConnection(socket, handler, codec)
            .handleErrorWith { error =>
              logger.error(error)(
                s"error thrown while handling connection on $socket"
              )
            }
        }
        .compile
        .drain
    }
  }

  /**
   * Create a streaming Unix socket server with shutdown control.
   *
   * The handler receives a shutdown callback that can be called to gracefully
   * stop the server. This allows implementing commands like "stop" or "kill".
   *
   * @param socketAddress The Unix socket path to bind to
   * @param handler Function that receives (request, shutdown) and returns stream of responses
   * @param codec Codec for request/response serialization
   * @return Server instance
   *
   * @example
   * {{{
   * def handler(command: String, shutdown: IO[Unit]): Stream[IO, String] =
   *   command.trim.toLowerCase match {
   *     case "stop" => Stream.eval(shutdown) >> Stream.emit("Goodbye!")
   *     case cmd => processCommand(cmd)
   *   }
   *
   * val server = UnixSocketServer.streamingWithShutdown[IO, String, String](
   *   UnixSocketAddress("/tmp/daemon.sock"),
   *   handler,
   *   LineDelimitedCodec.string[IO]
   * )
   * }}}
   */
  def streamingWithShutdown[F[_]: UnixSockets: Logger: Concurrent, Req, Resp](
      socketAddress: UnixSocketAddress,
      handler: (Req, F[Unit]) => Stream[F, Resp],
      codec: Codec[F, Req, Resp]
  ): UnixSocketServer[F] = new UnixSocketServer[F] {
    val logger = Logger[F]

    override def run: F[Unit] = {
      (for {
        shutdownSignal <- Concurrent[F].deferred[Unit]

        serverStream = UnixSockets[F]
          .server(socketAddress)
          .evalMap { socket =>
            val shutdown = shutdownSignal.complete(()).void
            handleConnectionWithShutdown(
              socket,
              handler,
              codec,
              shutdown
            ).handleErrorWith { error =>
              logger.error(error)(
                s"error thrown while handling connection on $socket"
              )
            }
          }
          .compile
          .drain

        // Race server with shutdown signal
        result <- serverStream.race(shutdownSignal.get)

        _ <- result match {
          case Left(_) =>
            Concurrent[F].raiseError[Unit](
              new RuntimeException("Server terminated unexpectedly")
            )
          case Right(_) => Concurrent[F].unit
        }
      } yield ())
    }
  }

  // ========== Non-Streaming API (Convenience Wrappers) ==========

  /**
   * Create a Unix socket server for single-response handlers.
   *
   * This is a convenience wrapper around the streaming API for handlers
   * that return a single response wrapped in F[_].
   *
   * @param socketAddress The Unix socket path to bind to
   * @param handler Function to process requests and generate a single response
   * @param codec Implicit codec for serialization/deserialization
   * @return A server instance
   *
   * @example
   * {{{
   * def handler(req: String): IO[String] = req match {
   *   case "ping" => IO.pure("pong")
   *   case other  => IO.pure(s"Echo: $other")
   * }
   *
   * val server = UnixSocketServer[IO, String, String](
   *   UnixSocketAddress("/tmp/daemon.sock")
   * )(handler)
   * }}}
   */
  def apply[F[_]: UnixSockets: Logger: Concurrent, Req, Resp](
      socketAddress: UnixSocketAddress
  )(
      handler: Req => F[Resp]
  )(implicit codec: Codec[F, Req, Resp]): UnixSocketServer[F] = {
    // Lift F[Resp] to Stream[F, Resp]
    streaming(socketAddress, (req: Req) => Stream.eval(handler(req)), codec)
  }

  /**
   * Create a Unix socket server with shutdown control for single-response handlers.
   *
   * This is a convenience wrapper around the streaming API for handlers
   * that return a single response wrapped in F[_].
   *
   * @param socketAddress The Unix socket path to bind to
   * @param handler Function that receives (request, shutdownCallback) and returns single response
   * @param codec Implicit codec for serialization/deserialization
   * @return A server instance
   *
   * @example
   * {{{
   * def handler(req: String, shutdown: IO[Unit]): IO[String] = req match {
   *   case "stop" => shutdown.as("Shutting down...")
   *   case other  => IO.pure(s"Echo: $other")
   * }
   *
   * val server = UnixSocketServer.withShutdown[IO, String, String](
   *   UnixSocketAddress("/tmp/daemon.sock")
   * )(handler)
   * }}}
   */
  def withShutdown[F[_]: UnixSockets: Logger: Concurrent, Req, Resp](
      socketAddress: UnixSocketAddress
  )(
      handler: (Req, F[Unit]) => F[Resp]
  )(implicit codec: Codec[F, Req, Resp]): UnixSocketServer[F] = {
    // Lift F[Resp] to Stream[F, Resp]
    streamingWithShutdown(
      socketAddress,
      (req: Req, shutdown: F[Unit]) => Stream.eval(handler(req, shutdown)),
      codec
    )
  }

  // ========== Private Helper Methods ==========

  private def handleConnection[F[_]: Concurrent, Req, Resp](
      socket: fs2.io.net.Socket[F],
      handler: Req => Stream[F, Resp],
      codec: Codec[F, Req, Resp]
  ): F[Unit] = {
    for {
      // 1. Decode request from incoming bytes
      request <- codec.decode(socket.reads)

      // 2. Process request with handler - returns Stream of responses
      responses = handler(request)

      // 3. Encode each response and write back to socket
      _ <- responses
        .flatMap(codec.encode)
        .through(socket.writes)
        .compile
        .drain
    } yield ()
  }

  private def handleConnectionWithShutdown[F[_]: Concurrent, Req, Resp](
      socket: fs2.io.net.Socket[F],
      handler: (Req, F[Unit]) => Stream[F, Resp],
      codec: Codec[F, Req, Resp],
      shutdown: F[Unit]
  ): F[Unit] = {
    for {
      // 1. Decode request
      request <- codec.decode(socket.reads)

      // 2. Process request with handler
      responses = handler(request, shutdown)

      // 3. Stream responses back
      _ <- responses
        .flatMap(codec.encode)
        .through(socket.writes)
        .compile
        .drain
    } yield ()
  }

  /**
   * Helper to clean up socket files.
   * Useful for removing stale socket files before starting server.
   */
  def cleanupSocketFile[F[_]: Files: Concurrent](
      socketAddress: UnixSocketAddress
  ): F[Unit] = {
    Files[F].deleteIfExists(Path(socketAddress.path)).void
  }
}
