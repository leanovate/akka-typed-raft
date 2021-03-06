package de.leanovate.raft

import akka.typed.scaladsl.ActorContext
import akka.typed.testkit.scaladsl.TestProbe
import akka.typed.{ActorRef, Behavior}
import de.leanovate.raft.ClusterTest._
import de.leanovate.raft.Raft._
import org.scalacheck.Gen
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.duration.{DurationInt, FiniteDuration}

class RaftTest
    extends FlatSpec
    with Matchers
    with ScalaFutures
    with GeneratorDrivenPropertyChecks {

  private val leaderHeartbeat = 50.milliseconds
  private val followerTimeout = 100.milliseconds -> 200.milliseconds
  private val (minimalFollowerTimeout, maximalFollowerTimeout) = followerTimeout
  private val candidateTimeout = 70.milliseconds
    .ensuring(_ < minimalFollowerTimeout)
  private val shortTime: FiniteDuration = 10.milliseconds

  def testConfiguration(nodes: Set[Ambassador])(implicit ctx: ActorContext[_]) =
    ClusterConfiguration(nodes,
                         ctx.system.deadLetters,
                         leaderHeartbeat,
                         followerTimeout,
                         candidateTimeout)

  def newLeader(nodes: Set[Ambassador], currentTerm: Int)(
      implicit ctx: ActorContext[_]): Behavior[In.PrivateMessage] =
    Raft.startAsLeader(currentTerm)(testConfiguration(nodes))

  def newFollower(nodes: Set[Ambassador],
                  currentTerm: Int,
                  votedFor: Option[Ambassador] = None,
                  currentLeader: Option[Ambassador] = None)(
      implicit ctx: ActorContext[_]): Behavior[In.PrivateMessage] =
    Raft.startAsFollower(
      currentTerm,
      votedFor = votedFor,
      currentLeader = currentLeader)(testConfiguration(nodes))

  def newCandidate(nodes: Set[Ambassador], currentTerm: Int)(
      implicit ctx: ActorContext[_]): Behavior[In.PrivateMessage] =
    Raft.startAsCandidate(currentTerm)(testConfiguration(nodes))

  "leaders" should "send heartbeats regularly" in cluster { implicit ctx =>
    val follower = Probe[Out.Message]
    val leader = spawn(newLeader(Set(follower.ref), currentTerm = 1))

    leader ! Raft.In.HeartbeatTick

    follower.expectMsg(Raft.Out.Heartbeat(1, AppendEntriesCommand()))
  }

  it should "vote for a new legitimate new leader" in cluster { implicit ctx =>
    val newCandidate = Probe[Raft.Out.Message]
    val oldLeader = spawn(newLeader(Set(newCandidate.ref), currentTerm = 1))

    oldLeader ! Raft.In.VoteRequest(newCandidate.ref, term = 2)

    newCandidate.expectMsg(Raft.Out.VoteResponse(2))
  }

  it should "ignore vote request for the current and older terms" in cluster {
    implicit ctx =>
      val oldCandidate = Probe[Raft.Out.Message]
      val leader = spawn(newLeader(Set(oldCandidate.ref), currentTerm = 2))

      leader ! Raft.In.VoteRequest(oldCandidate.ref, term = 2)
      oldCandidate.expectNoMsg(shortTime)

      leader ! Raft.In.VoteRequest(oldCandidate.ref, term = 1)
      oldCandidate.expectNoMsg(shortTime)

      oldCandidate.expectMsg(Raft.Out.Heartbeat(term = 2, AppendEntriesCommand()))
  }

  "followers" should "send vote request if a timeout happens" in cluster {
    implicit ctx =>
      val node = Probe[Raft.Out.Message]
      ctx.spawn(newFollower(Set(node.ref), 1), "follower")

      node.expectMsg(maximalFollowerTimeout * 2, Raft.Out.VoteRequest(term = 2))
  }

  it should "generate random timeouts in" in cluster { implicit ctx =>
    val config = testConfiguration(Set.empty)
    val samples = Array.fill(2000)(Raft.randomFollowerTimeout()(config))

    samples.min should be >= minimalFollowerTimeout
    samples.max should be <= maximalFollowerTimeout

    withClue("this test has the very unlikely random chance to fail") {
      (samples.max - samples.min) should be >= (maximalFollowerTimeout - minimalFollowerTimeout) * 0.8
    }
  }

  it should "ignore heartbeats from previous leaders" in cluster {
    implicit ctx =>
      val oldLeader = Probe[Out.Message]
      val follower = spawn(newFollower(Set(oldLeader.ref), 1))

      ctx.schedule(1 * leaderHeartbeat,
                   follower,
                   Raft.In.Heartbeat(oldLeader.ref, 0, AppendEntriesCommand()))
      ctx.schedule(2 * leaderHeartbeat,
                   follower,
                   Raft.In.Heartbeat(oldLeader.ref, 0, AppendEntriesCommand()))
      ctx.schedule(3 * leaderHeartbeat,
                   follower,
                   Raft.In.Heartbeat(oldLeader.ref, 0, AppendEntriesCommand()))

      oldLeader.expectMsg(maximalFollowerTimeout * 2,
                          Raft.Out.VoteRequest(term = 2))
  }

  it should "update its term when receiving a heartbeat with newer term number" in cluster {
    implicit ctx =>
      val otherNode = Probe[Raft.Out.Message]
      val newLeader = Probe[Raft.Out.Message]
      val follower = spawn(newFollower(Set(otherNode.ref), 1))

      follower ! Raft.In.Heartbeat(newLeader.ref, term = 3, AppendEntriesCommand())

      otherNode.expectMsg(maximalFollowerTimeout * 2,
                          Raft.Out.VoteRequest(term = 4))
  }

  it should "restart its timer after being a candidate and reverting back to follower" in cluster {
    implicit ctx =>
      val otherNode = Probe[Raft.Out.Message]
      val sporadicLeader = Probe[Raft.Out.Message]
      val follower = spawn(newFollower(Set(otherNode.ref), 1))

      follower ! Raft.In.Heartbeat(sporadicLeader.ref, term = 3, AppendEntriesCommand())

      otherNode.expectMsg(maximalFollowerTimeout * 2,
                          Raft.Out.VoteRequest(term = 4))

      follower ! Raft.In.Heartbeat(sporadicLeader.ref, term = 5, AppendEntriesCommand())

      otherNode.expectMsg(maximalFollowerTimeout * 2,
                          Raft.Out.VoteRequest(term = 6))
  }

  it should "vote for a legitimate new leader" in cluster { implicit ctx =>
    val newLeader = Probe[Raft.Out.Message]
    val follower = spawn(newFollower(Set(newLeader.ref), 1))

    follower ! Raft.In.VoteRequest(newLeader.ref, term = 2)

    newLeader.expectMsg(Raft.Out.VoteResponse(2))
  }

  it should "not vote for two different candidates during one term" in cluster {
    implicit ctx =>
      val newLeader = Probe[Raft.Out.Message]
      val follower =
        spawn(newFollower(Set(newLeader.ref), 1, Some(ctx.system.deadLetters)))

      follower ! Raft.In.VoteRequest(newLeader.ref, term = 1)

      newLeader.expectNoMsg(shortTime)
  }

  it should "not respond to vote requests from old terms" in cluster {
    implicit ctx =>
      val newLeaderProbe = Probe[Raft.Out.Message]
      val follower = spawn(newFollower(Set(newLeaderProbe.ref), 2))

      follower ! Raft.In.VoteRequest(newLeaderProbe.ref, term = 1)

      newLeaderProbe.expectNoMsg(shortTime)
  }

  ignore should "ignore all vote responses" in cluster { implicit ctx =>
    // how could this be tested?
    fail()
  }

  it should "respond with the current leader on commands" in cluster {
    implicit ctx =>
      val leader = Probe[Raft.Out.Message]

      val follower =
        spawn(newFollower(Set(leader.ref), 1, currentLeader = Some(leader.ref)))

      val client = Probe[Either[ActorRef[Raft.Out.Message], Unit]]

      follower ! Raft.In.Heartbeat(leader.ref, 1, AppendEntriesCommand())
      follower ! Raft.In.Command(client.ref)

      client.expectMsg(Left(leader.ref))
  }

  "candidates" should "become leaders when receiving vote responses from the majority" in cluster {
    implicit ctx =>
      val follower = fiveProbes
      val followerActors = follower.map(_.ref)
      val candidate = spawn(newCandidate(followerActors, 2))

      followerActors.foreach { _ =>
        candidate ! Raft.In.VoteResponse(2)
      }

      follower.foreach { f =>
        f.expectMsg(Raft.Out.VoteRequest(term = 2))
        f.expectMsg(Raft.Out.Heartbeat(2, AppendEntriesCommand()))
      }
  }

  it should "not confuse vote responses from different terms" in cluster {
    implicit ctx =>
      val follower = fiveProbes
      val followerActors = follower.map(_.ref)
      val candidate = spawn(newCandidate(followerActors, 4))

      candidate ! Raft.In.VoteResponse(1)
      candidate ! Raft.In.VoteResponse(2)
      candidate ! Raft.In.VoteResponse(3)
      candidate ! Raft.In.VoteResponse(5)
      candidate ! Raft.In.VoteResponse(6)
      candidate ! Raft.In.VoteResponse(7)
      candidate ! Raft.In.VoteResponse(7)
      candidate ! Raft.In.VoteResponse(4)

      follower.foreach { f =>
        f.expectMsg(Raft.Out.VoteRequest(term = 4))
        f.expectNoMsg(shortTime)
      }
  }

  it should "start a new term if no new leader was found" in cluster {
    implicit ctx =>
      val follower = fiveProbes
      val followerActors = follower.map(_.ref)
      val candidate = spawn(newCandidate(followerActors, 4))

      candidate ! Raft.In.VoteResponse(2)

      follower.foreach { f =>
        f.expectMsg(Raft.Out.VoteRequest(term = 4))
        f.expectMsg(Raft.Out.VoteRequest(term = 5))
      }
  }

  it should "become a follower if a heartbeat is received" in cluster {
    implicit ctx =>
      val follower = Probe[Raft.Out.Message]
      val candidate = spawn(newCandidate(Set(follower.ref), 2))

      candidate ! Raft.In.Heartbeat(follower.ref, 2, AppendEntriesCommand())

      follower.expectMsg(Raft.Out.VoteRequest(term = 2))

      follower.expectNoMsg(candidateTimeout)
      follower.expectMsg(maximalFollowerTimeout, Raft.Out.VoteRequest(term = 3))
  }

  it should "become a follower if a vote request with a more recent term is received" in cluster {
    implicit ctx =>
      val newLeader = Probe[Raft.Out.Message]
      val follower = fiveProbes
      val followerActors = follower.map(_.ref)
      val candidate = spawn(newCandidate(followerActors, 2))

      follower.foreach {
        _.expectMsg(Raft.Out.VoteRequest(term = 2))
      }

      candidate ! Raft.In.VoteRequest(newLeader.ref, 3)

      follower.foreach {
        _.expectMsg(Raft.Out.VoteRequest(term = 4))
      }
  }

  it should "ignore old heartbeats" in cluster { implicit ctx =>
    val follower = fiveProbes
    val followerActors = follower.map(_.ref)
    val candidate = spawn(newCandidate(followerActors, 2))

    candidate ! Raft.In.Heartbeat(followerActors.head, 1, AppendEntriesCommand())

    follower.foreach { f =>
      f.expectMsg(Raft.Out.VoteRequest(term = 2))
      f.expectMsg(Raft.Out.VoteRequest(term = 3))
    }
  }

  it should "stash commands until being follower" in cluster { implicit ctx =>
    val upcomingLeader = Probe[Out.Message]
    val candidate = spawn(newCandidate(Set(upcomingLeader.ref), 1))
    val client = Probe[Either[ActorRef[Out.Message], Unit]]

    candidate ! In.Command(client.ref)

    candidate ! In.Heartbeat(upcomingLeader.ref, 1, AppendEntriesCommand())

    client.expectMsg(Left(upcomingLeader.ref))
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
    (1 to 4 map { i =>
      TestProbe[Raft.Out.Message]("node" ++ i.toString)
    }).toSet

}
