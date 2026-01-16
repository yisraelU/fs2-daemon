package com.disneystreaming.apiregistry.daemon

import java.nio.channels.FileLock
import java.nio.file.{Path => JPath, StandardOpenOption}

import cats.effect.kernel.Resource
import cats.effect.{Async, Clock, Sync}
import cats.syntax.all._
import fs2.io.file.{Files, Flags, Path}
import fs2.io.net.unixsocket.UnixSocketAddress
import jdk.internal.util.StaticProperty

import com.disneystreaming.apiregistry.daemon.utils.Utils
import com.disneystreaming.apiregistry.daemon.utils.Utils.getCurrentPid

object Daemon {

  /**
   * Context provided to daemon after acquiring lock.
   *
   * @param lockDir Directory containing all daemon state
   * @param socketAddress Unix socket address (inside lock dir)
   * @param pid Process ID of this daemon
   * @param lock The exclusive file lock (held until resource closes)
   */
  case class DaemonContext(
      lockDir: Path,
      socketAddress: UnixSocketAddress,
      pid: Pid,
      lock: FileLock,
      log: Path
  )

  /**
   * Acquire lock directory with all daemon resources.
   *
   * This method:
   * 1. Creates lock directory under /tmp/{name}
   * 2. Acquires exclusive lock on daemon.lock
   * 3. Writes PID file
   * 4. Creates socket path inside directory
   * 5. Writes metadata file
   *
   * If lock cannot be acquired (daemon already running), fails immediately.
   *
   * @param name Name of the daemon (becomes directory name)
   * @return Resource providing DaemonContext
   */
  def acquire[F[_]: Async](name: String): Resource[F, DaemonContext] = {

    for {
      // 1. Create lock directory

      lockDirectory <- LockDirectory[F].generate(name)

      lockPath = lockDirectory.lock
      socketPath = lockDirectory.sock
      pidFile = lockDirectory.pid
      metaFile = lockDirectory.info
      log = lockDirectory.log

      // 2. Acquire exclusive file lock
      lock <- FileLocker.acquireExclusiveLock[F](lockPath)

      _ <- Resource.eval(Utils.deleteIfExists(socketPath, pidFile, metaFile))

      // 3. Get current process ID
      pid <- Resource.eval(getCurrentPid[F])

      // 4. Define socket path (inside lock dir)

      socketAddr = UnixSocketAddress(socketPath.toString)

      // 5. Write PID file
      _ <- Resource.eval(Utils.writeFile(pidFile)(pid.value.toString))

      // 6. Write metadata file
      _ <- Resource.eval(writeMetadata[F](metaFile, pid, socketAddr))

    } yield DaemonContext(lockDirectory.dir, socketAddr, pid, lock, log)
  }

  /**
   * Alternative: Just check if daemon is running without acquiring lock.
   */
  def checkRunning[F[_]: Async](name: String): F[Option[DaemonContext]] = {
    val tmpDir =
      Path.fromNioPath(JPath.of(System.getProperty("java.io.tmpdir")))
    val lockDir = tmpDir.resolve(name)

    Files[F]
      .exists(lockDir)
      .ifM(
        // Directory exists, try to read metadata
        readMetadata[F](lockDir).map(Some(_)),
        // Directory doesn't exist
        none[DaemonContext].pure[F]
      )
  }

  private def writeMetadata[F[_]: Async](
      dir: Path,
      pid: Pid,
      socket: UnixSocketAddress
  ): F[Unit] = {
    Clock[F].realTimeInstant.flatMap(timestamp =>
      Utils.writeFile(dir)(
        s"pid=${pid.value}",
        s"socket=${socket.path}",
        s"timestamp=${timestamp.toEpochMilli}",
        s"started=${java.time.Instant.ofEpochMilli(timestamp.toEpochMilli)}"
      )
    )
  }

  private def readMetadata[F[_]: Async](dir: Path): F[DaemonContext] = {
    val pidFile = dir.resolve("daemon.pid")
    val infoFile = dir.resolve("daemon.info")

    for {
      pidStr <- Files[F]
        .readUtf8Lines(pidFile)
        .compile
        .string
      pid = Pid(pidStr.trim.toLong)

      socketPath <- Files[F]
        .readUtf8Lines(infoFile)
        .filter(_.startsWith("socket="))
        .map(_.stripPrefix("socket="))
        .compile
        .string

      // Note: We don't have the actual lock in read-only mode
      socketAddr = UnixSocketAddress(socketPath.trim)
    } yield DaemonContext(dir, socketAddr, pid, null, null)
  }
}
