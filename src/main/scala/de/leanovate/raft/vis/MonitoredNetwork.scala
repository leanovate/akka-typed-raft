package de.leanovate.raft.vis

import akka.typed.{ActorRef, Behavior}
import akka.typed.scaladsl.Actor
import de.leanovate.raft.Raft
import de.leanovate.raft.Raft.Message

import scala.concurrent.duration._

object MonitoredNetwork {

  def behaviour(sink: String => Unit): Behavior[Messages] =
    Actor.deferred[Messages] { ctx =>
      println("Started monitored network")

      val names = (1 to 5).map("node" + _)

      val adapters = names.map { name: String =>
        ctx.spawnAdapter { msg: Raft.Message =>
          NetworkMessage(msg, name)
        }
      }.toSet

      val nodes = names
        .map(name => name -> ctx.spawn(slowRaftBehavior(adapters), name))
        .toMap

      Actor.immutable {
        case (_, NetworkMessage(msg, node)) =>
          sink(msg.toString + " to " + node)
          nodes(node) ! msg
          Actor.same
      }
    }

  sealed trait Messages
  private case class NetworkMessage(msg: Raft.Message, node: String)
      extends Messages

  private def slowRaftBehavior(
      nodes: Set[ActorRef[Message]]): Behavior[Raft.Message] =
    Raft.behavior(nodes,
                  leaderHeartbeat = 2.seconds,
                  followerTimeout = 5.seconds -> 10.seconds,
                  candidateTimeout = 3.seconds)
}
