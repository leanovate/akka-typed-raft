import java.util.concurrent.atomic.AtomicReference

import akka.typed.ActorSystem
import akka.typed.scaladsl.{Actor, ActorContext}
import akka.typed.testkit.TestKitSettings
import akka.typed.testkit.scaladsl.TestProbe
import org.scalatest.concurrent.Eventually
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import scala.util.Try

class RaftTest extends FlatSpec with Matchers with Eventually {

  implicit def systemFromContext(implicit ctx: ActorContext[_]): ActorSystem[_] =
    ctx.system

  implicit def testKitSettingsFromContext(implicit ctx: ActorContext[_]): TestKitSettings =
    TestKitSettings(ctx.system)

  def cluster(testcase: ActorContext[_] => Unit): Unit = {
    val res = new AtomicReference[Option[Throwable]]

    val cluster = Actor.deferred[Nothing] { implicit ctx =>
      res.set(Try {
        testcase(ctx)
      }.toEither.left.toOption)

      Actor.stopped
    }

    val system = ActorSystem[Nothing](cluster, "actor-system")

    Await.result(system.whenTerminated, 20.seconds)

    res.get().foreach(throw _)
  }


  "leaders" should "send heartbeats regularly" in cluster { implicit ctx =>
    val follower = TestProbe[Raft.Message]("follower")
    val leader = ctx.spawn(Raft.leader(Set(follower.testActor), 1), "leader")

    leader ! Raft.HeartbeatTick

    follower.expectMsg(Raft.Heartbeat)
  }

  it should "vote for a new legitimate new leader" in cluster { implicit ctx =>
    val newCandidate = TestProbe[Raft.Message]("node")
    val newCandidateActor = newCandidate.testActor
    val oldLeader = ctx.spawn(Raft.leader(Set(newCandidateActor), 1), "oldLeader")

    oldLeader ! Raft.VoteRequest(newCandidateActor, term = 2)

    newCandidate.expectMsg(Raft.VoteResponse(2))
  }

  it should "ignore vote request for the current and older terms" in cluster { implicit ctx =>
    val oldCandidate = TestProbe[Raft.Message]("node")
    val newCandidateActor = oldCandidate.testActor
    val leader = ctx.spawn(Raft.leader(Set(newCandidateActor), currentTerm = 2), "leader")

    leader ! Raft.VoteRequest(newCandidateActor, term = 2)
    oldCandidate.expectNoMsg(20.milliseconds)

    leader ! Raft.VoteRequest(newCandidateActor, term = 1)
    oldCandidate.expectNoMsg(20.milliseconds)
  }


  "followers" should "send vote request if a timeout happens" in cluster { implicit ctx =>
    val node = TestProbe[Raft.Message]("node")
    val follower = ctx.spawn(Raft.follower(Set(node.testActor), 1, None), "follower")

    follower ! Raft.LeaderTimeout

    node.expectMsg(Raft.VoteRequest(follower, term = 2))
  }

  it should "vote for a legitimate new leader" in cluster { implicit ctx =>
    val newLeader = TestProbe[Raft.Message]("node")
    val newLeaderActor = newLeader.testActor
    val follower = ctx.spawn(Raft.follower(Set(newLeaderActor), 1, None), "follower")

    follower ! Raft.VoteRequest(newLeaderActor, term = 2)

    newLeader.expectMsg(Raft.VoteResponse(2))
  }

  it should "not vote for two different candidates during one term" in cluster { implicit ctx =>
    val newLeader = TestProbe[Raft.Message]("node")
    val newLeaderActor = newLeader.testActor
    val follower = ctx.spawn(Raft.follower(Set(newLeaderActor), 1, Some(ctx.system.deadLetters)), "follower")

    follower ! Raft.VoteRequest(newLeaderActor, term = 1)

    newLeader.expectNoMsg(20.milliseconds)
  }

  it should "not respond to vote requests from old terms" in cluster { implicit ctx =>
    val newLeader = TestProbe[Raft.Message]("node")
    val newLeaderActor = newLeader.testActor
    val follower = ctx.spawn(Raft.follower(Set(newLeaderActor), 2, None), "follower")

    follower ! Raft.VoteRequest(newLeaderActor, term = 1)

    newLeader.expectNoMsg(20.milliseconds)
  }

  "candidates" should "become leaders when receiving vote responses from the majority" in cluster { implicit ctx =>
    val follower = fiveProbes
    val followerActors = follower.map(_.testActor)
    val candidate = ctx.spawn(Raft.candidate(followerActors, 2), "candidate")

    followerActors.foreach { ref =>
      candidate ! Raft.VoteResponse(2)
    }

    follower.foreach { f =>
      f.expectMsg(Raft.VoteRequest(candidate, 2))
      f.expectMsg(Raft.Heartbeat)
    }
  }

  it should "not confuse vote response from different terms" in cluster { implicit ctx =>
    val follower = fiveProbes
    val followerActors = follower.map(_.testActor)
    val candidate = ctx.spawn(Raft.candidate(followerActors, 4), "candidate")

    candidate ! Raft.VoteResponse(1)
    candidate ! Raft.VoteResponse(2)
    candidate ! Raft.VoteResponse(3)
    candidate ! Raft.VoteResponse(4)

    follower.foreach { f =>
      f.expectMsg(Raft.VoteRequest(candidate, 4))
      f.expectNoMsg(20.milliseconds)
    }
  }

  it should "start a new term if no new leader was found" in cluster { implicit ctx =>
    val follower = fiveProbes
    val followerActors = follower.map(_.testActor)
    val candidate = ctx.spawn(Raft.candidate(followerActors, 4), "candidate")

    candidate ! Raft.VoteResponse(2)

    follower.foreach { f =>
      f.expectMsg(Raft.VoteRequest(candidate, 4))
      f.expectMsg(Raft.VoteRequest(candidate, 5))
    }
  }

  private def fiveProbes(implicit ctx: ActorContext[_]) =
    (1 to 4 map { _ => TestProbe[Raft.Message]("node") }).toSet

}
