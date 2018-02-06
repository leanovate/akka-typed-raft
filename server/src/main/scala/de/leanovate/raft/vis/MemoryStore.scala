package de.leanovate.raft.vis

import java.util.concurrent.atomic.AtomicReference

import akka.NotUsed
import akka.stream.scaladsl.Source
import scala.concurrent.ExecutionContext.Implicits.global

import scala.concurrent.{Future, Promise}

private[vis] class MemoryStore[E] {

  def append(e: E): Unit = {
    val newLast = Promise[(Promise[_], E)]
    val oldLast = last.getAndSet(newLast)
    oldLast.success(newLast -> e)
  }

  def out: Source[E, NotUsed] = Source.unfoldAsync(first) { future =>
    future.map {
      case (pro, e) =>
        Some(pro.future.asInstanceOf[Future[(Promise[_], E)]] -> e)
    }
  }

  @volatile
  private var last = new AtomicReference(Promise[(Promise[_], E)]())

  private val first = last.get().future
}
