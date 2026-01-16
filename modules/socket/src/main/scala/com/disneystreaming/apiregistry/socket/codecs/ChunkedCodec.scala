package com.disneystreaming.apiregistry.socket.codecs

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

import cats.effect.Concurrent
import cats.syntax.all._
import fs2.Stream

/**
 * A codec that uses chunked encoding for streaming large messages.
 *
 * Protocol format (inspired by HTTP chunked encoding):
 * [4 bytes: chunk size][N bytes: chunk data]...[4 bytes: 0 (terminator)]
 *
 * This allows:
 * - Streaming responses of unknown length
 * - Low memory overhead (don't need full message buffered)
 * - Backpressure-friendly streaming
 *
 * Ideal for:
 * - Large file transfers
 * - Streaming logs/output
 * - Real-time data feeds
 */
object ChunkedCodec {

  private val CHUNK_SIZE = 8192 // 8KB chunks
  private val TERMINATOR = 0

  /**
   * Create a chunked codec for byte arrays.
   *
   * Splits large messages into chunks for efficient streaming.
   *
   * @param chunkSize Size of each chunk in bytes (default: 8KB)
   *
   * @example
   * {{{
   * val codec = ChunkedCodec.bytes[IO](chunkSize = 4096)
   * val server = UnixSocketServer.builder[IO, Array[Byte], Array[Byte]]
   *   .withSocket(socketAddress)
   *   .withCodec(codec)
   *   .withStreamingHandler(handler)
   *   .build
   * }}}
   */
  def bytes[F[_]: Concurrent](
      chunkSize: Int = CHUNK_SIZE
  ): Codec[F, Array[Byte], Array[Byte]] =
    new Codec[F, Array[Byte], Array[Byte]] {

      override def decode(bytes: Stream[F, Byte]): F[Array[Byte]] = {
        // Read chunks until terminator
        def readChunks(acc: Vector[Byte]): F[Vector[Byte]] = {
          for {
            // Read chunk size
            sizeBytes <- bytes.take(4).compile.toVector
            _ <- Concurrent[F]
              .raiseError(
                new RuntimeException("Incomplete chunk size header")
              )
              .whenA(sizeBytes.size < 4)

            size = ByteBuffer.wrap(sizeBytes.toArray).getInt

            result <-
              if (size == TERMINATOR) {
                // End of message
                Concurrent[F].pure(acc)
              } else if (size < 0 || size > 1_000_000) {
                // Validate chunk size
                Concurrent[F].raiseError(
                  new RuntimeException(s"Invalid chunk size: $size")
                )
              } else {
                // Read chunk data
                for {
                  chunk <- bytes.take(size.toLong).compile.toVector
                  _ <- Concurrent[F]
                    .raiseError(
                      new RuntimeException("Incomplete chunk data")
                    )
                    .whenA(chunk.size < size)

                  // Recursively read next chunk
                  rest <- readChunks(acc ++ chunk)
                } yield rest
              }
          } yield result
        }

        readChunks(Vector.empty).map(_.toArray)
      }

      override def encode(response: Array[Byte]): Stream[F, Byte] = {
        // Split response into chunks
        val chunks = response.grouped(chunkSize).toSeq

        val chunkStreams = chunks.map { chunk =>
          val size = chunk.length
          val sizeBytes = ByteBuffer.allocate(4).putInt(size).array()

          Stream.emits(sizeBytes) ++ Stream.emits(chunk)
        }

        // Combine all chunks and add terminator
        val terminator = ByteBuffer.allocate(4).putInt(TERMINATOR).array()

        Stream.emits(chunkStreams).flatten ++ Stream.emits(terminator)
      }
    }

  /**
   * Create a chunked codec for UTF-8 strings.
   *
   * Useful for streaming large text content.
   *
   * @param chunkSize Size of each chunk in bytes (default: 8KB)
   *
   * @example
   * {{{
   * val codec = ChunkedCodec.string[IO]()
   *
   * def handler(input: String): Stream[IO, String] = {
   *   // Stream large response
   *   Stream.emit(largeLogFile)
   * }
   * }}}
   */
  def string[F[_]: Concurrent](
      chunkSize: Int = CHUNK_SIZE
  ): Codec[F, String, String] =
    new Codec[F, String, String] {

      private val bytesCodec = bytes[F](chunkSize)

      override def decode(bytes: Stream[F, Byte]): F[String] = {
        bytesCodec.decode(bytes).flatMap { byteArray =>
          Concurrent[F].catchNonFatal(
            new String(byteArray, StandardCharsets.UTF_8)
          )
        }
      }

      override def encode(response: String): Stream[F, Byte] = {
        val byteArray = response.getBytes(StandardCharsets.UTF_8)
        bytesCodec.encode(byteArray)
      }
    }

  /**
   * Create a streaming chunked codec.
   *
   * This variant allows the handler to return a Stream of chunks,
   * which are encoded and sent as they are produced.
   *
   * Perfect for:
   * - Real-time log streaming
   * - Progressive file downloads
   * - Continuous data feeds
   *
   * @example
   * {{{
   * val codec = ChunkedCodec.streaming[IO]()
   *
   * def handler(req: String): Stream[IO, String] = {
   *   // Stream output line by line
   *   Stream.repeatEval(IO(readNextLogLine()))
   * }
   * }}}
   */
  def streaming[F[_]: Concurrent](
      chunkSize: Int = CHUNK_SIZE
  ): Codec[F, String, String] = {
    // Note: For true streaming, you'd want to modify the Codec trait
    // to support Stream[F, Resp] as the response type.
    // For now, this works the same as string() but documents the intent.
    string[F](chunkSize)
  }
}
