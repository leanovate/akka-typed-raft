
import akka.typed.scaladsl.{Actor, TimerScheduler}
import akka.typed.{ActorRef, Behavior}

import scala.annotation.tailrec
import scala.concurrent.duration.{DurationLong, FiniteDuration}

object Raft {

  def behavior: Behavior[Message] = Actor.withTimers { timer =>
    new ClusterConfiguration(Set.empty, timer,
      leaderHeartbeat = 200.milliseconds,
      followerTimeout = 500.milliseconds -> 800.milliseconds,
      candidateTimeout = 300.milliseconds
    ).follower(0, None)
  }

  class ClusterConfiguration(
                              nodes: Set[ActorRef[Message]],
                              timer: TimerScheduler[Message],
                              leaderHeartbeat: FiniteDuration,
                              followerTimeout: (FiniteDuration, FiniteDuration),
                              candidateTimeout: FiniteDuration) {

    val (minimalFollowerTimeout, maximalFollowerTimeout) = followerTimeout
    require(minimalFollowerTimeout < maximalFollowerTimeout)

    def leader(currentTerm: Int): Behavior[Message] = {
      Actor.deferred { ctx =>
        println(ctx.self + " became leader")
        timer.startPeriodicTimer("", HeartbeatTick, 150.milliseconds)
        Actor.immutable { (_, msg) =>
          msg match {
            case HeartbeatTick =>
              nodes.foreach(_ ! Heartbeat(currentTerm))
              Actor.same
            case VoteRequest(newLeader, newTerm) if newTerm > currentTerm =>
              newLeader ! VoteResponse(newTerm)
              timer.cancelAll()
              follower(newTerm, Some(newLeader))
            case VoteRequest(_, oldTerm) if oldTerm <= currentTerm =>
              Actor.same
            case _: VoteResponse =>
              Actor.same
          }
        }
      }
    }


    @tailrec
    final def randomFollowerTimeout(): FiniteDuration = {
      val span = maximalFollowerTimeout - minimalFollowerTimeout

      math.random() * span match {
        case offset: FiniteDuration => minimalFollowerTimeout + offset
        case unexpectedResult =>
          println(s"Got $unexpectedResult during timeout generation, trying again")
          randomFollowerTimeout()
      }
    }

    def follower(currentTerm: Int, votedFor: Option[ActorRef[Message]]): Behavior[Message] = {
      def resetTimer(): Unit = {
        timer.cancelAll()
        timer.startSingleTimer("", LeaderTimeout, randomFollowerTimeout())
      }

      Actor.deferred { ctx =>
        resetTimer()
        println(ctx.self + s" became follower in term $currentTerm")
        Actor.immutable { (_, msg) =>
          msg match {
            case LeaderTimeout =>
              timer.cancelAll()
              candidate(currentTerm + 1)
            case TermMessage(oldTerm) if oldTerm < currentTerm =>
              Actor.same
            case Heartbeat(newTerm) if newTerm > currentTerm =>
              follower(newTerm, None)
            case Heartbeat(`currentTerm`) =>
              resetTimer()
              Actor.same
            case VoteRequest(candidate, `currentTerm`) if votedFor != Some(candidate) =>
              Actor.same
            case VoteRequest(candidate, newTerm) if newTerm >= currentTerm =>
              resetTimer()
              candidate ! VoteResponse(newTerm)
              Actor.same
          }
        }
      }
    }

    def candidate(currentTerm: Int): Behavior[Message] = {
      timer.startSingleTimer("", CandidateTimeout, candidateTimeout)

      def waitingCandidate(requiredVotes: Int): Behavior[Message] = Actor.immutable { (ctx, msg) =>
        msg match {
          case VoteResponse(`currentTerm`) =>
            val openVotes = requiredVotes - 1
            if (openVotes == 0) {
              ctx.self ! HeartbeatTick
              leader(currentTerm)
            } else {
              waitingCandidate(openVotes)
            }

          case VoteResponse(_) =>
            Actor.same
          case CandidateTimeout =>
            println(ctx.self + " candidate timeout, start new term")
            candidate(currentTerm + 1)
          case Heartbeat(newTerm) if newTerm >= currentTerm =>
            timer.cancelAll()
            follower(newTerm, None)
          case Heartbeat(_) =>
            Actor.same
        }
      }

      Actor.deferred { ctx =>
        println(ctx.self + " became candidate")
        (nodes - ctx.self).foreach(_ ! VoteRequest(ctx.self, currentTerm))

        waitingCandidate(requiredConfirmations)
      }
    }

    val requiredConfirmations: Int = minimalMajority(nodes.size) - 1 /* one self vote */
  }

  def minimalMajority(clusterSize: Int): Int =
    clusterSize / 2 + 1

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
