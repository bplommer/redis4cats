/*
 * Copyright 2018-2020 ProfunKtor
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.profunktor.redis4cats.connection

import java.util.concurrent.TimeUnit

import cats.effect._
import cats.syntax.all._
import dev.profunktor.redis4cats.config.Redis4CatsConfig
import dev.profunktor.redis4cats.effect.{ JRFuture, Log }
import dev.profunktor.redis4cats.effect.JRFuture.mkEc
import io.lettuce.core.{ ClientOptions, RedisClient => JRedisClient, RedisURI => JRedisURI }
import scala.concurrent.ExecutionContext

sealed abstract case class RedisClient private (underlying: JRedisClient, uri: RedisURI)

object RedisClient {

  private[redis4cats] def acquireAndRelease[F[_]: Async: Log](
      uri: => RedisURI,
      opts: ClientOptions,
      config: Redis4CatsConfig,
      ec: ExecutionContext
  ): (F[RedisClient], RedisClient => F[Unit]) = {
    val acquire: F[RedisClient] = F.delay {
      val jClient: JRedisClient = JRedisClient.create(uri.underlying)
      jClient.setOptions(opts)
      new RedisClient(jClient, uri) {}
    }

    val release: RedisClient => F[Unit] = client =>
      F.info(s"Releasing Redis connection: $uri") *>
          JRFuture
            .fromCompletableFuture(
              F.delay(
                client.underlying.shutdownAsync(
                  config.shutdown.quietPeriod.toNanos,
                  config.shutdown.timeout.toNanos,
                  TimeUnit.NANOSECONDS
                )
              )
            )(ec)
            .void

    (acquire, release)
  }

  private[redis4cats] def acquireAndReleaseWithoutUri[F[_]: Async: Log](
      opts: ClientOptions,
      config: Redis4CatsConfig,
      ec: ExecutionContext
  ): F[(F[RedisClient], RedisClient => F[Unit])] =
    F.delay(RedisURI.fromUnderlying(new JRedisURI()))
      .map(uri => acquireAndRelease(uri, opts, config, ec))

  class RedisClientPartiallyApplied[F[_]: Async: Log] {

    /**
      * Creates a [[RedisClient]] with default options.
      *
      * Example:
      *
      * {{{
      * RedisClient[IO].from("redis://localhost")
      * }}}
      */
    def from(strUri: => String): Resource[F, RedisClient] =
      Resource.eval(RedisURI.make[F](strUri)).flatMap(this.fromUri(_))

    /**
      * Creates a [[RedisClient]] with default options from a validated URI.
      *
      * Example:
      *
      * {{{
      * for {
      *   uri <- Resource.eval(RedisURI.make[F]("redis://localhost"))
      *   cli <- RedisClient[IO].fromUri(uri)
      * } yield cli
      * }}}
      *
      * You may prefer to use [[from]] instead, which takes a raw string.
      */
    def fromUri(uri: => RedisURI): Resource[F, RedisClient] =
      Resource.eval(F.delay(ClientOptions.create())).flatMap(this.custom(uri, _))

    /**
      * Creates a [[RedisClient]] with the supplied options.
      *
      * Example:
      *
      * {{{
      * for {
      *   ops <- Resource.eval(F.delay(ClientOptions.create())) // configure timeouts, etc
      *   cli <- RedisClient[IO].withOptions("redis://localhost", ops)
      * } yield cli
      * }}}
      */
    def withOptions(
        strUri: => String,
        opts: ClientOptions
    ): Resource[F, RedisClient] =
      Resource.eval(RedisURI.make[F](strUri)).flatMap(this.custom(_, opts))

    /**
      * Creates a [[RedisClient]] with the supplied options from a validated URI.
      *
      * Example:
      *
      * {{{
      * for {
      *   uri <- Resource.eval(RedisURI.make[F]("redis://localhost"))
      *   ops <- Resource.eval(F.delay(ClientOptions.create())) // configure timeouts, etc
      *   cli <- RedisClient[IO].custom(uri, ops)
      * } yield cli
      * }}}
      *
      * Additionally, it can take a [[dev.profunktor.redis4cats.config.Redis4CatsConfig]] to configure the shutdown timeouts,
      * for example. However, you don't need to worry about this in most cases.
      *
      * {{{
      * RedisClient[IO].custom(uri, ops, Redis4CatsConfig())
      * }}}
      *
      * If not supplied, sane defaults will be used.
      */
    def custom(
        uri: => RedisURI,
        opts: ClientOptions,
        config: Redis4CatsConfig = Redis4CatsConfig()
    ): Resource[F, RedisClient] = mkEc.flatMap { ec =>
      val (acquire, release) = acquireAndRelease(uri, opts, config, ec)
      Resource.make(acquire)(release)
    }
  }

  def apply[F[_]: Async: Log]: RedisClientPartiallyApplied[F] = new RedisClientPartiallyApplied[F]

  def fromUnderlyingWithUri(underlying: JRedisClient, uri: RedisURI): RedisClient =
    new RedisClient(underlying, uri) {}

}
