# Daemon Module

The daemon module provides utilities for managing long-running daemon processes with proper lifecycle management.

## Overview

The daemon module handles:
- **Exclusive Locking**: Ensures only one instance of your daemon runs at a time
- **PID Management**: Automatically creates and manages PID files
- **Socket Coordination**: Provides a consistent socket path within the daemon directory
- **Metadata Storage**: Tracks daemon state (PID, socket, startup time)
- **Resource Safety**: Uses Cats Effect's `Resource` for guaranteed cleanup

## Core API

### `Daemon.acquire`

Acquire a daemon lock and create the necessary infrastructure:

```scala
import cats.effect.{IO, Resource}
import com.disneystreaming.apiregistry.daemon.Daemon

val daemonContext: Resource[IO, Daemon.DaemonContext] =
  Daemon.acquire[IO]("my-daemon")

daemonContext.use { ctx =>
  // ctx.lockDir: Path to lock directory
  // ctx.socketAddress: Unix socket address
  // ctx.pid: Process ID
  // ctx.lock: Exclusive file lock
  // ctx.log: Path to log file

  IO.println(s"Daemon running with PID ${ctx.pid.value}") *>
  IO.println(s"Socket: ${ctx.socketAddress.path}")
}
```

This creates a directory structure:
```
/tmp/my-daemon/
├── daemon.lock       # Exclusive lock file
├── daemon.pid        # Process ID
├── daemon.sock       # Unix socket
├── daemon.info       # Metadata (PID, socket, timestamp)
└── daemon.log        # Log file path
```

### `Daemon.checkRunning`

Check if a daemon is already running without acquiring the lock:

```scala
import com.disneystreaming.apiregistry.daemon.Daemon

Daemon.checkRunning[IO]("my-daemon").flatMap {
  case Some(ctx) =>
    IO.println(s"Daemon is running with PID ${ctx.pid.value}")
  case None =>
    IO.println("Daemon is not running")
}
```

## DaemonContext

The `DaemonContext` provides access to all daemon resources:

```scala
case class DaemonContext(
  lockDir: Path,                    // Lock directory path
  socketAddress: UnixSocketAddress, // Unix socket address
  pid: Pid,                         // Process ID
  lock: FileLock,                   // Exclusive file lock
  log: Path                         // Log file path
)
```

## Lock Directory Structure

The daemon creates and manages several files:

| File | Purpose |
|------|---------|
| `daemon.lock` | Exclusive file lock preventing multiple instances |
| `daemon.pid` | Contains the process ID |
| `daemon.sock` | Unix socket for IPC |
| `daemon.info` | Metadata file with PID, socket path, and timestamp |
| `daemon.log` | Suggested log file location |

## Usage Patterns

### Basic Daemon

```scala
import cats.effect.{IO, IOApp}
import com.disneystreaming.apiregistry.daemon.Daemon

object BasicDaemon extends IOApp.Simple {
  def run: IO[Unit] = {
    Daemon.acquire[IO]("basic-daemon").use { ctx =>
      // Your daemon logic here
      IO.println("Daemon running...") *>
        IO.never  // Run forever
    }
  }
}
```

### With Unix Socket Server

```scala
import cats.effect.{IO, IOApp}
import org.typelevel.log4cats.slf4j.Slf4jLogger
import com.disneystreaming.apiregistry.daemon.Daemon
import com.disneystreaming.apiregistry.socket.server.UnixSocketServer
import com.disneystreaming.apiregistry.socket.codecs.LineDelimitedCodec

object ServerDaemon extends IOApp.Simple {
  implicit val logger = Slf4jLogger.getLogger[IO]

  def handler(msg: String): IO[String] =
    IO.pure(s"Received: $msg")

  def run: IO[Unit] = {
    Daemon.acquire[IO]("server-daemon").use { ctx =>
      UnixSocketServer
        .builder[IO, String, String]
        .withSocket(ctx.socketAddress)
        .withCodec(LineDelimitedCodec.string[IO])
        .withHandler(handler)
        .build
        .run
    }
  }
}
```

### Graceful Shutdown

```scala
import cats.effect.{IO, IOApp}
import org.typelevel.log4cats.slf4j.Slf4jLogger
import com.disneystreaming.apiregistry.daemon.Daemon
import com.disneystreaming.apiregistry.socket.server.UnixSocketServer
import com.disneystreaming.apiregistry.socket.codecs.LineDelimitedCodec

object ShutdownDaemon extends IOApp.Simple {
  implicit val logger = Slf4jLogger.getLogger[IO]

  def handler(cmd: String, shutdown: IO[Unit]): IO[String] =
    cmd.trim.toLowerCase match {
      case "stop" => shutdown.as("Shutting down...")
      case other  => IO.pure(s"Echo: $other")
    }

  def run: IO[Unit] = {
    Daemon.acquire[IO]("shutdown-daemon").use { ctx =>
      UnixSocketServer
        .builder[IO, String, String]
        .withSocket(ctx.socketAddress)
        .withCodec(LineDelimitedCodec.string[IO])
        .withHandlerAndShutdown(handler)
        .build
        .run
    }
  }
}
```

## Low-Level Components

The daemon module also exposes lower-level utilities:

### FileLocker

Acquire exclusive file locks:

```scala
import cats.effect.{IO, Resource}
import fs2.io.file.Path
import com.disneystreaming.apiregistry.daemon.FileLocker

val lockPath = Path("/tmp/my.lock")
val lock: Resource[IO, java.nio.channels.FileLock] =
  FileLocker.acquireExclusiveLock[IO](lockPath)

lock.use { fileLock =>
  // Lock is held
  IO.println("Lock acquired")
}
// Lock is automatically released
```

### LockDirectory

Generate daemon directory structures:

```scala
import cats.effect.{IO, Resource}
import com.disneystreaming.apiregistry.daemon.LockDirectory

val lockDir: Resource[IO, LockDirectory.Structure] =
  LockDirectory[IO].generate("my-daemon")

lockDir.use { structure =>
  IO.println(s"Lock dir: ${structure.dir}") *>
  IO.println(s"Lock file: ${structure.lock}") *>
  IO.println(s"PID file: ${structure.pid}") *>
  IO.println(s"Socket: ${structure.sock}")
}
```

## Error Handling

If a daemon is already running, `Daemon.acquire` will fail with an error indicating the lock couldn't be acquired. This prevents accidental duplicate instances.

```scala
Daemon.acquire[IO]("my-daemon").use { ctx =>
  // ...
}.handleErrorWith {
  case e: java.nio.channels.OverlappingFileLockException =>
    IO.println("Daemon is already running!")
  case other =>
    IO.raiseError(other)
}
```

## Requirements

- Unix-based operating system (Linux, macOS, BSD)
- Java 11 or higher
  - **JDK 16+**: Native Unix socket support (recommended)
  - **JDK 11-15**: Requires JNR-UnixSocket (included as transitive dependency)
- FS2 3.x
- Cats Effect 3.x

The daemon module works seamlessly with Unix sockets. For detailed information about JNR dependencies and JDK version considerations, see the [Socket Module documentation](socket.md#unix-socket-implementation).
