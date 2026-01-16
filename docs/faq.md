# Frequently Asked Questions

## Does this library include JNR-UnixSocket?

**Yes.** This library includes JNR-UnixSocket as a transitive dependency to ensure compatibility across all supported JDK versions (11+).

- **JDK 16+**: Native Unix sockets are used at runtime; JNR is present but unused
- **JDK 11-15**: JNR-UnixSocket is required and actively used for Unix socket support

You don't need to add JNR-UnixSocket manually - it's already included as a dependency.

## What JDK version should I use?

We recommend **JDK 16 or higher** for the best performance and simplest setup:

- **JDK 16+**: Native Unix socket support with zero overhead
- **JDK 11-15**: Fully supported with automatic fallback

Both work seamlessly - the API is identical regardless of JDK version.

## Why JDK 16+ is recommended

JDK 16 introduced native Unix domain socket support via `java.nio.channels`:
- No third-party dependencies needed
- Better performance (direct JDK implementation)
- Simpler dependency tree
- Long-term support path

However, JDK 11-15 work perfectly fine with FS2's compatibility layer.

## Can I exclude JNR if I'm only using JDK 16+?

**Yes.** If you're confident your application will only run on JDK 16 or higher, you can exclude JNR to reduce your dependency footprint:

```scala
libraryDependencies ++= Seq(
  ("io.github.yisraelu" %% "unix-socket" % "@VERSION@")
    .exclude("com.github.jnr", "jnr-unixsocket")
)
```

However, this is purely optional:
- Keeping JNR doesn't affect performance on JDK 16+ (native implementation is always preferred)
- Excluding it makes your build slightly smaller but less portable to older JDKs

## How does Unix socket support work on different JDKs?

This library uses FS2's Unix socket API, which adapts based on your JDK version:

```scala
// This code works identically on all JDK versions
import fs2.io.net.unixsocket.{UnixSockets, UnixSocketAddress}

UnixSockets[IO]
  .server(UnixSocketAddress("/tmp/socket.sock"))
  .evalMap { socket =>
    // Handle connection
  }
```

Under the hood:
- **JDK 16+**: FS2 uses `java.nio.channels.UnixDomainSocketChannel` (native)
- **JDK 11-15**: FS2 uses JNR-UnixSocket (this library includes it as a dependency)

You write the same code regardless of JDK version.

## Can I use this library on Windows?

No. Unix domain sockets are a Unix/Linux/macOS feature and are not available on Windows.

While Windows 10 1803+ technically supports Unix sockets via WSL2, this library requires a native Unix-based operating system:
- Linux (any distribution)
- macOS
- BSD (FreeBSD, OpenBSD, etc.)

## What if I see JNR-related errors on JDK 11-15?

If you're using JDK 11-15 and see JNR-related errors, ensure you're using:
- FS2 version 3.0 or higher
- A properly configured build tool (sbt, Mill, Maven, Gradle)

FS2 should automatically include necessary JNR dependencies when running on JDK 11-15.

If issues persist:
1. Check your FS2 version: `libraryDependencies` should include `"co.fs2" %% "fs2-io" % "3.x.x"`
2. Verify your JDK version: `java -version`
3. Consider upgrading to JDK 16+ for native support

## How do I check which Unix socket implementation is being used?

You can check your JDK version to determine which implementation is active:

```bash
$ java -version
openjdk version "17.0.1" 2021-10-19
# JDK 17 = native Unix socket support
```

Or in code (for debugging):

```scala
val javaVersion = System.getProperty("java.version")
val useNative = javaVersion.split("\\.")(0).toInt >= 16

IO.println(s"Using ${if (useNative) "native" else "JNR"} Unix sockets")
```

However, this is purely informational - your code works identically on both.

## Are there performance differences between JDK versions?

JDK 16+ native Unix sockets offer:
- Slightly better throughput
- Lower latency
- Reduced memory footprint
- Simpler stack traces

For most applications, the difference is negligible. Both implementations are production-ready and performant.

## Can I explicitly add JNR if I want to?

You can, but it's unnecessary and may cause version conflicts. FS2 already includes the correct JNR version internally when needed.

If you have a specific reason to include JNR (e.g., other parts of your application need it), ensure version compatibility:

```scala
libraryDependencies += "com.github.jnr" % "jnr-unixsocket" % "0.38.x"
```

But for this library, **you don't need to do this**.

## Does this affect deployment?

No. Your deployment remains simple:
- Package your application as usual (JAR, Docker, native image)
- Ensure JDK 11+ is available in the runtime environment
- No special configuration needed

Example Docker setup:

```dockerfile
# Recommended: Use JDK 17+ for native support
FROM eclipse-temurin:17-jre

COPY target/myapp.jar /app.jar
CMD ["java", "-jar", "/app.jar"]
```

Or with JDK 11:

```dockerfile
# Also works: JDK 11 with automatic JNR fallback
FROM eclipse-temurin:11-jre

COPY target/myapp.jar /app.jar
CMD ["java", "-jar", "/app.jar"]
```

Both work identically.

## How is this different from other Unix socket libraries?

Other JVM Unix socket libraries:
- **jnr-unixsocket**: Low-level JNR bindings (requires manual setup)
- **junixsocket**: Alternative native bindings (extra dependencies)
- **netty-transport-native-unix-common**: Netty-specific (tied to Netty ecosystem)

This library (via FS2):
- Works across all JDK versions automatically
- No manual configuration
- Pure functional, type-safe API
- Seamless integration with Cats Effect ecosystem
- Native support on modern JDKs with zero-dependency fallback

## What about native image compilation (GraalVM)?

FS2's Unix socket support works with GraalVM native image:

- **JDK 16+ native sockets**: Work seamlessly in native image
- **JNR fallback (JDK 11-15)**: May require additional reflection configuration

For best native image compatibility, use JDK 17+ as the base for your GraalVM build.

## Where can I learn more?

- [FS2 Documentation](https://fs2.io/)
- [JEP 380: Unix Domain Socket Channels](https://openjdk.org/jeps/380) (JDK 16 feature)
- [Getting Started Guide](guide/getting-started.md)
- [Socket Module Documentation](socket.md)
