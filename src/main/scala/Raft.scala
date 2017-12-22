
import akka.typed.scaladsl.Actor
import akka.typed.{ActorRef, Behavior}

import scala.concurrent.duration.DurationLong

object Raft {

  def behavior: Behavior[Message] =
    follower(Set.empty, 0, None)


  def leader(nodes: Set[ActorRef[Message]], currentTerm: Int): Behavior[Message] = Actor.withTimers { timer =>
    Actor.deferred { ctx =>
      println(ctx.self + " became leader")
      Actor.immutable { (ctx, msg) =>
        msg match {
          case HeartbeatTick =>
            nodes.foreach(_ ! Heartbeat(currentTerm))
            Actor.same
          case VoteRequest(newLeader, newTerm) if newTerm > currentTerm =>
            newLeader ! VoteResponse(newTerm)
            follower(nodes, newTerm, Some(newLeader))
          case VoteRequest(c, oldTerm) if oldTerm <= currentTerm =>
            Actor.same
          case _: VoteResponse =>
            Actor.same
        }
      }
    }
  }

  def follower(nodes: Set[ActorRef[Message]], currentTerm: Int, votedFor: Option[ActorRef[Message]]): Behavior[Message] = Actor.withTimers { timer =>
    timer.startSingleTimer("", LeaderTimeout, 300.milliseconds)
    Actor.deferred { ctx =>
      println(ctx.self + " became follower")
      Actor.immutable { (ctx, msg) =>
        msg match {
          case LeaderTimeout =>
            timer.cancelAll()
            candidate(nodes, currentTerm + 1)
          case TermMessage(oldTerm) if oldTerm < currentTerm =>
            Actor.same
          case Heartbeat(_) =>
            timer.cancelAll()
            timer.startSingleTimer("", LeaderTimeout, 300.milliseconds)
            Actor.same
          case VoteRequest(candidate, `currentTerm`) if votedFor != Some(candidate) =>
            Actor.same
          case VoteRequest(candidate, newTerm) if newTerm >= currentTerm =>
            timer.cancelAll()
            timer.startSingleTimer("", LeaderTimeout, 2.seconds)
            candidate ! VoteResponse(newTerm)
            Actor.same
        }
      }
    }
  }

  private val candidateTimeout = 200.milliseconds

  def candidate(nodes: Set[ActorRef[Message]], currentTerm: Int): Behavior[Message] = Actor.withTimers { timer =>
    timer.startSingleTimer("", CandidateTimeout, candidateTimeout)


    def waitingCandidate(votes: Int): Behavior[Message] = Actor.immutable { (ctx, msg) =>
      msg match {
        case VoteResponse(`currentTerm`) if (votes + 1) > (nodes.size / 2) =>
          nodes.foreach(_ ! Heartbeat(currentTerm))
          leader(nodes, currentTerm)
        case VoteResponse(`currentTerm`) =>
          waitingCandidate(votes + 1)
        case VoteResponse(_) =>
          Actor.same
        case CandidateTimeout =>
          println(ctx.self + " candidate timeout, start new term")
          candidate(nodes, currentTerm + 1)
        case Heartbeat(newTerm) if newTerm >= currentTerm =>
          timer.cancelAll()
          follower(nodes, newTerm, None)
        case Heartbeat(_) =>
          Actor.same
      }
    }

    Actor.deferred { ctx =>
      println(ctx.self + " became candidate")
      (nodes - ctx.self).foreach(_ ! VoteRequest(ctx.self, currentTerm))

      waitingCandidate(1 /* one self vote */)
    }
  }


  sealed trait Message

  sealed trait TermMessage extends Message {
    def term: Int
  }

  object TermMessage {
    def unapply(arg: TermMessage): Option[Int] = Some(arg.term)
  }

  case class Heartbeat(term: Int) extends TermMessage

  case class VoteRequest(candidate: ActorRef[Message], term: Int) extends TermMessage

  case class VoteResponse(term: Int) extends TermMessage

  case object Timeout extends Message

  val HeartbeatTick: Timeout.type = Timeout
  val LeaderTimeout: Timeout.type = Timeout
  val CandidateTimeout: Timeout.type = Timeout

}
