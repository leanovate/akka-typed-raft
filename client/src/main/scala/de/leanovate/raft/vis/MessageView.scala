package de.leanovate.raft.vis

import diode._

import scalatags.JsDom
import scalatags.JsDom.all._

class MessageView(events: ModelRO[Seq[NetworkEvent]]) {
  def render: JsDom.Frag = {
    div(
      h2("Received messages:"),
      events().map { msg =>
        p(msg.toString)
      }
    )
  }
}
