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

import cats.effect._
import cats.syntax.all._
import cats.effect.syntax.all._
import dev.profunktor.redis4cats.data.NodeId
import dev.profunktor.redis4cats.effect.JRFuture
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.async.RedisAsyncCommands
import io.lettuce.core.api.sync.{ RedisCommands => RedisSyncCommands }
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection
import io.lettuce.core.cluster.api.async.RedisClusterAsyncCommands
import io.lettuce.core.cluster.api.sync.{ RedisClusterCommands => RedisClusterSyncCommands }
import scala.util.control.NoStackTrace
import scala.concurrent.ExecutionContext

case class OperationNotSupported(value: String) extends NoStackTrace {
  override def toString(): String = s"OperationNotSupported($value)"
}

private[redis4cats] trait RedisConnection[F[_], K, V] {
  def sync: F[RedisSyncCommands[K, V]]
  def clusterSync: F[RedisClusterSyncCommands[K, V]]
  def async: F[RedisAsyncCommands[K, V]]
  def clusterAsync: F[RedisClusterAsyncCommands[K, V]]
  def close: F[Unit]
  def byNode(nodeId: NodeId): F[RedisAsyncCommands[K, V]]
  def liftK[G[_]: Async]: RedisConnection[G, K, V]
}

private[redis4cats] class RedisStatefulConnection[F[_]: Async, K, V](
    conn: StatefulRedisConnection[K, V],
    ec: ExecutionContext
) extends RedisConnection[F, K, V] {
  def sync: F[RedisSyncCommands[K, V]] = F.delay(conn.sync()).evalOn(ec)
  def clusterSync: F[RedisClusterSyncCommands[K, V]] =
    F.raiseError(OperationNotSupported("Running in a single node"))
  def async: F[RedisAsyncCommands[K, V]] = F.delay(conn.async()).evalOn(ec)
  def clusterAsync: F[RedisClusterAsyncCommands[K, V]] =
    F.raiseError(OperationNotSupported("Running in a single node"))
  def close: F[Unit] = JRFuture.fromCompletableFuture(F.evalOn(F.delay(conn.closeAsync()), ec))(ec).void
  def byNode(nodeId: NodeId): F[RedisAsyncCommands[K, V]] =
    F.raiseError(OperationNotSupported("Running in a single node"))
  def liftK[G[_]: Async]: RedisConnection[G, K, V] =
    new RedisStatefulConnection[G, K, V](conn, ec)
}

private[redis4cats] class RedisStatefulClusterConnection[F[_]: Async, K, V](
    conn: StatefulRedisClusterConnection[K, V],
    ec: ExecutionContext
) extends RedisConnection[F, K, V] {
  def sync: F[RedisSyncCommands[K, V]] =
    F.raiseError(
      OperationNotSupported("Transactions are not supported in a cluster. You must select a single node.")
    )
  def async: F[RedisAsyncCommands[K, V]] =
    F.raiseError(
      OperationNotSupported("Transactions are not supported in a cluster. You must select a single node.")
    )
  def clusterAsync: F[RedisClusterAsyncCommands[K, V]] =
    F.delay[RedisClusterAsyncCommands[K, V]](conn.async()).evalOn(ec)
  def clusterSync: F[RedisClusterSyncCommands[K, V]] = F.delay[RedisClusterSyncCommands[K, V]](conn.sync()).evalOn(ec)
  def close: F[Unit] =
    JRFuture.fromCompletableFuture(F.evalOn(F.delay(conn.closeAsync()), ec))(ec).void
  def byNode(nodeId: NodeId): F[RedisAsyncCommands[K, V]] =
    JRFuture.fromCompletableFuture(F.evalOn(F.delay(conn.getConnectionAsync(nodeId.value)), ec))(ec).flatMap {
      stateful => F.delay(stateful.async()).evalOn(ec)
    }
  def liftK[G[_]: Async]: RedisConnection[G, K, V] =
    new RedisStatefulClusterConnection[G, K, V](conn, ec)
}
