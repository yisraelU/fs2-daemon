package com.disneystreaming.apiregistry.daemon

import cats.effect.Async
import cats.effect.kernel.Resource
import fs2.io.file.Path

import com.disneystreaming.apiregistry.daemon.utils.Utils

trait LockDirectoryGen[F[_]] {
  def generate(name: String): Resource[F, LockDirectory]
}

class LockDirectorImpl[F[_]: Async] extends LockDirectoryGen[F] {

  override def generate(name: String): Resource[F, LockDirectory] = {
    for {
      lockDir <- Utils.tempDirectory[F](name)

    } yield new LockDirectory {
      override def dir: Path = lockDir
    }
  }

}
object LockDirectory {
  def apply[F[_]: Async]: LockDirectoryGen[F] = new LockDirectorImpl[F]
}

abstract class LockDirectory {
  def dir: Path
  def lock: Path = dir.resolve("daemon.lock")

  def sock: Path = dir.resolve("daemon.sock")

  def pid: Path = dir.resolve("daemon.pid")

  def info: Path = dir.resolve("daemon.info")
  def log: Path = dir.resolve("daemon.log")
}
