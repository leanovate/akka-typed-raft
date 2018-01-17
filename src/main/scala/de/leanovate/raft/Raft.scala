package de.leanovate.raft

import akka.typed.scaladsl.{Actor, TimerScheduler}
import akka.typed.{ActorRef, Behavior}

import scala.annotation.tailrec
import scala.concurrent.duration._

object Raft {

  def behaviour(clusterConfiguration: ClusterConfiguration): Behavior[Message] =
    startAsFollower()(clusterConfiguration)

  def startAsLeader(currentTerm: Int = 0)(
      implicit config: ClusterConfiguration): Behavior[Message] =
    Actor.withTimers { leader(currentTerm)(config, _) }

  def startAsFollower(currentTerm: Int = 0,
                      votedFor: Option[ActorRef[Message]] = None)(
      implicit config: ClusterConfiguration): Behavior[Message] =
    Actor.withTimers { follower(currentTerm, votedFor)(config, _) }

  def startAsCandidate(currentTerm: Int = 0)(
      implicit config: ClusterConfiguration): Behavior[Message] =
    Actor.withTimers { candidate(currentTerm)(config, _) }

  case class ClusterConfiguration(
      nodes: Set[ActorRef[Message]],
      leaderHeartbeat: FiniteDuration,
      followerTimeout: (FiniteDuration, FiniteDuration),
      candidateTimeout: FiniteDuration) {

    val (minimalFollowerTimeout, maximalFollowerTimeout) = followerTimeout
    require(minimalFollowerTimeout < maximalFollowerTimeout)
  }

  private def leader(currentTerm: Int)(
      implicit config: ClusterConfiguration,
      timer: TimerScheduler[Message]): Behavior[Message] =
    Actor.deferred { ctx =>
      println(ctx.self + " became leader")
      timer.startPeriodicTimer("", HeartbeatTick, 150.milliseconds)
      Actor.immutable { (_, msg) =>
        msg match {
          case HeartbeatTick =>
            config.nodes.foreach(_ ! Heartbeat(currentTerm))
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

  private def follower(currentTerm: Int, votedFor: Option[ActorRef[Message]])(
      implicit config: ClusterConfiguration,
      timer: TimerScheduler[Message]): Behavior[Message] = {
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
          case VoteRequest(candidate, `currentTerm`)
              if votedFor != Some(candidate) =>
            Actor.same
          case VoteRequest(candidate, newTerm) if newTerm >= currentTerm =>
            resetTimer()
            candidate ! VoteResponse(newTerm)
            Actor.same
          case VoteResponse(_) =>
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
        println(
          s"Got $unexpectedResult during timeout generation, trying again")
        randomFollowerTimeout()
    }
  }

  private def candidate(currentTerm: Int)(
      implicit config: ClusterConfiguration,
      timer: TimerScheduler[Message]): Behavior[Message] = {
    timer.startSingleTimer("", CandidateTimeout, config.candidateTimeout)

    def waitingCandidate(requiredVotes: Int): Behavior[Message] =
      Actor.immutable { (ctx, msg) =>
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
          case TermMessage(newTerm) if newTerm >= currentTerm =>
            timer.cancelAll()
            follower(newTerm, None)
          case Heartbeat(_) =>
            Actor.same
        }
      }

    Actor.deferred { ctx =>
      println(ctx.self + " became candidate")
      (config.nodes - ctx.self).foreach(_ ! VoteRequest(ctx.self, currentTerm))

      val requiredConfirmations
        : Int = minimalMajority(config.nodes.size) - 1 /* one self vote */

      waitingCandidate(requiredConfirmations)
    }
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

  case class VoteRequest(candidate: ActorRef[Message], term: Int)
      extends TermMessage

  case class VoteResponse(term: Int) extends TermMessage

  private[raft] case object Timeout extends Message

  private[raft] val HeartbeatTick: Timeout.type = Timeout
  private[raft] val LeaderTimeout: Timeout.type = Timeout
  private[raft] val CandidateTimeout: Timeout.type = Timeout
}
