
import akka.typed.scaladsl.Actor
import akka.typed.{ActorRef, Behavior}

import scala.concurrent.duration.DurationLong

object Raft {

  def behavior: Behavior[Message] =
    follower(Set.empty, 0, None)


  def leader(nodes: Set[ActorRef[Message]], currentTerm: Int): Behavior[Message] = Actor.withTimers { timer =>
    Actor.immutable { (ctx, msg) =>
      msg match {
        case HeartbeatTick =>
          nodes.foreach(_ ! Heartbeat)
          Actor.same
        case VoteRequest(newLeader, newTerm) if newTerm > currentTerm =>
          newLeader ! VoteResponse()
          follower(nodes, newTerm, Some(newLeader))
        case VoteRequest(c, oldTerm) if oldTerm <= currentTerm =>
          Actor.same
      }
    }
  }

  def follower(nodes: Set[ActorRef[Message]], currentTerm: Int, votedFor: Option[ActorRef[Message]]): Behavior[Message] = Actor.withTimers { timer =>
    Actor.immutable{ (ctx, msg) =>
      msg match {
        case LeaderTimeout =>
          (nodes - ctx.self).foreach(_ ! VoteRequest(ctx.self, currentTerm + 1))
          candidate(nodes, currentTerm + 1)
        case Heartbeat =>
          timer.cancelAll()
          timer.startSingleTimer("", LeaderTimeout, 2.seconds)
          Actor.same
        case VoteRequest(candidate, newTerm) if newTerm == currentTerm && votedFor != Some(candidate) =>
          Actor.same
        case VoteRequest(_, oldTerm) if oldTerm < currentTerm =>
          Actor.same
        case VoteRequest(candidate, newTerm) if newTerm >= currentTerm =>
          timer.cancelAll()
          timer.startSingleTimer("", LeaderTimeout, 2.seconds)
          candidate ! VoteResponse()
          Actor.same
      }

    }
  }

  def candidate(nodes: Set[ActorRef[Message]], currentTerm: Int): Behavior[Message] = {
    def waitingCandidate(votes: Int): Behavior[Message] = Actor.immutable { (ctx, msg) =>
      msg match {
        case VoteResponse() if (votes + 1) > (nodes.size / 2) =>
          nodes.foreach(_ ! Heartbeat)
          leader(nodes, currentTerm)
        case VoteResponse() =>
          waitingCandidate(votes + 1)
      }
    }

    waitingCandidate(1 /* self vote */)
  }


  sealed trait Message

  case object Heartbeat extends Message
  case object Timeout extends Message
  val HeartbeatTick: Timeout.type = Timeout
  val LeaderTimeout: Timeout.type = Timeout
  case class VoteRequest(candidate: ActorRef[Message], term: Int) extends Message

  case class VoteResponse() extends Message

}
