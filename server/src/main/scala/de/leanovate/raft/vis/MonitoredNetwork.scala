package de.leanovate.raft.vis

import java.time.Clock

import akka.typed.scaladsl.Actor
import akka.typed.{ActorRef, Behavior}
import de.leanovate.raft.Raft
import de.leanovate.raft.Raft.Out

import scala.concurrent.duration._

object MonitoredNetwork {

  private val slowMessages = 1.second
  private val slowRaft: (Set[ActorRef[Out.Message]],
                         ActorRef[String]) => Raft.ClusterConfiguration =
    Raft.ClusterConfiguration(_,
                              _,
                              leaderHeartbeat = 3.seconds,
                              followerTimeout = 5.seconds -> 10.seconds,
                              candidateTimeout = 4.seconds)

  def behaviour(sink: NetworkEvent => Unit,
                clock: Clock = Clock.systemUTC()): Behavior[Messages] =
    Actor.deferred[Messages] { ctx =>
      println("Started monitored network")

      def time() = clock.millis().toDouble / 1000

      val names = (1 to 5).map(index => NodeName("node" + index))

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
        val logger = ctx.spawnAdapter[String] { msg: String =>
          NodeLogMessage(name, msg)
        }
        name -> ctx.spawn(Raft.behaviour(slowRaft(ambassadors, logger)),
                          name.name)
      }.toMap

      Actor.immutable {
        case (_, DelayedNetworkMessage(msg, from, to)) =>
          nodes(to) ! outToInMessage(ambassadorFor(to -> from))(msg)
          Actor.same
        case (actorContext, NetworkMessage(msg, from, to)) =>
          sink(
            MessageSent(from,
                        to,
                        time(),
                        time() + slowMessages.toMillis.toDouble / 1000.0,
                        Map("c" -> msg.toString)))
          actorContext.schedule(slowMessages,
                       actorContext.self,
                       DelayedNetworkMessage(msg, from, to))
          Actor.same
        case (_, NodeLogMessage(node, msg)) =>
          sink(NodeUpdate(node, time(), Map("c" -> msg)))
          Actor.same
      }
    }

  sealed trait Messages

  private case class NetworkMessage(msg: Raft.Out.Message,
                                    form: NodeName,
                                    to: NodeName)
      extends Messages
  private case class DelayedNetworkMessage(msg: Raft.Out.Message,
                                           from: NodeName,
                                           to: NodeName)
      extends Messages
  private case class NodeLogMessage(node: NodeName, msg: String)
      extends Messages

  private def outToInMessage(ambassador: ActorRef[Raft.Out.Message])
    : Raft.Out.Message => Raft.In.Message = {
    case Raft.Out.Heartbeat(term, appendEntriesCommand) => Raft.In.Heartbeat(ambassador, term, appendEntriesCommand)
    case Raft.Out.VoteRequest(term)  => Raft.In.VoteRequest(ambassador, term)
    case Raft.Out.VoteResponse(term) => Raft.In.VoteResponse(term)
  }
}
