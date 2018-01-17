package de.leanovate.raft

import akka.http.scaladsl.server.{Directives, Route}
import de.leanovate.raft.shared.SharedMessages
import de.leanovate.raft.twirl.Implicits._

object WebService extends Directives {

  val route: Route = {
    pathSingleSlash {
      get {
        complete {
          de.leanovate.raft.html.index.render(SharedMessages.itWorks)
        }
      }
    } ~
      pathPrefix("assets" / Remaining) { file =>
        // optionally compresses the response with Gzip or Deflate
        // if the client accepts compressed responses
        encodeResponse {
          getFromResource("public/" + file)
        }
      }
  }
}
