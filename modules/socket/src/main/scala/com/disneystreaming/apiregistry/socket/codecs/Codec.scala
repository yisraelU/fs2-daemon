package com.disneystreaming.apiregistry.socket.codecs

import cats.effect.Concurrent
import fs2.Stream
trait Codec[F[_], Req, Resp] {

  // needs to know when to stop reading
  def decode(bytes: Stream[F, Byte]): F[Req]

  def encode(response: Resp): Stream[F, Byte]

}
object Codec {
  implicit def bytesCodec[F[_]: Concurrent]: Codec[F, List[Byte], List[Byte]] =
    new Codec[F, List[Byte], List[Byte]] {

      override def decode(bytes: Stream[F, Byte]): F[List[Byte]] =
        bytes.compile.toList

      override def encode(response: List[Byte]): Stream[F, Byte] =
        Stream.emits(response)
    }
}
