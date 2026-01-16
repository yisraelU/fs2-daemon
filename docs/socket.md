# Socket Module

The socket module provides a Unix domain socket server implementation with support for multiple serialization formats and streaming responses.

## Overview

Unix domain sockets provide:
- High-performance local IPC (faster than TCP)
- No network overhead
- File system permissions for access control
- Natural fit for daemon processes

The socket module offers:
- Type-safe request/response handling
- Multiple codec options for serialization
- Streaming response support
- Fluent builder API
- Graceful shutdown control

## Quick Example

```scala
import cats.effect.{IO, IOApp}
import fs2.io.net.unixsocket.UnixSocketAddress
import org.typelevel.log4cats.slf4j.Slf4jLogger
import com.disneystreaming.apiregistry.socket.server.UnixSocketServer
import com.disneystreaming.apiregistry.socket.codecs.LineDelimitedCodec

object SimpleServer extends IOApp.Simple {
  implicit val logger = Slf4jLogger.getLogger[IO]

  def handler(msg: String): IO[String] =
    IO.pure(s"Echo: $msg")

  def run: IO[Unit] = {
    UnixSocketServer
      .builder[IO, String, String]
      .withSocket(UnixSocketAddress("/tmp/server.sock"))
      .withCodec(LineDelimitedCodec.string[IO])
      .withHandler(handler)
      .build
      .run
  }
}
```

## Builder API

The fluent builder API guides you through server construction:

```scala
UnixSocketServer
  .builder[IO, Request, Response]  // 1. Specify types
  .withSocket(socketAddress)       // 2. Set socket path
  .withCodec(codec)                // 3. Choose codec
  .withHandler(handler)            // 4. Set handler
  .build                           // 5. Build server
  .run                             // 6. Start server
```

### Handler Options

Choose the handler type that fits your needs:

#### Single Response Handler

For handlers that return a single response:

```scala
def handler(request: String): IO[String] =
  IO.pure(s"Response for: $request")

.withHandler(handler)
```

#### Streaming Handler

For handlers that stream multiple responses:

```scala
def handler(request: String): Stream[IO, String] =
  Stream.range(1, 11).map(i => s"Line $i")

.withStreamingHandler(handler)
```

#### With Shutdown Control

For handlers that can trigger graceful shutdown:

```scala
def handler(request: String, shutdown: IO[Unit]): IO[String] =
  request match {
    case "stop" => shutdown.as("Goodbye!")
    case other  => IO.pure(s"Echo: $other")
  }

.withHandlerAndShutdown(handler)
```

#### Streaming with Shutdown

Combine streaming responses with shutdown control:

```scala
def handler(request: String, shutdown: IO[Unit]): Stream[IO, String] =
  request match {
    case "stop" =>
      Stream.eval(shutdown) >> Stream.emit("Goodbye!")
    case "stream" =>
      Stream.range(1, 11).map(i => s"Item $i")
    case other =>
      Stream.emit(s"Echo: $other")
  }

.withStreamingHandlerAndShutdown(handler)
```

## Codecs

Codecs handle serialization and deserialization of requests and responses.

### LineDelimitedCodec

Text-based codec using newline delimiters:

```scala
import com.disneystreaming.apiregistry.socket.codecs.LineDelimitedCodec

// String codec
val stringCodec = LineDelimitedCodec.string[IO]

// Custom codec with transformation
val customCodec = LineDelimitedCodec.custom[IO, Command, Result](
  decode = line => parseCommand(line),
  encode = result => formatResult(result)
)
```

**Use case**: Simple text protocols, CLI tools, debugging

### LengthPrefixedCodec

Binary codec with 4-byte length prefix:

```scala
import com.disneystreaming.apiregistry.socket.codecs.LengthPrefixedCodec

val codec = LengthPrefixedCodec[IO]
```

**Use case**: Binary protocols, efficient large payloads

### LengthPrefixedJsonCodec

JSON with length prefix:

```scala
import com.disneystreaming.apiregistry.socket.codecs.LengthPrefixedJsonCodec
import io.circe.{Decoder, Encoder}

implicit val reqDecoder: Decoder[Request] = ???
implicit val respEncoder: Encoder[Response] = ???

val codec = LengthPrefixedJsonCodec[IO, Request, Response]
```

**Use case**: Structured data, API-like interfaces

### RawBytesCodec

Direct byte streaming:

```scala
import com.disneystreaming.apiregistry.socket.codecs.RawBytesCodec

val codec = RawBytesCodec[IO]
```

**Use case**: Binary protocols, file transfers

### ChunkedCodec

HTTP-style chunked transfer:

```scala
import com.disneystreaming.apiregistry.socket.codecs.ChunkedCodec

val codec = ChunkedCodec.string[IO]
```

**Use case**: Streaming large responses, progressive data

### Custom Codecs

Implement the `Codec` trait for custom formats:

```scala
import com.disneystreaming.apiregistry.socket.codecs.Codec
import fs2.Stream

class MyCodec[F[_]: Concurrent] extends Codec[F, Request, Response] {
  def decode(bytes: Stream[F, Byte]): F[Request] =
    // Your decoding logic
    ???

  def encode(response: Response): Stream[F, Byte] =
    // Your encoding logic
    ???
}
```

## Examples

### JSON API Server

```scala
import io.circe._
import io.circe.generic.semiauto._
import io.circe.parser.decode
import io.circe.syntax._
import com.disneystreaming.apiregistry.socket.codecs.LineDelimitedCodec

case class Command(action: String, args: List[String])
case class Result(status: String, message: String)

implicit val commandDecoder: Decoder[Command] = deriveDecoder
implicit val resultEncoder: Encoder[Result] = deriveEncoder

val codec = LineDelimitedCodec.custom[IO, Command, Result](
  line => IO.fromEither(decode[Command](line).leftMap(new RuntimeException(_))),
  result => IO.pure(result.asJson.noSpaces)
)

def handler(cmd: Command): IO[Result] =
  IO.pure(Result("success", s"Executed ${cmd.action}"))

UnixSocketServer
  .builder[IO, Command, Result]
  .withSocket(UnixSocketAddress("/tmp/api.sock"))
  .withCodec(codec)
  .withHandler(handler)
  .build
  .run
```

### Streaming Output

Stream output as it's produced (useful for long-running commands):

```scala
import fs2.Stream

def handler(command: String): Stream[IO, String] = {
  command.trim.toLowerCase match {
    case "logs" =>
      // Stream log lines as they're generated
      Stream.repeatEval(IO.realTimeInstant.map(_.toString))
        .metered(1.second)
        .take(10)

    case "build" =>
      // Stream build output
      Stream.eval(IO.println("Starting build...")) >>
        Stream.emit("Compiling...") ++
        Stream.emit("Running tests...") ++
        Stream.emit("Build complete!")

    case _ =>
      Stream.emit("Unknown command")
  }
}

UnixSocketServer
  .builder[IO, String, String]
  .withSocket(UnixSocketAddress("/tmp/stream.sock"))
  .withCodec(LineDelimitedCodec.string[IO])
  .withStreamingHandler(handler)
  .build
  .run
```

### Multi-Command Handler

```scala
def handler(cmd: String, shutdown: IO[Unit]): IO[String] = {
  cmd.trim.toLowerCase match {
    case "ping" =>
      IO.pure("pong")

    case "status" =>
      IO.realTime.map(t => s"Uptime: ${t.toSeconds}s")

    case "stop" | "kill" | "shutdown" =>
      shutdown.as("Shutting down...")

    case "help" =>
      IO.pure("""
        |Available commands:
        |  ping     - Check if server is alive
        |  status   - Show server status
        |  stop     - Shutdown server
        |  help     - Show this message
        |""".stripMargin)

    case unknown =>
      IO.pure(s"Unknown command: $unknown (try 'help')")
  }
}
```

## Testing with netcat

Test your Unix socket server using `nc` (netcat):

```bash
# Single message
$ echo "hello" | nc -U /tmp/server.sock
Echo: hello

# Interactive session
$ nc -U /tmp/server.sock
ping
pong
status
Uptime: 42s
stop
Shutting down...
```

## Socket Cleanup

Clean up stale socket files before starting:

```scala
import com.disneystreaming.apiregistry.socket.server.UnixSocketServer

val socketAddr = UnixSocketAddress("/tmp/server.sock")

UnixSocketServer.cleanupSocketFile[IO](socketAddr) *>
  UnixSocketServer
    .builder[IO, String, String]
    .withSocket(socketAddr)
    .withCodec(LineDelimitedCodec.string[IO])
    .withHandler(handler)
    .build
    .run
```

## Advanced Patterns

### Request Validation

```scala
def handler(cmd: String): IO[String] = {
  if (cmd.trim.isEmpty)
    IO.pure("Error: Empty command")
  else if (cmd.length > 1024)
    IO.pure("Error: Command too long")
  else
    processCommand(cmd)
}
```

### Concurrent Request Handling

The server handles each connection concurrently - multiple clients can connect simultaneously.

### Error Handling

Errors in handlers are logged but don't crash the server:

```scala
def handler(cmd: String): IO[String] = {
  parseCommand(cmd)
    .flatMap(processCommand)
    .handleErrorWith { error =>
      IO.pure(s"Error: ${error.getMessage}")
    }
}
```

## Requirements

- Unix-based operating system (Linux, macOS, BSD)
- Java 11 or higher
  - **JDK 16+**: Native Unix socket support (recommended)
  - **JDK 11-15**: Requires JNR-UnixSocket (included as transitive dependency)
- FS2 3.x with fs2-io
- Cats Effect 3.x

### Unix Socket Implementation

This library uses FS2's `fs2.io.net.unixsocket` API and includes JNR-UnixSocket as a dependency for broad JDK compatibility:

- **JDK 16+**: Uses native implementation via `java.nio.channels.UnixDomainSocketChannel`
  - Zero overhead, direct JDK support
  - JNR dependency is present but unused at runtime
  - Can be excluded if you only target JDK 16+

- **JDK 11-15**: Uses JNR-UnixSocket for Unix domain socket support
  - JNR dependency is required and active
  - Works seamlessly alongside FS2's abstraction

### Excluding JNR (JDK 16+ Only)

If your application only runs on JDK 16 or higher, you can exclude the JNR dependency to reduce your dependency footprint:

```scala
libraryDependencies ++= Seq(
  ("io.github.yisraelu" %% "unix-socket" % "@VERSION@")
    .exclude("com.github.jnr", "jnr-unixsocket")
)
```

This is entirely optional - keeping JNR in your dependency tree doesn't impact performance when using JDK 16+, as the native implementation takes precedence.
