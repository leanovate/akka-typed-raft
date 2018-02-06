package de.leanovate.raft.vis

import diode.{ModelR, ModelRO, UseValueEq}

import scalatags.JsDom
import scalatags.JsDom.all._

class NodeOverview(nodesView: ModelRO[Map[NodeName, String]]) {

  def render = div(
    nodesView().map {
      case (name, state) =>
        p(name.name, state)
    }.toSeq
  )

}
