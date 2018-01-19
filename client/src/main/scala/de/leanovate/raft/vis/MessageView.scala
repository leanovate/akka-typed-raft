package de.leanovate.raft.vis

import diode._

import scalatags.JsDom
import scalatags.JsDom.all._

/**
  * Counter view renders the counter value and provides interaction through
  * various buttons affecting the counter value.
  *
  * @param messages Model reader for the counter value
  * @param dispatch Dispatcher
  */
class MessageView(messages: ModelRO[Seq[String]], dispatch: Dispatcher) {
  def render: JsDom.Frag = {
    div(
      h2("Received messages:"),
      messages().map { msg =>
        p(msg)
      },
      div(
        button(cls := "btn btn-default",
               onclick := (() => dispatch(Reset)),
               "Reset")
      )
    )
  }
}
