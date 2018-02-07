package de.leanovate.raft.vis

import diode._

import scalatags.JsDom
import scalatags.JsDom.svgTags._
import scalatags.JsDom.all.{SeqFrag, _}
import scalatags.JsDom.svgAttrs.{style, _}

class NodesView(seconds: ModelRO[Double],
                nodes: ModelRO[Set[NodeName]],
                messages: ModelRO[Seq[NetworkEvent]],
                dispatch: Dispatcher) {

  def render: JsDom.Frag = {
    val sortedNodes = nodes().toSeq.sorted
    val nodePositions: Map[NodeName, (Double, Double)] =
      NodesView.nodePositions(sortedNodes)

    val currentTime = seconds()

    val messagePositions: Seq[(Double, Double)] = for {
      MessageSent(from, to, sendTime, receiveTime, _) <- messages()
      if sendTime < currentTime && currentTime < receiveTime
      n1 = nodePositions(from)
      n2 = nodePositions(to)
      relativeTime = NodesView.relativeTime(sendTime, currentTime, receiveTime)
    } yield NodesView.positionBetween(n1, n2, relativeTime)

    svg(
      viewBox := "-50 -50 100 100",
      style := "max-width: 500px",
      for {
        (node, (nx, ny)) <- nodePositions.toSeq
      } yield
        circle(cx := nx,
               cy := ny,
               r := 5,
               fill := "yellow",
               onmousedown := (() => dispatch(SelectNode(node)))),
      for {
        (x, y) <- messagePositions
      } yield circle(cx := x, cy := y, r := 2, fill := "orange")
    )
  }
}

object NodesView {

  def nodePositions[N](nodes: Iterable[N]): Map[N, (Double, Double)] =
    (for {
      (node, i) <- nodes.zipWithIndex
      p = i.toDouble / nodes.size * 2 * math.Pi
    } yield node -> (math.sin(p) * 40, math.cos(p) * 40)).toMap

  def relativeTime(startTime: Double,
                   currentTime: Double,
                   endTime: Double): Double =
    (currentTime - startTime) / (endTime - startTime)

  def positionBetween(n1: (Double, Double),
                      n2: (Double, Double),
                      weight: Double): (Double, Double) =
    (positionBetween(n1._1, n2._1, weight),
     positionBetween(n1._2, n2._2, weight))

  def positionBetween(n1: Double, n2: Double, weight: Double): Double =
    n1 * (1.0 - weight) + n2 * weight
}
