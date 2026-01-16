package com.disneystreaming.apiregistry.socket.codecs

import cats.effect.Concurrent
import cats.syntax.all._
import fs2.Stream

/**
 * A codec that uses newline delimiters to frame messages.
 *
 * Messages are expected to be UTF-8 encoded text terminated by '\n'.
 * This is ideal for CLI daemon protocols where commands and responses
 * are single-line messages.
 */
object LineDelimitedCodec {

  /**
   * Create a line-delimited codec for String messages.
   *
   * @example
   * {{{
   * // Client sends: "register service.yaml\n"
   * // Server responds: "Service registered successfully\n"
   *
   * val codec = LineDelimitedCodec.string[IO]
   * val server = UnixSocketServer[IO, String, String](
   *   UnixSocketAddress("/tmp/daemon.sock")
   * )(handleCommand)(codec)
   * }}}
   */
  def string[F[_]: Concurrent]: Codec[F, String, String] =
    new Codec[F, String, String] {

      override def decode(bytes: Stream[F, Byte]): F[String] = {
        bytes
          .through(fs2.text.utf8.decode) // Convert bytes to String
          .through(fs2.text.lines) // Split on newlines
          .head // Take first line
          .compile
          .lastOrError // Get the line or fail if empty
          .handleErrorWith { error =>
            Concurrent[F].raiseError(
              new RuntimeException(
                s"Failed to decode line-delimited message: ${error.getMessage}",
                error
              )
            )
          }
      }

      override def encode(response: String): Stream[F, Byte] = {
        // Ensure response ends with newline
        val withNewline =
          if (response.endsWith("\n")) response else response + "\n"

        Stream
          .emit(withNewline)
          .through(fs2.text.utf8.encode) // Convert String to bytes
      }
    }

  /**
   * Create a line-delimited codec with custom serialization/deserialization.
   *
   * This allows you to use JSON or other formats while keeping newline framing.
   *
   * @param deserialize Parse a String line into Request
   * @param serialize Convert Response to String
   *
   * @example
   * {{{
   * import io.circe.parser.decode
   * import io.circe.syntax._
   *
   * val codec = LineDelimitedCodec.custom[IO, Command, Result](
   *   line => IO.fromEither(decode[Command](line)),
   *   result => IO.pure(result.asJson.noSpaces)
   * )
   * }}}
   */
  def custom[F[_]: Concurrent, Req, Resp](
      deserialize: String => F[Req],
      serialize: Resp => F[String]
  ): Codec[F, Req, Resp] = new Codec[F, Req, Resp] {

    override def decode(bytes: Stream[F, Byte]): F[Req] = {
      bytes
        .through(fs2.text.utf8.decode)
        .through(fs2.text.lines)
        .head
        .compile
        .lastOrError
        .flatMap(deserialize)
        .handleErrorWith { error =>
          Concurrent[F].raiseError(
            new RuntimeException(
              s"Failed to deserialize message: ${error.getMessage}",
              error
            )
          )
        }
    }

    override def encode(response: Resp): Stream[F, Byte] = {
      Stream
        .eval(serialize(response))
        .map(str => if (str.endsWith("\n")) str else str + "\n")
        .through(fs2.text.utf8.encode)
    }
  }
}
