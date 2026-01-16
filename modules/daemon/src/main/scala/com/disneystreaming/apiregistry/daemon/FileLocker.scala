package com.disneystreaming.apiregistry.daemon

import java.nio.channels.{FileChannel, FileLock}
import java.nio.file.StandardOpenOption

import cats.effect.kernel.{Async, Resource, Sync}
import cats.implicits._
import fs2.io.file.Path

trait FileChannelF[F[_]] {
  def acquire(lockFile: Path): F[FileChannel]
  def release(channel: FileChannel): F[Unit]
}

trait FileLocker[F[_]] extends FileChannelF[F] {

  def tryLock(channel: FileChannel): F[Option[FileLock]]

  def lock(channel: FileChannel): F[FileLock]

  def release(lock: FileLock): F[Unit]

}

object FileLocker {
  def apply[F[_]: Async]: FileLocker[F] = new FileLocker[F] {
    override def acquire(lockFile: Path): F[FileChannel] =
      Sync[F].blocking(
        FileChannel.open(
          lockFile.toNioPath,
          StandardOpenOption.CREATE,
          StandardOpenOption.WRITE
        )
      )

    override def tryLock(channel: FileChannel): F[Option[FileLock]] =
      Sync[F].blocking(Option(channel.tryLock()))

    override def lock(channel: FileChannel): F[FileLock] =
      Sync[F].blocking(channel.lock())

    override def release(lock: FileLock): F[Unit] = Sync[F]
      .blocking(lock.release())
      .handleErrorWith(ex =>
        Sync[F]
          .delay(println(s"failed to release lock  $lock with exception $ex"))
      )

    override def release(channel: FileChannel): F[Unit] = Sync[F]
      .blocking(channel.close())
      .handleErrorWith(ex =>
        Sync[F].delay(
          println(s"failed to close file channel $channel with exception $ex")
        )
      )
  }

  def acquireExclusiveLock[F[_]: Async](
      lockPath: Path
  ): Resource[F, FileLock] = {
    val locker = FileLocker[F]

    for {
      // Open file channel for locking
      channel <- Resource.make(locker.acquire(lockPath))(locker.release)

      // Try to acquire exclusive lock
      lock <- Resource.make(
        locker.tryLock(channel).flatMap { maybeLock =>
          Async[F].fromOption(
            maybeLock,
            new IllegalStateException(
              s"Cannot acquire lock on $lockPath. "
            )
          )

        }
      )(lock => locker.release(lock))

    } yield lock
  }
}
