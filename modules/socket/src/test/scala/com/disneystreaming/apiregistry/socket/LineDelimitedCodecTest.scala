package com.disneystreaming.apiregistry.socket

import cats.effect.IO
import fs2.Stream
import munit.CatsEffectSuite

import com.disneystreaming.apiregistry.socket.codecs.LineDelimitedCodec

class LineDelimitedCodecTest extends CatsEffectSuite {

  test("string codec - decode single line") {
    val codec = LineDelimitedCodec.string[IO]
    val input = Stream.emits("hello world\n".getBytes)

    codec.decode(input).assertEquals("hello world")
  }

  test("string codec - decode line without trailing newline") {
    val codec = LineDelimitedCodec.string[IO]
    val input = Stream.emits("hello world".getBytes)

    // This should still work - lines pipe handles lines without trailing newline at EOF
    codec.decode(input).assertEquals("hello world")
  }

  test("string codec - decode only first line from multiple lines") {
    val codec = LineDelimitedCodec.string[IO]
    val input = Stream.emits("first line\nsecond line\nthird line\n".getBytes)

    // Should only decode the first line
    codec.decode(input).assertEquals("first line")
  }

  test("string codec - encode adds newline") {
    val codec = LineDelimitedCodec.string[IO]

    codec
      .encode("hello world")
      .through(fs2.text.utf8.decode)
      .compile
      .string
      .assertEquals("hello world\n")
  }

  test("string codec - encode preserves existing newline") {
    val codec = LineDelimitedCodec.string[IO]

    codec
      .encode("hello world\n")
      .through(fs2.text.utf8.decode)
      .compile
      .string
      .assertEquals("hello world\n")
  }

  test("string codec - roundtrip") {
    val codec = LineDelimitedCodec.string[IO]
    val original = "test message"

    val roundtrip = for {
      encoded <- codec.encode(original).compile.to(Array)
      decoded <- codec.decode(Stream.emits(encoded))
    } yield decoded

    roundtrip.assertEquals(original)
  }

  test("string codec - decode empty stream fails") {
    val codec = LineDelimitedCodec.string[IO]
    val input = Stream.empty

    codec.decode(input).intercept[RuntimeException]
  }

  test("string codec - decode UTF-8 characters") {
    val codec = LineDelimitedCodec.string[IO]
    val message = "Hello 世界 🌍\n"
    val input = Stream.emits(message.getBytes("UTF-8"))

    codec.decode(input).assertEquals("Hello 世界 🌍")
  }

  test("custom codec - deserialize and serialize") {
    case class TestReq(value: String)
    case class TestResp(result: String)

    val codec = LineDelimitedCodec.custom[IO, TestReq, TestResp](
      line => IO.pure(TestReq(line)),
      resp => IO.pure(s"result:${resp.result}")
    )

    val input = Stream.emits("test input\n".getBytes)

    for {
      decoded <- codec.decode(input)
      _ <- IO(assertEquals(decoded, TestReq("test input")))
      encoded <- codec
        .encode(TestResp("success"))
        .through(fs2.text.utf8.decode)
        .compile
        .string
      _ <- IO(assertEquals(encoded, "result:success\n"))
    } yield ()
  }

  test("custom codec - deserialize error is wrapped") {
    val codec = LineDelimitedCodec.custom[IO, Int, String](
      line => IO.raiseError(new NumberFormatException("bad number")),
      resp => IO.pure(resp)
    )

    val input = Stream.emits("not a number\n".getBytes)

    codec
      .decode(input)
      .intercept[RuntimeException]
      .map(ex =>
        assert(ex.getMessage.contains("Failed to deserialize message"))
      )
  }

  test("custom codec - serialize and deserialize JSON-like data") {
    // Simulate JSON encoding/decoding without circe dependency
    case class Command(action: String, value: Int)
    case class Result(status: String)

    val codec = LineDelimitedCodec.custom[IO, Command, Result](
      line => {
        // Simple parser: "action:value"
        val parts = line.split(":")
        if (parts.length == 2) {
          IO.pure(Command(parts(0), parts(1).toInt))
        } else {
          IO.raiseError(new RuntimeException("Invalid format"))
        }
      },
      result => IO.pure(s"status:${result.status}")
    )

    val input = Stream.emits("register:42\n".getBytes)

    for {
      decoded <- codec.decode(input)
      _ <- IO(assertEquals(decoded, Command("register", 42)))
      encoded <- codec
        .encode(Result("ok"))
        .through(fs2.text.utf8.decode)
        .compile
        .string
      _ <- IO(assertEquals(encoded, "status:ok\n"))
    } yield ()
  }

  test("string codec - handles multiline input stream correctly") {
    val codec = LineDelimitedCodec.string[IO]
    // Simulate chunked reading where bytes arrive in multiple chunks
    val input = Stream(
      "hel".getBytes,
      "lo wo".getBytes,
      "rld\nsecond".getBytes,
      " line\n".getBytes
    ).flatMap(bytes => Stream.emits(bytes))

    // Should only get first line
    codec.decode(input).assertEquals("hello world")
  }
}
