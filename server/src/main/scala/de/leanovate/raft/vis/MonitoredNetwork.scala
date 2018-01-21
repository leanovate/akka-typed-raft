package de.leanovate.raft.vis

import java.time.Clock

import akka.typed.scaladsl.Actor
import akka.typed.{ActorRef, Behavior}
import de.leanovate.raft.Raft

import scala.concurrent.duration._

object MonitoredNetwork {

  private val slowMessages = 500.milliseconds
  private val slowRaft =
    Raft.ClusterConfiguration(_,
      leaderHeartbeat = 2.seconds,
      followerTimeout = 5.seconds -> 10.seconds,
      candidateTimeout = 3.seconds)


  def behaviour(sink: NetworkEvent => Unit, clock: Clock = Clock.systemUTC()): Behavior[Messages] =
    Actor.deferred[Messages] { ctx =>
      println("Started monitored network")

      val startTime = clock.millis()

      val names = (1 to 5).map("node" + _)

      val ambassadorFor = (for {
        from <- names
        to <- names
      } yield
        (from, to) -> ctx.spawnAdapter { msg: Raft.Out.Message =>
          NetworkMessage(msg, from, to)
        }).toMap

      val nodes = names.map { name =>
        val ambassadors = ambassadorFor.collect {
          case ((`name`, notSelf), ambassador) if notSelf != name => ambassador
        }.toSet
        name -> ctx.spawn(Raft.behaviour(slowRaft(ambassadors)), name)
      }.toMap

      Actor.immutable {
        case (_, DelayedNetworkMessage(msg, from, to)) =>

          nodes(to) ! outToInMessage(ambassadorFor(to -> from))(msg)
          Actor.same
        case (ctx, NetworkMessage(msg, from, to)) =>
          val relativeTime = (clock.millis() - startTime).toDouble / 1000
          sink(MessageSent(from, to, relativeTime, Map("c" -> msg.toString)))
          ctx.schedule(slowMessages,
                       ctx.self,
                       DelayedNetworkMessage(msg, from, to))
          Actor.same
      }
    }

  sealed trait Messages

  private case class NetworkMessage(msg: Raft.Out.Message,
                                    from: String,
                                    to: String)
      extends Messages
  private case class DelayedNetworkMessage(msg: Raft.Out.Message,
                                           from: String,
                                           to: String)
      extends Messages

  private def outToInMessage(ambassador: ActorRef[Raft.Out.Message])
    : Raft.Out.Message => Raft.In.Message = {
    case Raft.Out.Heartbeat(term)    => Raft.In.Heartbeat(term)
    case Raft.Out.VoteRequest(term)  => Raft.In.VoteRequest(ambassador, term)
    case Raft.Out.VoteResponse(term) => Raft.In.VoteResponse(term)
  }
}
