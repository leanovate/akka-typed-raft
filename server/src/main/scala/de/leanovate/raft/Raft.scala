package de.leanovate.raft

import akka.typed.scaladsl.{Actor, TimerScheduler}
import akka.typed.{ActorRef, Behavior}

import scala.annotation.tailrec
import scala.concurrent.duration._

object Raft {

  def behaviour(
      clusterConfiguration: ClusterConfiguration): Behavior[In.Message] =
    startAsFollower()(clusterConfiguration).narrow[Raft.In.Message]

  private[raft] def startAsLeader(currentTerm: Int = 0)(
      implicit config: ClusterConfiguration): Behavior[In.PrivateMessage] =
    Actor.withTimers { leader(currentTerm)(config, _) }

  private[raft] def startAsFollower(currentTerm: Int = 0,
                                    votedFor: Option[Ambassador] = None,
                                    currentLeader: Option[Ambassador] = None)(
      implicit config: ClusterConfiguration): Behavior[In.PrivateMessage] =
    Actor.withTimers {
      follower(currentTerm, votedFor, currentLeader)(config, _)
    }

  private[raft] def startAsCandidate(currentTerm: Int = 0)(
      implicit config: ClusterConfiguration): Behavior[In.PrivateMessage] =
    Actor.withTimers { candidate(currentTerm)(config, _) }

  case class ClusterConfiguration(
      ambassadors: Set[Ambassador],
      logger: ActorRef[String],
      leaderHeartbeat: FiniteDuration,
      followerTimeout: (FiniteDuration, FiniteDuration),
      candidateTimeout: FiniteDuration) {

    val (minimalFollowerTimeout, maximalFollowerTimeout) = followerTimeout
    require(minimalFollowerTimeout < maximalFollowerTimeout)
  }

  private def leader(currentTerm: Int)(
      implicit config: ClusterConfiguration,
      timer: TimerScheduler[In.PrivateMessage]): Behavior[In.PrivateMessage] =
    Actor.deferred { ctx =>
      config.logger ! "became leader"
      timer.startPeriodicTimer("", In.HeartbeatTick, config.leaderHeartbeat)
      Actor.immutable { (_, msg) =>
        msg match {
          case In.HeartbeatTick =>
            config.ambassadors.foreach(_ ! Out.Heartbeat(currentTerm))
            Actor.same
          case In.VoteRequest(newLeader, newTerm) if newTerm > currentTerm =>
            newLeader ! Out.VoteResponse(newTerm)
            timer.cancelAll()
            follower(newTerm, Some(newLeader), Some(newLeader))
          case In.VoteRequest(_, oldTerm) if oldTerm <= currentTerm =>
            Actor.same
          case _: In.VoteResponse =>
            Actor.same
        }
      }
    }

  private def follower(currentTerm: Int,
                       votedFor: Option[Ambassador],
                       currentLeader: Option[Ambassador])(
      implicit config: ClusterConfiguration,
      timer: TimerScheduler[In.PrivateMessage]): Behavior[In.PrivateMessage] = {
    def resetTimer(): Unit = {
      timer.cancelAll()
      timer.startSingleTimer("", In.LeaderTimeout, randomFollowerTimeout())
    }

    Actor.deferred { ctx =>
      resetTimer()
      config.logger ! s" became follower in term $currentTerm"
      Actor.immutable { (_, msg) =>
        msg match {
          case In.LeaderTimeout =>
            timer.cancelAll()
            candidate(currentTerm + 1)
          case In.Message(oldTerm) if oldTerm < currentTerm =>
            Actor.same
          case In.Heartbeat(newTerm) if newTerm > currentTerm =>
            follower(newTerm, None, None)
          case In.Heartbeat(`currentTerm`) =>
            resetTimer()
            Actor.same
          case In.VoteRequest(candidate, `currentTerm`)
              if votedFor != Some(candidate) =>
            Actor.same
          case In.VoteRequest(candidate, newTerm) if newTerm >= currentTerm =>
            resetTimer()
            candidate ! Out.VoteResponse(newTerm)
            Actor.same
          case In.VoteResponse(_) =>
            Actor.same
          case In.Command(respondTo) =>
            respondTo ! Left(currentLeader.get)
            Actor.same
        }
      }
    }
  }
  @tailrec
  private[raft] final def randomFollowerTimeout()(
      implicit config: ClusterConfiguration): FiniteDuration = {
    val span = config.maximalFollowerTimeout - config.minimalFollowerTimeout

    math.random() * span match {
      case offset: FiniteDuration => config.minimalFollowerTimeout + offset
      case unexpectedResult =>
        config.logger ! s"Got $unexpectedResult during timeout generation, trying again"
        randomFollowerTimeout()
    }
  }

  private def candidate(currentTerm: Int)(
      implicit config: ClusterConfiguration,
      timer: TimerScheduler[In.PrivateMessage]): Behavior[In.PrivateMessage] = {
    timer.startSingleTimer("", In.CandidateTimeout, config.candidateTimeout)

    def waitingCandidate(requiredVotes: Int): Behavior[In.PrivateMessage] =
      Actor.immutable { (ctx, msg) =>
        msg match {
          case In.VoteResponse(`currentTerm`) =>
            val openVotes = requiredVotes - 1
            if (openVotes == 0) {
              ctx.self ! In.HeartbeatTick
              leader(currentTerm)
            } else {
              waitingCandidate(openVotes)
            }

          case In.VoteResponse(_) =>
            Actor.same
          case In.CandidateTimeout =>
            config.logger ! "candidate timeout, start new term"
            candidate(currentTerm + 1)
          case In.Message(newTerm) if newTerm >= currentTerm =>
            timer.cancelAll()
            follower(newTerm, None, None)
          case In.Heartbeat(_) =>
            Actor.same
          case _: In.VoteRequest =>
            Actor.same
        }
      }

    Actor.deferred { ctx =>
      config.logger ! s"candidate in term $currentTerm"
      config.ambassadors.foreach(_ ! Out.VoteRequest(currentTerm))

      val requiredConfirmations: Int =
        minimalMajority(config.ambassadors.size + 1) - 1 /* one self vote */

      waitingCandidate(requiredConfirmations)
    }
  }

  private[raft] def minimalMajority(clusterSize: Int): Int =
    clusterSize / 2 + 1

  type Ambassador = ActorRef[Out.Message]

  object In {
    private[raft] sealed trait PrivateMessage

    sealed trait Message extends PrivateMessage {
      def term: Int
    }

    object Message {
      def unapply(arg: Message): Option[Int] = Some(arg.term)
    }

    case class Heartbeat(term: Int) extends Message

    case class VoteRequest(candidate: Ambassador, term: Int) extends Message

    case class VoteResponse(term: Int) extends Message

    case class Command(
        respondTo: ActorRef[Either[ActorRef[Raft.Out.Message], Unit]])
        extends PrivateMessage

    private[raft] case object Timeout extends PrivateMessage
    private[raft] val HeartbeatTick: Timeout.type = Timeout
    private[raft] val LeaderTimeout: Timeout.type = Timeout

    private[raft] val CandidateTimeout: Timeout.type = Timeout

  }

  object Out {
    sealed trait Message

    sealed trait TermMessage extends Message {
      def term: Int
    }

    object TermMessage {
      def unapply(arg: TermMessage): Option[Int] = Some(arg.term)
    }

    case class Heartbeat(term: Int) extends TermMessage

    case class VoteRequest(term: Int) extends TermMessage

    case class VoteResponse(term: Int) extends TermMessage
  }
}
