# fs2-daemon

Fs2 utilities for writing daemon processes that communicate over Unix domain sockets.

This library provides composable, type-safe building blocks for creating long-running daemon processes with robust IPC (Inter-Process Communication) over Unix sockets. Built on top of FS2 and Cats Effect, it offers both daemon process management and a flexible Unix socket server implementation with multiple codec options.

## Features

### Daemon Module

- **Process Management**: Acquire exclusive locks to ensure single daemon instances
- **PID Management**: Automatic PID file creation and cleanup
- **Resource Safety**: Leverages Cats Effect's `Resource` for guaranteed cleanup
- **Metadata Tracking**: Stores daemon state (PID, socket path, startup time)
- **Lock Directory**: Isolated daemon state in `/tmp/{daemon-name}/`

### Socket Module

- **Unix Domain Sockets**: High-performance IPC without network overhead
- **Multiple Codecs**:
  - Line-delimited text
  - Length-prefixed binary
  - JSON support (with Circe)
  - Raw bytes
  - Chunked transfer
- **Streaming Responses**: Support for both single-response and streaming handlers
- **Graceful Shutdown**: Built-in shutdown control for clean daemon termination
- **Type-Safe**: Generic request/response types with compile-time safety
- **Fluent Builder API**: Intuitive, step-by-step server construction

## Installation

Add the following to your `build.sbt`:

```scala
libraryDependencies ++= Seq(
  "io.github.yisraelu" %% "daemon" % "@VERSION@",
  "io.github.yisraelu" %% "unix-socket" % "@VERSION@"
)
```

This library is cross-published for Scala 2.13 and Scala 3.

### Requirements

- **JDK 11+** (JDK 16+ recommended for native Unix socket support)
- **Unix-based OS** (Linux, macOS, BSD)
- FS2 3.x and Cats Effect 3.x

**Note**: This library includes JNR-UnixSocket as a dependency for JDK 11-15 compatibility. On JDK 16+, native Unix sockets are used instead. You can optionally exclude JNR if you only target JDK 16+.

## Quick Start

Check out the [Getting Started guide](guide/getting-started.md) for your first daemon, or explore the [Examples](examples.md) to see common patterns.

## Architecture

The library is designed around three core principles:

1. **Resource Safety**: All resources (locks, sockets, files) are managed via Cats Effect's `Resource`, ensuring cleanup even on errors
2. **Type Safety**: Generic request/response types with codec abstraction provide compile-time guarantees
3. **Composability**: Small, focused abstractions that compose naturally using FS2 streams

The daemon module handles the lifecycle concerns (single instance, PID management, cleanup), while the socket module handles communication (encoding, decoding, request/response flow). They work together seamlessly but can be used independently.

## Modules

- [**daemon**](daemon.md) - Process management, locking, PID files
- [**socket**](socket.md) - Unix socket server with codec support

## Resources

- [FAQ](faq.md) - Common questions about JDK versions, JNR, and deployment

## Contributing

Contributions are welcome! Please feel free to submit issues or pull requests on [GitHub](https://github.com/yisraelu/fs2-daemon).
