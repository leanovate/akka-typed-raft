package de.leanovate.raft

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives
import akka.stream.{ActorMaterializer, Materializer}
import com.typesafe.config.ConfigFactory
import de.leanovate.raft.vis.VisWebSerice

import scala.concurrent.ExecutionContext

object WebServer extends Directives {
  def main(args: Array[String]): Unit = {
    implicit val system: ActorSystem = ActorSystem("server-system")
    implicit val materializer: Materializer = ActorMaterializer()
    implicit val ec: ExecutionContext = system.dispatcher

    val config = ConfigFactory.load()
    val interface = config.getString("http.interface")
    val port = config.getInt("http.port")

    val bindingFuture = Http().bindAndHandle(
      VisWebSerice.routes ~ WebService.route,
      interface,
      port)

    println(s"Server online at http://$interface:$port")
  }
}
