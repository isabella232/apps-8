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

package com.lightbend.akka.bench.sharding.latency

import akka.actor.{ Actor, ActorLogging, ActorSystem, Props }
import akka.cluster.sharding.{ ClusterSharding, ClusterShardingSettings, ShardRegion }
import akka.persistence.{ PersistentActor, RecoveryCompleted }
import com.lightbend.akka.bench.sharding.BenchSettings
import scala.concurrent.duration._

object LatencyBenchEntity {

  // commands
  sealed trait EntityCommand { def id: String }
  sealed trait PingLike { def id: String; def sentTimestamp: Long }
  final case class PingFirst(id: String, sentTimestamp: Long) extends EntityCommand with PingLike
  final case class PongFirst(original: PingLike, wakeupTimeMs: Long)
  
  final case class PingSecond(id: String, sentTimestamp: Long) extends PingLike
  final case class PongSecond(original: PingLike)

  final case class PersistAndPing(id: String, sentTimestamp: Long) extends EntityCommand with PingLike
  
  // events
  case class PingObserved(sentTimestamp: Long)

  def props() = Props[LatencyBenchEntity]


  // sharding config
  val typeName = "bench-entity"

  val extractEntityId: ShardRegion.ExtractEntityId = {
    case msg: LatencyBenchEntity.EntityCommand => (msg.id, msg)
  }

  def extractShardId(numberOfEntities: Int): ShardRegion.ExtractShardId = {
    case msg: LatencyBenchEntity.EntityCommand => (Math.abs(msg.id.hashCode) % numberOfEntities).toString
  }

  def startRegion(system: ActorSystem) =
    ClusterSharding(system).start(
      typeName,
      LatencyBenchEntity.props(),
      ClusterShardingSettings(system).withRole("shard"),
      extractEntityId,
      extractShardId(BenchSettings(system).NumberOfShards)
    )

  def proxy(system: ActorSystem) =
    ClusterSharding(system)
      .startProxy(
        typeName,
        Some("shard"),
        extractEntityId,
        extractShardId(BenchSettings(system).NumberOfShards)
      )
}

class LatencyBenchEntity extends Actor with ActorLogging { // FIXME: FIX CASSANDRA AND DO PERSISTENCE BENCH
  import LatencyBenchEntity._

  log.info(s"Started ${self.path.name}")
  
  var persistentPingCounter = 0
//  override def persistenceId: String = self.path.name

  val started = System.currentTimeMillis()

  override def receive = {// Command: Receive = {
    case msg: PingFirst =>
      // simple roundtrip
      sender() ! PongFirst(msg, started)
    
    case msg: PingSecond =>
      // simple roundtrip
      sender() ! PongSecond(msg)

//    case msg: PersistAndPing =>
//      // roundtrip with write
//      log.debug("Got persist-ping from [{}]", sender())
//      val before = System.nanoTime()
//      persist(PingObserved(msg.sentTimestamp)) { _ =>
//        val persistMs = (System.nanoTime() - before).nanos.toMillis
//        PersistenceHistograms.persistTiming.recordValue(persistMs)
//        persistentPingCounter += 1
//        sender() ! Pong(msg)
//      }
      
    case other =>
      log.info("received something else: " + other)
      throw new Exception("What is: " + other.getClass)
  }
  
  def receiveRecover: Receive = {
    case _: PingObserved =>
      persistentPingCounter += 1

    case _: RecoveryCompleted =>
      val recoveryMs = (System.nanoTime() - started).nanos
      PersistenceHistograms.recoveryTiming.recordValue(recoveryMs.toMillis)

  }
  


}
