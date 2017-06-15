/*
 * Copyright 2017 Lightbend Inc.
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

package com.lightbend.akka.bench.sharding

import akka.actor.{ActorLogging, ActorSystem, Props}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings, ShardRegion}
import akka.persistence.{PersistentActor, RecoveryCompleted}

object BenchEntity {

  // commands
  trait EntityCommand { def id: String }
  trait PingLike { def sentTimestamp: Long }
  case class Ping(id: String, sentTimestamp: Long) extends EntityCommand with PingLike
  case class PersistAndPing(id: String, sentTimestamp: Long) extends EntityCommand with PingLike
  case class Pong(original: PingLike)

  // events
  case class PingObserved(sentTimestamp: Long)

  def props() = Props[BenchEntity]


  // sharding config
  val typeName = "bench-entity"

  val extractEntityId: ShardRegion.ExtractEntityId = {
    case msg: BenchEntity.EntityCommand => (msg.id, msg)
  }

  def extractShardId(numberOfEntities: Int): ShardRegion.ExtractShardId = {
    case msg: BenchEntity.EntityCommand => (msg.id.hashCode % numberOfEntities).toString
  }

  def startRegion(system: ActorSystem) =
    ClusterSharding(system).start(
      typeName,
      BenchEntity.props(),
      ClusterShardingSettings(system),
      extractEntityId,
      extractShardId(BenchSettings(system).NumberOfShards)
    )

  def proxy(system: ActorSystem) =
    ClusterSharding(system)
      .startProxy(
        typeName,
        None,
        extractEntityId,
        extractShardId(BenchSettings(system).NumberOfShards)
      )
}

class BenchEntity extends PersistentActor with ActorLogging {
  import BenchEntity._

  var persistentPingCounter = 0

  override def persistenceId: String = self.path.name

  val start = System.nanoTime()
  override def receiveRecover: Receive = {
    case _ :PingObserved =>
      persistentPingCounter += 1

    case _: RecoveryCompleted =>
      val recoveryMs = (System.nanoTime() - start) / 1000000
      PersistenceHistograms.recoveryTiming.recordValue(recoveryMs)

  }

  override def receiveCommand: Receive = {

    case msg: Ping =>
      // simple roundtrip
      log.debug("Got ping from [{}]", sender())
      sender() ! Pong(msg)

    case msg: PersistAndPing =>
      // roundtrip with write
      log.debug("Got persist-ping from [{}]", sender())
      val before = System.nanoTime()
      persist(PingObserved(msg.sentTimestamp)){ _ =>
        val persistMs = (System.nanoTime() - before) / 1000000
        PersistenceHistograms.persistTiming.recordValue(persistMs)
        persistentPingCounter += 1
        sender() ! Pong(msg)
      }

  }


}
