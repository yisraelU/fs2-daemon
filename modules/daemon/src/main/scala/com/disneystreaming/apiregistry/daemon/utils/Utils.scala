package com.disneystreaming.apiregistry.daemon.utils

import java.nio.file.{Path => JPath, StandardOpenOption}

import cats.effect.kernel.Resource
import cats.effect.{Async, Sync}
import cats.implicits._
import fs2.io.file.{Files, Flags, Path}

import com.disneystreaming.apiregistry.daemon.Pid

object Utils {

  /**
   * creates a directory under the temp directory of OS , with proper cleanup
   * @param childName
   * @tparam F
   * @return
   */
  def tempDirectory[F[_]: Async](
      childName: String
  ): Resource[F, Path] = {
    val tmpdir =
      Path.fromNioPath(JPath.of(System.getProperty("java.io.tmpdir")))
    val dir = tmpdir.resolve(childName)
    Resource.make {
      Files[F].createDirectories(dir).as(dir)
    } { p =>
      Files[F]
        .deleteRecursively(p)
        .handleError(_ => ())
    }
  }

  def getCurrentPid[F[_]: Sync]: F[Pid] =
    Sync[F].blocking(ProcessHandle.current().pid()).map(Pid)

  def pidIsRunning[F[_]: Sync](pid: Pid): F[Boolean] = {
    Sync[F].blocking(ProcessHandle.of(pid.value).isPresent)
  }

  def deleteIfExists[F[_]: Async](files: Path*): F[Unit] = {
    files.traverse(Files[F].deleteIfExists).void
  }
  def writeFile[F[_]: Async](path: Path)(value: String*): F[Unit] = {
    fs2.Stream
      .emits(value)
      .through(
        Files[F].writeUtf8Lines(
          path,
          Flags.fromOpenOptions(List(StandardOpenOption.CREATE_NEW))
        )
      )
      .compile
      .drain
  }
}
