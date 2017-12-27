import ClusterTest._
import Raft.{ClusterConfiguration, Message}
import akka.typed.{ActorRef, Behavior}
import akka.typed.scaladsl.{Actor, ActorContext, TimerScheduler}
import akka.typed.testkit.scaladsl.TestProbe
import org.scalacheck.Gen
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.duration.{DurationInt, FiniteDuration}

class RaftTest extends FlatSpec with Matchers with GeneratorDrivenPropertyChecks {


  private val leaderHeartbeat = 50.milliseconds
  private val followerTimeout = 100.milliseconds -> 200.milliseconds
  private val (minimalFollowerTimeout, maximalFollowerTimeout) = followerTimeout
  private val candidateTimeout = 70.milliseconds.ensuring(_ < minimalFollowerTimeout)
  private val shortTime: FiniteDuration = 10.milliseconds

  def testConfiguration(nodes: Set[ActorRef[Message]],
                        timer: TimerScheduler[Message]) = new ClusterConfiguration(nodes, timer, leaderHeartbeat, followerTimeout, candidateTimeout)

  def newLeader(nodes: Set[ActorRef[Message]], currentTerm: Int): Behavior[Message] = Actor.withTimers { timer =>
    testConfiguration(nodes, timer).leader(currentTerm)
  }

  def newFollower(nodes: Set[ActorRef[Message]], currentTerm: Int, votedFor: Option[ActorRef[Message]]): Behavior[Message] = Actor.withTimers { timer =>
    testConfiguration(nodes, timer).follower(currentTerm, votedFor)
  }

  def newCandidate(nodes: Set[ActorRef[Message]], currentTerm: Int): Behavior[Message] = Actor.withTimers { timer =>
    testConfiguration(nodes, timer).candidate(currentTerm)
  }

  "leaders" should "send heartbeats regularly" in cluster { implicit ctx =>
    val follower = TestProbe[Raft.Message]("follower")
    val leader = ctx.spawn(newLeader(Set(follower.ref), 1), "leader")

    leader ! Raft.HeartbeatTick

    follower.expectMsg(Raft.Heartbeat(1))
  }

  it should "vote for a new legitimate new leader" in cluster { implicit ctx =>
    val newCandidate = TestProbe[Raft.Message]("node")
    val oldLeader = ctx.spawn(newLeader(Set(newCandidate.ref), 1), "oldLeader")

    oldLeader ! Raft.VoteRequest(newCandidate.ref, term = 2)

    newCandidate.expectMsg(Raft.VoteResponse(2))
  }

  it should "ignore vote request for the current and older terms" in cluster { implicit ctx =>
    val oldCandidate = TestProbe[Raft.Message]("node")
    val leader = ctx.spawn(newLeader(Set(oldCandidate.ref), currentTerm = 2), "leader")

    leader ! Raft.VoteRequest(oldCandidate.ref, term = 2)
    oldCandidate.expectNoMsg(shortTime)

    leader ! Raft.VoteRequest(oldCandidate.ref, term = 1)
    oldCandidate.expectNoMsg(shortTime)
  }


  "followers" should "send vote request if a timeout happens" in cluster { implicit ctx =>
    val node = TestProbe[Raft.Message]("node")
    val follower = ctx.spawn(newFollower(Set(node.ref), 1, None), "follower")

    node.expectMsg(maximalFollowerTimeout * 2, Raft.VoteRequest(follower, term = 2))
  }

  it should "generate random timeouts in" in {
    withClue("this test has the very unlikely random chance to fail") {
      val config = testConfiguration(Set.empty, null)
      val samples = Array.fill(2000)(config.randomFollowerTimeout())

      samples.min should be >= minimalFollowerTimeout
      samples.max should be <= maximalFollowerTimeout

      (samples.max - samples.min) should be >= (maximalFollowerTimeout - minimalFollowerTimeout) * 0.8
    }
  }

  it should "ignore heartbeats from previous leaders" in cluster { implicit ctx =>
    val node = TestProbe[Raft.Message]("node")
    val follower = ctx.spawn(newFollower(Set(node.ref), 1, None), "follower")

    ctx.spawn(Actor.withTimers[Unit] { timer =>
      timer.startPeriodicTimer("", (), leaderHeartbeat)
      Actor.immutable { (_, _) =>
        follower ! Raft.Heartbeat(0)
        Actor.same
      }
    }, "oldLeader")


    node.expectMsg(maximalFollowerTimeout * 2, Raft.VoteRequest(follower, term = 2))
  }

  it should "update its term when receiving a heartbeat with newer term number" in cluster { implicit ctx =>
    val otherNode = TestProbe[Raft.Message]("node")
    val follower = ctx.spawn(newFollower(Set(otherNode.ref), 1, None), "follower")

    follower ! Raft.Heartbeat(term = 3)

    otherNode.expectMsg(maximalFollowerTimeout * 2, Raft.VoteRequest(follower, 4))
  }

  it should "restart its timer after being a candidate and reverting back to follower" in cluster { implicit ctx =>
    val otherNode = TestProbe[Raft.Message]("node")
    val follower = ctx.spawn(newFollower(Set(otherNode.ref), 1, None), "follower")

    follower ! Raft.Heartbeat(term = 3)

    otherNode.expectMsg(maximalFollowerTimeout * 2, Raft.VoteRequest(follower, 4))

    follower ! Raft.Heartbeat(term = 5)

    otherNode.expectMsg(maximalFollowerTimeout * 2, Raft.VoteRequest(follower, 6))
  }

  it should "vote for a legitimate new leader" in cluster { implicit ctx =>
    val newLeader = TestProbe[Raft.Message]("node")
    val follower = ctx.spawn(newFollower(Set(newLeader.ref), 1, None), "follower")

    follower ! Raft.VoteRequest(newLeader.ref, term = 2)

    newLeader.expectMsg(Raft.VoteResponse(2))
  }

  it should "not vote for two different candidates during one term" in cluster { implicit ctx =>
    val newLeader = TestProbe[Raft.Message]("node")
    val follower = ctx.spawn(newFollower(Set(newLeader.ref), 1, Some(ctx.system.deadLetters)), "follower")

    follower ! Raft.VoteRequest(newLeader.ref, term = 1)

    newLeader.expectNoMsg(shortTime)
  }

  it should "not respond to vote requests from old terms" in cluster { implicit ctx =>
    val newLeaderPrope = TestProbe[Raft.Message]("newLeader")
    val follower = ctx.spawn(newFollower(Set(newLeaderPrope.ref), 2, None), "follower")

    follower ! Raft.VoteRequest(newLeaderPrope.ref, term = 1)

    newLeaderPrope.expectNoMsg(shortTime)
  }

  "candidates" should "become leaders when receiving vote responses from the majority" in cluster { implicit ctx =>
    val follower = fiveProbes
    val followerActors = follower.map(_.ref)
    val candidate = ctx.spawn(newCandidate(followerActors, 2), "candidate")

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
    val followerActors = follower.map(_.ref)
    val candidate = ctx.spawn(newCandidate(followerActors, 4), "candidate")

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
      f.expectNoMsg(shortTime)
    }
  }

  it should "start a new term if no new leader was found" in cluster { implicit ctx =>
    val follower = fiveProbes
    val followerActors = follower.map(_.ref)
    val candidate = ctx.spawn(newCandidate(followerActors, 4), "candidate")

    candidate ! Raft.VoteResponse(2)

    follower.foreach { f =>
      f.expectMsg(Raft.VoteRequest(candidate, 4))
      f.expectMsg(Raft.VoteRequest(candidate, 5))
    }
  }

  it should "become a follower if a heartbeat is received" in cluster { implicit ctx =>
    val newLeader = TestProbe[Raft.Message]("newLeader")
    val follower = fiveProbes
    val followerActors = follower.map(_.ref)
    val candidate = ctx.spawn(newCandidate(followerActors, 2), "candidate")

    follower.foreach {
      _.expectMsg(Raft.VoteRequest(candidate, 2))
    }

    candidate ! Raft.Heartbeat(2)

    follower.par.foreach { f =>
      f.expectNoMsg(candidateTimeout)
      f.expectMsg(maximalFollowerTimeout, Raft.VoteRequest(candidate, 3))
    }
  }

  it should "become a follower if a vote request with a more recent term is received" in cluster { implicit ctx =>
    val newLeader = TestProbe[Raft.Message]("newLeader")
    val follower = fiveProbes
    val followerActors = follower.map(_.ref)
    val candidate = ctx.spawn(newCandidate(followerActors, 2), "candidate")

    follower.foreach {
      _.expectMsg(Raft.VoteRequest(candidate, 2))
    }

    candidate ! Raft.VoteRequest(newLeader.ref, 3)

    follower.foreach {
      _.expectMsg(Raft.VoteRequest(candidate, 4))
    }
  }

  it should "ignore old heartbeats" in cluster { implicit ctx =>
    val newLeader = TestProbe[Raft.Message]("newLeader")
    val follower = fiveProbes
    val followerActors = follower.map(_.ref)
    val candidate = ctx.spawn(newCandidate(followerActors, 2), "candidate")

    candidate ! Raft.Heartbeat(1)

    follower.foreach { f =>
      f.expectMsg(Raft.VoteRequest(candidate, 2))
      f.expectMsg(Raft.VoteRequest(candidate, 3))
    }
  }

  "minimal majority" should "be greater than the total opposition" in {
    forAll(Gen.posNum[Int]) { clusterSize =>
      val majority = Raft.minimalMajority(clusterSize)
      val opposition = clusterSize - majority

      majority should be > opposition
    }
  }

  it should "be a minority with one vote less" in {
    forAll(Gen.posNum[Int]) { clusterSize =>
      val minority = Raft.minimalMajority(clusterSize) - 1
      val opposition = clusterSize - minority

      minority should be <= opposition
    }
  }

  private def fiveProbes(implicit ctx: ActorContext[_]) =
    (1 to 4 map { _ => TestProbe[Raft.Message]("node") }).toSet

}
