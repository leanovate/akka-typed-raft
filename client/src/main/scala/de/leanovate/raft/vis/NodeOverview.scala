package de.leanovate.raft.vis

import diode.ModelRO

import scalatags.JsDom.all._

class NodeOverview(selected: ModelRO[Option[NodeName]],
                   nodesView: ModelRO[Map[NodeName, String]]) {

  def render =
    selected()
      .fold(nothingSelected)(node => showNode(node, nodesView()(node)))

  private def nothingSelected = div(
    h3("Nothing selected"),
    p("Click on a node to see the current state")
  )

  private def showNode(name: NodeName, state: String) = div(
    h3(name.name),
    p(state)
  )

}
