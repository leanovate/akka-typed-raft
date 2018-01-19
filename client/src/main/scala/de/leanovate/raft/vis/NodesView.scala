package de.leanovate.raft.vis

import diode._

import scalatags.JsDom
import scalatags.JsDom.all._

class NodesView(nodes: ModelRO[Set[String]], dispatch: Dispatcher) {
  def render: JsDom.Frag = {
    div(
      h2("Known nodes:"),
      nodes().toSeq.sorted.map { msg =>
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
