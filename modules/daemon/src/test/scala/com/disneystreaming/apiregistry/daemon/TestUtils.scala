package com.disneystreaming.apiregistry.daemon

import java.nio.file.{Path => JPath}

import cats.effect.IO
import cats.syntax.all._
import fs2.io.file.{Files, Path}

/**
 * Common utilities for daemon tests.
 */
object TestUtils {

  /**
   * Generate a unique daemon name for testing.
   */
  def uniqueName: String =
    s"test-${System.currentTimeMillis()}-${scala.util.Random.nextInt(
      1000000
    )}-${java.util.UUID.randomUUID().toString.take(8)}"

  /**
   * Generate a unique daemon name with custom prefix.
   */
  def uniqueNameWithPrefix(prefix: String): String =
    s"$prefix-${System.currentTimeMillis()}-${scala.util.Random.nextInt(100000)}"

  /**
   * Get the tmp directory.
   */
  def tmpDir: Path =
    Path.fromNioPath(JPath.of(System.getProperty("java.io.tmpdir")))

  /**
   * Clean up a daemon lock directory.
   */
  def cleanup(name: String): IO[Unit] = {
    val lockDir = tmpDir.resolve(name)
    Files[IO].deleteRecursively(lockDir).handleError(_ => ())
  }

  /**
   * Write a string to a file using fs2.
   *
   * This is the correct fs2 pattern:
   * 1. Create a stream with the content
   * 2. Pipe through the writer
   * 3. Compile and drain
   */
  def writeFile(path: Path, content: String): IO[Unit] =
    fs2.Stream
      .emit(content)
      .through(Files[IO].writeUtf8(path))
      .compile
      .drain

  /**
   * Create a lock directory with stale files (simulates crash).
   */
  def createStaleLockDir(
      name: String,
      files: Map[String, String] = Map.empty
  ): IO[Unit] = {
    val lockDir = tmpDir.resolve(name)
    Files[IO].createDirectories(lockDir) *>
      files.toList.traverse_ { case (filename, content) =>
        writeFile(lockDir.resolve(filename), content)
      }
  }
}
