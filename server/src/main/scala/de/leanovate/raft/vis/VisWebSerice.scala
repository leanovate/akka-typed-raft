package de.leanovate.raft.vis

import akka.actor.ActorSystem
import akka.http.scaladsl.model.sse.ServerSentEvent
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream.{Materializer, OverflowStrategy}

import scala.concurrent.duration._

object VisWebSerice {

  def routes(implicit sys: ActorSystem, mat: Materializer): Route = {
    import akka.typed.scaladsl.adapter._

    val (queue, fanoutPublisher) = Source
      .queue[String](10, OverflowStrategy.dropHead)
      .toMat(Sink.asPublisher(fanout = true))(Keep.both)
      .run()

    sys.spawn[MonitoredNetwork.Messages](
      MonitoredNetwork.behaviour(str => queue.offer(str)),
      "MonitoredNetwork")

    // keep fanoutPublisher alive
    Source.fromPublisher(fanoutPublisher).runWith(Sink.ignore)

    pathPrefix("vis") {
      path("events") {
        get {
          import akka.http.scaladsl.marshalling.sse.EventStreamMarshalling._

          complete {
            Source
              .fromPublisher(fanoutPublisher)
              .map(time => ServerSentEvent(time.toString))
              .keepAlive(1.second, () => ServerSentEvent.heartbeat)
          }
        }
      } ~ pathEndOrSingleSlash {
        get {
          complete("see /vis/events for updates")
        }
      }
    }
  }
}
