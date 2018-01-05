package de.leanovate.raft.vis

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.sse.ServerSentEvent
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream.{ActorMaterializer, Materializer, OverflowStrategy}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.io.StdIn

object VisServer {

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
        complete("see /events for updates")
      }
    }
  }

  def main(args: Array[String]): Unit = {
    implicit val system: ActorSystem = ActorSystem("vis-system")
    implicit val materializer: Materializer = ActorMaterializer()
    // needed for the future flatMap/onComplete in the end
    implicit val executionContext: ExecutionContext = system.dispatcher

    val bindingFuture = Http().bindAndHandle(routes, "localhost", 8080)

    println(s"Server online at http://localhost:8080/\nPress RETURN to stop...")
    StdIn.readLine() // let it run until user presses return
    bindingFuture
      .flatMap(_.unbind()) // trigger unbinding from the port
      .onComplete(_ => system.terminate()) // and shutdown when done
  }
}
