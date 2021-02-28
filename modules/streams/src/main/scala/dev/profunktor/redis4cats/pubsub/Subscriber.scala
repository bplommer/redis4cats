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

package dev.profunktor.redis4cats
package pubsub

import cats.effect._
import cats.effect.Ref
import cats.effect.implicits._
import cats.effect.std.Dispatcher
import cats.syntax.all._
import dev.profunktor.redis4cats.pubsub.internals.{ PubSubInternals, PubSubState }
import dev.profunktor.redis4cats.data.RedisChannel
import dev.profunktor.redis4cats.effect.{ JRFuture, Log }
import fs2.Stream
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import scala.concurrent.ExecutionContext

class Subscriber[F[_]: Async: Log, K, V](
    state: Ref[F, PubSubState[F, K, V]],
    subConnection: StatefulRedisPubSubConnection[K, V],
    ec: ExecutionContext
) extends SubscribeCommands[Stream[F, *], K, V] {

  override def subscribe(channel: RedisChannel[K]): Stream[F, V] =
    Stream.resource(Dispatcher[F]).flatMap { implicit dispatcher =>
      Stream
        .eval(
          state.get.flatMap { st =>
            PubSubInternals[F, K, V](state, subConnection).apply(channel)(st) <*
              JRFuture(F.delay(subConnection.async().subscribe(channel.underlying)))(ec)
          }
        )
        .flatMap(_.subscribe(500).unNone)
    }

  override def unsubscribe(channel: RedisChannel[K]): Stream[F, Unit] =
    Stream.eval {
      JRFuture(F.delay(subConnection.async().unsubscribe(channel.underlying)))(ec).void
        .guarantee(state.get.flatMap { st =>
          st.get(channel.underlying).fold(F.unit)(_.publish1(none[V])) *> state.update(_ - channel.underlying)
        })
    }

}
