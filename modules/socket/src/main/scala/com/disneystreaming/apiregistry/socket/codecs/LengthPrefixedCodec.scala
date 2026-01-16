package com.disneystreaming.apiregistry.socket.codecs

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

import cats.effect.Concurrent
import cats.syntax.all._
import cats.syntax.applicativeError._
import fs2.Stream

/**
 * A codec that uses a 4-byte length prefix to frame messages.
 *
 * Protocol format:
 * [4 bytes: message length (big-endian)][N bytes: payload]
 *
 * This is more efficient than line-delimited because:
 * - No scanning for delimiters
 * - Supports binary data and multiline strings
 * - Exact byte count means no buffering uncertainty
 *
 * Performance: ~2-5x faster than line-delimited for binary data
 */
object LengthPrefixedCodec {

  /**
   * Create a length-prefixed codec for binary messages.
   *
   * @example
   * {{{
   * val codec = LengthPrefixedCodec.bytes[IO]
   * val server = UnixSocketServer.builder[IO, Array[Byte], Array[Byte]]
   *   .withSocket(socketAddress)
   *   .withCodec(codec)
   *   .withStreamingHandler(handler)
   *   .build
   * }}}
   */
  def bytes[F[_]: Concurrent]: Codec[F, Array[Byte], Array[Byte]] =
    new Codec[F, Array[Byte], Array[Byte]] {

      override def decode(bytes: Stream[F, Byte]): F[Array[Byte]] = {
        for {
          // Read 4-byte length prefix (big-endian)
          lengthBytes <- bytes.take(4).compile.toVector
          _ <- Concurrent[F]
            .raiseError(
              new RuntimeException(
                s"Incomplete length prefix: expected 4 bytes, got ${lengthBytes.size}"
              )
            )
            .whenA(lengthBytes.size < 4)

          length = ByteBuffer.wrap(lengthBytes.toArray).getInt

          // Validate length is reasonable (prevent DoS)
          _ <- Concurrent[F]
            .raiseError(
              new RuntimeException(s"Invalid message length: $length")
            )
            .whenA(length < 0 || length > 10_000_000)

          // Read exact number of payload bytes
          payload <- bytes.take(length.toLong).compile.toVector
          _ <- Concurrent[F]
            .raiseError(
              new RuntimeException(
                s"Incomplete payload: expected $length bytes, got ${payload.size}"
              )
            )
            .whenA(payload.size < length)

        } yield payload.toArray
      }

      override def encode(response: Array[Byte]): Stream[F, Byte] = {
        val length = response.length
        val lengthBytes = ByteBuffer.allocate(4).putInt(length).array()

        Stream.emits(lengthBytes) ++ Stream.emits(response)
      }
    }

  /**
   * Create a length-prefixed codec for UTF-8 String messages.
   *
   * Strings are encoded as UTF-8 bytes with a length prefix.
   * This supports multiline strings and special characters.
   *
   * @example
   * {{{
   * val codec = LengthPrefixedCodec.string[IO]
   * val server = UnixSocketServer.builder[IO, String, String]
   *   .withSocket(socketAddress)
   *   .withCodec(codec)
   *   .withStreamingHandler(handler)
   *   .build
   * }}}
   */
  def string[F[_]: Concurrent]: Codec[F, String, String] =
    new Codec[F, String, String] {

      override def decode(bytes: Stream[F, Byte]): F[String] = {
        for {
          // Read length prefix
          lengthBytes <- bytes.take(4).compile.toVector
          _ <- Concurrent[F]
            .raiseError(
              new RuntimeException("Incomplete length prefix")
            )
            .whenA(lengthBytes.size < 4)

          length = ByteBuffer.wrap(lengthBytes.toArray).getInt

          // Validate length
          _ <- Concurrent[F]
            .raiseError(
              new RuntimeException(s"Invalid message length: $length")
            )
            .whenA(length < 0 || length > 10_000_000)

          // Read payload and decode as UTF-8
          payload <- bytes.take(length.toLong).compile.toVector
          _ <- Concurrent[F]
            .raiseError(
              new RuntimeException("Incomplete payload")
            )
            .whenA(payload.size < length)

          str <- Concurrent[F].catchNonFatal(
            new String(payload.toArray, StandardCharsets.UTF_8)
          )

        } yield str
      }

      override def encode(response: String): Stream[F, Byte] = {
        val strBytes = response.getBytes(StandardCharsets.UTF_8)
        val length = strBytes.length
        val lengthBytes = ByteBuffer.allocate(4).putInt(length).array()

        Stream.emits(lengthBytes) ++ Stream.emits(strBytes)
      }
    }

  /**
   * Create a length-prefixed codec with custom serialization.
   *
   * This allows you to use any serialization format (JSON, Protobuf, etc.)
   * with length-prefixed framing.
   *
   * @param serialize Convert response to bytes
   * @param deserialize Parse bytes into request
   *
   * @example
   * {{{
   * import io.circe.parser.decode
   * import io.circe.syntax._
   *
   * val codec = LengthPrefixedCodec.custom[IO, Command, Result](
   *   result => IO.pure(result.asJson.noSpaces.getBytes(StandardCharsets.UTF_8)),
   *   bytes => IO.fromEither(decode[Command](new String(bytes, StandardCharsets.UTF_8)))
   * )
   * }}}
   */
  def custom[F[_]: Concurrent, Req, Resp](
      serialize: Resp => F[Array[Byte]],
      deserialize: Array[Byte] => F[Req]
  ): Codec[F, Req, Resp] = new Codec[F, Req, Resp] {

    override def decode(bytes: Stream[F, Byte]): F[Req] = {
      for {
        // Read length prefix
        lengthBytes <- bytes.take(4).compile.toVector
        _ <- Concurrent[F]
          .raiseError(
            new RuntimeException("Incomplete length prefix")
          )
          .whenA(lengthBytes.size < 4)

        length = ByteBuffer.wrap(lengthBytes.toArray).getInt

        // Validate length
        _ <- Concurrent[F]
          .raiseError(
            new RuntimeException(s"Invalid message length: $length")
          )
          .whenA(length < 0 || length > 10_000_000)

        // Read payload
        payload <- bytes.take(length.toLong).compile.toVector
        _ <- Concurrent[F]
          .raiseError(
            new RuntimeException("Incomplete payload")
          )
          .whenA(payload.size < length)

        // Deserialize
        result <- Concurrent[F].attempt(deserialize(payload.toArray))
        req <- result match {
          case Left(error) =>
            Concurrent[F].raiseError[Req](
              new RuntimeException(
                s"Deserialization failed: ${error.getMessage}",
                error
              )
            )
          case Right(value) => Concurrent[F].pure(value)
        }

      } yield req
    }

    override def encode(response: Resp): Stream[F, Byte] = {
      Stream.eval(serialize(response)).flatMap { respBytes =>
        val length = respBytes.length
        val lengthBytes = ByteBuffer.allocate(4).putInt(length).array()

        Stream.emits(lengthBytes) ++ Stream.emits(respBytes)
      }
    }
  }
}
