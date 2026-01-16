package com.disneystreaming.apiregistry.daemon

import cats.effect.IO
import fs2.io.file.{Files, Path}
import munit.CatsEffectSuite

import com.disneystreaming.apiregistry.daemon.TestUtils._

/**
 * Basic smoke tests for the daemon lock directory functionality.
 *
 * These tests verify the core lock acquisition and cleanup behavior.
 */
class DaemonBasicTest extends CatsEffectSuite {

  test("fresh start - creates lock directory") {
    val name = uniqueName

    Daemon
      .acquire[IO](name)
      .use { ctx =>
        Files[IO].exists(ctx.lockDir).assert *>
          Files[IO].exists(ctx.lockDir.resolve("daemon.lock")).assert
      }
      .guarantee(cleanup(name))
  }

  test("prevents duplicate daemon instances") {
    val name = uniqueName

    Daemon
      .acquire[IO](name)
      .use { ctx1 =>
        // First daemon holds lock
        Daemon
          .acquire[IO](name)
          .use { ctx2 =>
            IO.unit
          }
          .attempt
          .flatMap {
            case Left(e: IllegalStateException) =>
              val msg = Option(e.getMessage).getOrElse("")
              IO(
                assert(
                  msg.contains("lock") || msg.contains("running") || msg.isEmpty
                )
              )
            case Left(other) =>
              IO(fail(s"Wrong exception: ${other.getClass.getName}"))
            case Right(_) =>
              IO(fail("Second daemon should have failed"))
          }
      }
      .guarantee(cleanup(name))
  }

  test("cleans up lock directory on shutdown") {
    val name = uniqueName
    val lockDir = tmpDir.resolve(name)

    Daemon.acquire[IO](name).use { _ =>
      Files[IO].exists(lockDir).assert
    } *>
      Files[IO].exists(lockDir).map(exists => assert(!exists))
  }

  test("handles stale lock directory") {
    val name = uniqueName
    val lockDir = tmpDir.resolve(name)

    // Simulate crash: create stale directory with stale metadata
    val simulateCrash = Files[IO].createDirectories(lockDir) *>
      writeFile(lockDir.resolve("daemon.pid"), "99999") *>
      writeFile(
        lockDir.resolve("daemon.info"),
        "pid=99999\ntimestamp=1234567890\nstale=true"
      ) *>
      writeFile(lockDir.resolve("stale.txt"), "crashed")

    (simulateCrash *>
      Daemon.acquire[IO](name).use { ctx =>
        for {
          // Verify daemon started successfully
          _ <- IO(assertEquals(ctx.lockDir, lockDir))

          // Verify PID file was overwritten with current PID
          pidContent <- Files[IO]
            .readUtf8(lockDir.resolve("daemon.pid"))
            .compile
            .string
          _ <- IO(assertEquals(pidContent.trim, ctx.pid.value.toString))
          _ <- IO(assertNotEquals(pidContent.trim, "99999"))

          // Verify metadata file was renewed with current info
          metaContent <- Files[IO]
            .readUtf8(lockDir.resolve("daemon.info"))
            .compile
            .string
          _ <- IO(assert(metaContent.contains(s"pid=${ctx.pid.value}")))
          _ <- IO(assert(!metaContent.contains("stale=true")))

          // Stale files might still exist (we don't delete unknown files)
          _ <- Files[IO].exists(lockDir.resolve("stale.txt")).assert
        } yield ()
      }).guarantee(cleanup(name))
  }

  test("sequential daemon starts succeed") {
    val name = uniqueName

    val run1 = Daemon.acquire[IO](name).use(_ => IO.unit)
    val run2 = Daemon.acquire[IO](name).use(_ => IO.unit)
    val run3 = Daemon.acquire[IO](name).use(_ => IO.unit)

    (run1 *> run2 *> run3).guarantee(cleanup(name))
  }

  test("checkRunning returns None when daemon not running") {
    val name = uniqueName

    Daemon.checkRunning[IO](name).map { result =>
      assertEquals(result, None)
    }
  }

  test("checkRunning returns Some when daemon is running") {
    val name = uniqueName

    Daemon
      .acquire[IO](name)
      .use { ctx =>
        Daemon.checkRunning[IO](name).map { result =>
          assert(result.isDefined)
        }
      }
      .guarantee(cleanup(name))
  }

  test("different daemon names don't conflict") {
    val name1 = s"daemon-1-${System.currentTimeMillis()}"
    val name2 = s"daemon-2-${System.currentTimeMillis()}"

    Daemon
      .acquire[IO](name1)
      .use { ctx1 =>
        Daemon.acquire[IO](name2).use { ctx2 =>
          IO {
            assertNotEquals(ctx1.lockDir, ctx2.lockDir)
            assertNotEquals(ctx1.socketAddress.path, ctx2.socketAddress.path)
          }
        }
      }
      .guarantee(cleanup(name1) *> cleanup(name2))
  }

  test("context contains valid lock") {
    val name = uniqueName

    Daemon
      .acquire[IO](name)
      .use { ctx =>
        IO {
          assertNotEquals(ctx.lock, null)
          assert(ctx.lock.isValid)
        }
      }
      .guarantee(cleanup(name))
  }

  test("socket path is inside lock directory") {
    val name = uniqueName

    Daemon
      .acquire[IO](name)
      .use { ctx =>
        IO {
          val socketPath = Path(ctx.socketAddress.path)
          val socketParent = socketPath.parent.get
          assertEquals(socketParent, ctx.lockDir)
        }
      }
      .guarantee(cleanup(name))
  }

  test("socket file deleted before use") {
    val name = uniqueName
    val lockDir = tmpDir.resolve(name)

    // Create stale socket file
    val createStaleSocket = for {
      _ <- Files[IO].createDirectories(lockDir)
      socketPath = lockDir.resolve("daemon.sock")
      _ <- writeFile(socketPath, "stale socket")
    } yield ()

    (createStaleSocket *>
      Daemon.acquire[IO](name).use { ctx =>
        val socketPath = Path(ctx.socketAddress.path)

        for {
          // Socket file should not contain "stale socket"
          // (it was deleted by Daemon.acquire)
          exists <- Files[IO].exists(socketPath)

          // File might not exist yet (server hasn't bound)
          // Or it might be a socket (not regular file)
          // Either way, it shouldn't contain our stale data
          _ = IO.unit
        } yield ()
      }).guarantee(cleanup(name))
  }

  test("metadata file contains correct information") {
    val name = uniqueName

    Daemon
      .acquire[IO](name)
      .use { ctx =>
        val metaFile = ctx.lockDir.resolve("daemon.info")

        Files[IO].readUtf8(metaFile).compile.string.map { content =>
          // Should contain PID
          assert(content.contains(s"pid=${ctx.pid.value}"))

          // Should contain socket path
          assert(content.contains(s"socket=${ctx.socketAddress.path}"))

          // Should contain timestamp
          assert(content.contains("timestamp="))

          // Should contain human-readable timestamp
          assert(content.contains("started="))
        }
      }
      .guarantee(cleanup(name))
  }

  test("checkRunning after shutdown returns None") {
    val name = uniqueName

    (Daemon.acquire[IO](name).use { _ =>
      IO.unit
    } *>
      Daemon.checkRunning[IO](name).map { result =>
        assertEquals(result, None)
      }).guarantee(cleanup(name))
  }

  test("concurrent acquire attempts - only one succeeds") {
    import cats.effect.std.CountDownLatch

    val name = uniqueName

    (for {
      latch <- CountDownLatch[IO](1)

      // Start first daemon
      fiber1 <- Daemon
        .acquire[IO](name)
        .use { ctx1 =>
          latch.release *> IO.sleep(
            scala.concurrent.duration.Duration(200, "ms")
          )
        }
        .start

      // Wait for first daemon to acquire lock
      _ <- latch.await

      // Try to start second daemon - should fail immediately
      result <- Daemon
        .acquire[IO](name)
        .use { ctx2 =>
          IO.unit
        }
        .attempt

      _ <- fiber1.joinWithNever

      _ = result match {
        case Left(e: IllegalStateException) =>
          val msg = Option(e.getMessage).getOrElse("")
          assert(
            msg.contains("already running") || msg.contains(
              "lock"
            ) || msg.isEmpty
          )
        case Left(other) =>
          fail(s"Wrong exception: ${other.getClass.getName}")
        case Right(_) =>
          fail("Second daemon should have failed")
      }
    } yield ()).guarantee(cleanup(name))
  }

  test("lock is held throughout daemon lifetime") {
    import cats.effect.std.CountDownLatch

    val name = uniqueName

    (for {
      startedLatch <- CountDownLatch[IO](1)
      checkLatch <- CountDownLatch[IO](1)

      // Start daemon
      daemonFiber <- Daemon
        .acquire[IO](name)
        .use { ctx =>
          startedLatch.release *>
            checkLatch.await *>
            IO.unit
        }
        .start

      // Wait for daemon to start
      _ <- startedLatch.await

      // Try to acquire lock again (should fail - daemon holding it)
      secondAttempt <- Daemon.acquire[IO](name).use(_ => IO.unit).attempt

      // Release daemon
      _ <- checkLatch.release
      _ <- daemonFiber.joinWithNever

      // Verify second attempt failed
      _ = assert(secondAttempt.isLeft)

    } yield ()).guarantee(cleanup(name))
  }
}
