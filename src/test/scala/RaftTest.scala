import ClusterTest._
import akka.typed.scaladsl.{Actor, ActorContext}
import akka.typed.testkit.scaladsl.TestProbe
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.duration.DurationInt

class RaftTest extends FlatSpec with Matchers {

  "leaders" should "send heartbeats regularly" in cluster { implicit ctx =>
    val follower = TestProbe[Raft.Message]("follower")
    val leader = ctx.spawn(Raft.leader(Set(follower.testActor), 1), "leader")

    leader ! Raft.HeartbeatTick

    follower.expectMsg(Raft.Heartbeat(1))
  }

  it should "vote for a new legitimate new leader" in cluster { implicit ctx =>
    val newCandidate = TestProbe[Raft.Message]("node")
    val oldLeader = ctx.spawn(Raft.leader(Set(newCandidate.testActor), 1), "oldLeader")

    oldLeader ! Raft.VoteRequest(newCandidate.testActor, term = 2)

    newCandidate.expectMsg(Raft.VoteResponse(2))
  }

  it should "ignore vote request for the current and older terms" in cluster { implicit ctx =>
    val oldCandidate = TestProbe[Raft.Message]("node")
    val leader = ctx.spawn(Raft.leader(Set(oldCandidate.testActor), currentTerm = 2), "leader")

    leader ! Raft.VoteRequest(oldCandidate.testActor, term = 2)
    oldCandidate.expectNoMsg(20.milliseconds)

    leader ! Raft.VoteRequest(oldCandidate.testActor, term = 1)
    oldCandidate.expectNoMsg(20.milliseconds)
  }


  "followers" should "send vote request if a timeout happens" in cluster { implicit ctx =>
    val node = TestProbe[Raft.Message]("node")
    val follower = ctx.spawn(Raft.follower(Set(node.testActor), 1, None), "follower")

    node.expectMsg(500.milliseconds, Raft.VoteRequest(follower, term = 2))
  }

  it should "ignore heartbeats from previous leaders" in cluster { implicit ctx =>
    val node = TestProbe[Raft.Message]("node")
    val follower = ctx.spawn(Raft.follower(Set(node.testActor), 1, None), "follower")

    ctx.spawn(Actor.withTimers[Unit] { timer =>
      timer.startPeriodicTimer("", (), 200.milliseconds)
      Actor.immutable { (_, _) =>
        follower ! Raft.Heartbeat(0)
        Actor.same
      }
    }, "oldLeader")


    node.expectMsg(500.milliseconds, Raft.VoteRequest(follower, term = 2))
  }

  it should "vote for a legitimate new leader" in cluster { implicit ctx =>
    val newLeader = TestProbe[Raft.Message]("node")
    val follower = ctx.spawn(Raft.follower(Set(newLeader.testActor), 1, None), "follower")

    follower ! Raft.VoteRequest(newLeader.testActor, term = 2)

    newLeader.expectMsg(Raft.VoteResponse(2))
  }

  it should "not vote for two different candidates during one term" in cluster { implicit ctx =>
    val newLeader = TestProbe[Raft.Message]("node")
    val follower = ctx.spawn(Raft.follower(Set(newLeader.testActor), 1, Some(ctx.system.deadLetters)), "follower")

    follower ! Raft.VoteRequest(newLeader.testActor, term = 1)

    newLeader.expectNoMsg(20.milliseconds)
  }

  it should "not respond to vote requests from old terms" in cluster { implicit ctx =>
    val newLeader = TestProbe[Raft.Message]("newLeader")
    val follower = ctx.spawn(Raft.follower(Set(newLeader.testActor), 2, None), "follower")

    follower ! Raft.VoteRequest(newLeader.testActor, term = 1)

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
      f.expectMsg(Raft.Heartbeat(2))
    }
  }

  it should "not confuse vote responses from different terms" in cluster { implicit ctx =>
    val follower = fiveProbes
    val followerActors = follower.map(_.testActor)
    val candidate = ctx.spawn(Raft.candidate(followerActors, 4), "candidate")

    candidate ! Raft.VoteResponse(1)
    candidate ! Raft.VoteResponse(2)
    candidate ! Raft.VoteResponse(3)
    candidate ! Raft.VoteResponse(5)
    candidate ! Raft.VoteResponse(6)
    candidate ! Raft.VoteResponse(7)
    candidate ! Raft.VoteResponse(7)
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

  it should "become follower if a heartbeat is received" in cluster { implicit ctx =>
    val newLeader = TestProbe[Raft.Message]("newLeader")
    val follower = fiveProbes
    val followerActors = follower.map(_.testActor)
    val candidate = ctx.spawn(Raft.candidate(followerActors, 2), "candidate")

    candidate ! Raft.Heartbeat(2)

    follower.foreach { f =>
      f.expectMsg(Raft.VoteRequest(candidate, 2))
      f.expectNoMsg(50.milliseconds)
    }
  }

  it should "ignore old heartbeats" in cluster { implicit ctx =>
    val newLeader = TestProbe[Raft.Message]("newLeader")
    val follower = fiveProbes
    val followerActors = follower.map(_.testActor)
    val candidate = ctx.spawn(Raft.candidate(followerActors, 2), "candidate")

    candidate ! Raft.Heartbeat(1)

    follower.foreach { f =>
      f.expectMsg(Raft.VoteRequest(candidate, 2))
      f.expectMsg(Raft.VoteRequest(candidate, 3))
    }
  }

  private def fiveProbes(implicit ctx: ActorContext[_]) =
    (1 to 4 map { _ => TestProbe[Raft.Message]("node") }).toSet

}
