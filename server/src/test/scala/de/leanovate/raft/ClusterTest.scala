package de.leanovate.raft

import java.util.concurrent.atomic.AtomicReference

import akka.typed.{ActorRef, ActorSystem, Behavior}
import akka.typed.scaladsl.{Actor, ActorContext}
import akka.typed.testkit.TestKitSettings
import akka.typed.testkit.scaladsl.TestProbe

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Try

object ClusterTest {

  implicit def systemFromContext(
      implicit ctx: ActorContext[_]): ActorSystem[_] =
    ctx.system

  implicit def testKitSettingsFromContext(
      implicit ctx: ActorContext[_]): TestKitSettings =
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

  def spawn[U](behavior: Behavior[U])(implicit name: sourcecode.Name,
                                      ctx: ActorContext[_]): ActorRef[U] =
    ctx.spawn(behavior, name.value)

  def Probe[U](implicit name: sourcecode.Name,
               system: ActorSystem[_],
               settings: TestKitSettings): TestProbe[U] =
    TestProbe[U](name.value)
}
