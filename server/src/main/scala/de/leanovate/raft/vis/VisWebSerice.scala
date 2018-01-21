package de.leanovate.raft.vis

import akka.actor.ActorSystem
import akka.http.scaladsl.model.sse.ServerSentEvent
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.Materializer
import upickle.default.write

import scala.concurrent.duration._

object VisWebSerice {

  def routes(implicit sys: ActorSystem, mat: Materializer): Route = {
    import akka.typed.scaladsl.adapter._

    val store = new MemoryStore[NetworkEvent]

    sys.spawn[MonitoredNetwork.Messages](
      MonitoredNetwork.behaviour(store.append),
      "MonitoredNetwork")

    pathPrefix("vis") {
      path("events") {
        get {
          import akka.http.scaladsl.marshalling.sse.EventStreamMarshalling._

          complete {
            store.out
              .map(event => ServerSentEvent(write(event)))
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
