package de.leanovate.raft

import akka.typed.testkit.scaladsl.TestProbe
import de.leanovate.raft.ClusterTest._
import org.scalatest.{FlatSpec, Matchers}

class RegistryTest extends FlatSpec with Matchers {

  "registry" should "initially return 0" in cluster { implicit ctx =>
    val reg = ctx.spawn(Registry.behaviour, "registry")
    val receiver = TestProbe[Option[Int]]("receiver")

    reg ! Registry.Get('x, receiver.testActor)

    receiver.expectMsg(None)
  }

  it should "return the last write" in cluster { implicit ctx =>
    val reg = ctx.spawn(Registry.behaviour, "registry")
    val receiver = TestProbe[Option[Int]]("receiver")

    reg ! Registry.Set('x, 1, ctx.system.deadLetters)
    reg ! Registry.Set('x, 2, ctx.system.deadLetters)

    reg ! Registry.Get('x, receiver.testActor)

    receiver.expectMsg(Some(2))
  }

  it should "return nothing after a delete" in cluster { implicit ctx =>
    val reg = ctx.spawn(Registry.behaviour, "registry")
    val receiver = TestProbe[Option[Int]]("receiver")

    reg ! Registry.Set('x, 1, ctx.system.deadLetters)
    reg ! Registry.Delete('x, ctx.system.deadLetters)

    reg ! Registry.Get('x, receiver.testActor)

    receiver.expectMsg(None)
  }

}
