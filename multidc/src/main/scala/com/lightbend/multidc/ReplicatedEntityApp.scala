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

package com.lightbend.multidc

import java.io.File

import akka.actor.ActorSystem
import akka.cluster.Cluster
import akka.cluster.http.management.ClusterHttpManagement
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import akka.persistence.multidc.PersistenceMultiDcSettings
import com.typesafe.config.ConfigFactory

import scala.io.StdIn

object ReplicatedEntityApp extends App {
  val rootConfFile = new File("/home/akka/multidc/application.conf")
  val rootConf =
    if (rootConfFile.exists) ConfigFactory.parseFile(rootConfFile)
    else ConfigFactory.empty("no-root-application-conf-found")
  val conf = rootConf.withFallback(ConfigFactory.load())

  println(s"Cloud configuration: ${rootConfFile.exists}")

  implicit val system: ActorSystem = ActorSystem("MultiDcSystem", conf)


  val cluster = Cluster(system)
  ClusterHttpManagement(cluster).start()

  val shardedCounters = ClusterSharding(system).start(
    typeName = ReplicatedCounter.ShardingTypeName,
    entityProps = ReplicatedCounter.shardingProps(PersistenceMultiDcSettings(system)),
    settings = ClusterShardingSettings(system),
    extractEntityId = ReplicatedCounter.extractEntityId,
    extractShardId = ReplicatedCounter.extractShardId)

  HttpApi.startServer(conf.getString("multidc.host"), conf.getInt("multidc.port"), shardedCounters)

  StdIn.readLine()
  system.terminate()
}

