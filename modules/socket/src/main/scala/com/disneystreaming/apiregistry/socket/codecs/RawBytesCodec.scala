package com.disneystreaming.apiregistry.socket.codecs

import java.nio.charset.StandardCharsets

import cats.effect.Concurrent
import cats.syntax.all._
import fs2.Stream

/**
 * A codec that reads/writes raw bytes without any framing.
 *
 * This is the simplest possible codec - it reads all available bytes
 * until the connection closes, and writes bytes directly to the socket.
 *
 * WARNING: This codec has limitations:
 * - No message boundaries (reads until EOF)
 * - Not suitable for request/response protocols
 * - Each connection can only handle one message
 *
 * Use cases:
 * - Simple file transfers
 * - Single-shot commands
 * - Custom protocols where you handle framing yourself
 */
object RawBytesCodec {

  /**
   * Create a raw bytes codec.
   *
   * Reads all bytes until the stream ends.
   *
   * @example
   * {{{
   * val codec = RawBytesCodec.bytes[IO]
   *
   * def handler(data: Array[Byte]): Stream[IO, Array[Byte]] = {
   *   // Process binary data
   *   Stream.emit(processData(data))
   * }
   * }}}
   */
  def bytes[F[_]: Concurrent]: Codec[F, Array[Byte], Array[Byte]] =
    new Codec[F, Array[Byte], Array[Byte]] {

      override def decode(bytes: Stream[F, Byte]): F[Array[Byte]] = {
        // Read all bytes until stream ends
        bytes.compile.toVector.map(_.toArray)
      }

      override def encode(response: Array[Byte]): Stream[F, Byte] = {
        // Write all bytes directly
        Stream.emits(response)
      }
    }

  /**
   * Create a raw UTF-8 string codec.
   *
   * Reads all bytes as UTF-8 text until the stream ends.
   *
   * @example
   * {{{
   * val codec = RawBytesCodec.string[IO]
   *
   * def handler(text: String): Stream[IO, String] = {
   *   // Process text
   *   Stream.emit(s"Received: $text")
   * }
   * }}}
   */
  def string[F[_]: Concurrent]: Codec[F, String, String] =
    new Codec[F, String, String] {

      override def decode(bytes: Stream[F, Byte]): F[String] = {
        bytes
          .through(fs2.text.utf8.decode)
          .compile
          .string
      }

      override def encode(response: String): Stream[F, Byte] = {
        Stream
          .emit(response)
          .through(fs2.text.utf8.encode)
      }
    }

  /**
   * Create a raw codec with size limit.
   *
   * Like bytes() but enforces a maximum message size to prevent DoS.
   *
   * @param maxSize Maximum bytes to read (default: 10MB)
   *
   * @example
   * {{{
   * val codec = RawBytesCodec.bounded[IO](maxSize = 1_000_000) // 1MB max
   * }}}
   */
  def bounded[F[_]: Concurrent](
      maxSize: Long = 10_000_000
  ): Codec[F, Array[Byte], Array[Byte]] =
    new Codec[F, Array[Byte], Array[Byte]] {

      override def decode(bytes: Stream[F, Byte]): F[Array[Byte]] = {
        bytes
          .take(maxSize)
          .compile
          .toVector
          .flatMap { vec =>
            if (vec.isEmpty) {
              Concurrent[F].raiseError(
                new RuntimeException("No data received")
              )
            } else {
              Concurrent[F].pure(vec.toArray)
            }
          }
      }

      override def encode(response: Array[Byte]): Stream[F, Byte] = {
        if (response.length > maxSize) {
          Stream.raiseError(
            new RuntimeException(
              s"Response too large: ${response.length} > $maxSize"
            )
          )
        } else {
          Stream.emits(response)
        }
      }
    }

  /**
   * Create a raw codec for fixed-size messages.
   *
   * Enforces exact message size - useful for fixed-length protocols.
   *
   * @param size Exact message size in bytes
   *
   * @example
   * {{{
   * // Protocol with fixed 1024-byte messages
   * val codec = RawBytesCodec.fixedSize[IO](1024)
   * }}}
   */
  def fixedSize[F[_]: Concurrent](
      size: Int
  ): Codec[F, Array[Byte], Array[Byte]] =
    new Codec[F, Array[Byte], Array[Byte]] {

      override def decode(bytes: Stream[F, Byte]): F[Array[Byte]] = {
        bytes.take(size.toLong).compile.toVector.flatMap { vec =>
          if (vec.size < size) {
            Concurrent[F].raiseError(
              new RuntimeException(
                s"Expected $size bytes, got ${vec.size}"
              )
            )
          } else {
            Concurrent[F].pure(vec.toArray)
          }
        }
      }

      override def encode(response: Array[Byte]): Stream[F, Byte] = {
        if (response.length != size) {
          Stream.raiseError(
            new RuntimeException(
              s"Response must be exactly $size bytes, got ${response.length}"
            )
          )
        } else {
          Stream.emits(response)
        }
      }
    }
}
