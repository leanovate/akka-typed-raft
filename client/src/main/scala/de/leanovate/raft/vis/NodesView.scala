package de.leanovate.raft.vis

import diode._

import scalatags.JsDom
import scalatags.JsDom.svgTags._
import scalatags.JsDom.all.{SeqFrag, _}
import scalatags.JsDom.svgAttrs.{style, _}


class NodesView(nodes: ModelRO[Set[String]], messsages: ModelRO[Seq[NetworkEvent]]) {


  def render: JsDom.Frag = {
    val sortedNodes = nodes().toSeq.sorted
    val nodePositions: Map[String, (Double, Double)] = (for {
      (node, i) <- sortedNodes.zipWithIndex
      p = i.toDouble / sortedNodes.length * 2 * math.Pi
    } yield node -> (math.sin(p) * 40, math.cos(p) * 40)).toMap

    val messagePositions: Seq[(Double, Double)] = for {
      MessageSent(from, to, _, _) <- messsages()
      (x1, y1) = nodePositions(from)
      (x2, y2) = nodePositions(to)
    } yield ((x1 + x2) / 2, (y1 + y2) / 2)

    svg(viewBox := "-50 -50 100 100", style := "max-width: 500px",
      for {
        (node, (nx, ny)) <- nodePositions.toSeq
      } yield circle(cx := nx, cy := ny, r := 5, fill := "yellow"),
      for {
        (x, y) <- messagePositions
      } yield circle(cx := x, cy := y, r := 2, fill := "orange")
    )
  }
}
