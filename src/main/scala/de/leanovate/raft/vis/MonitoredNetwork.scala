package de.leanovate.raft.vis

import akka.typed.Behavior
import akka.typed.scaladsl.Actor

import scala.concurrent.duration._

object MonitoredNetwork {

  def behaviour(sink: String => Unit): Behavior[Messages] =
    Actor.deferred[Messages] { ctx =>
      println("Started monitored network")
      Actor.withTimers { timer =>
        timer.startPeriodicTimer("", Heartbeat, 2.seconds)

        Actor.immutable {
          case (_, Heartbeat) =>
            sink("Beat")
            Actor.same
        }

      }
    }

  sealed trait Messages
  private case object Heartbeat extends Messages
}
