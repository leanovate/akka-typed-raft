package de.leanovate.raft.vis

import diode.ModelRO

import scalatags.JsDom
import scalatags.JsDom.all._

class TimeView(time: ModelRO[Double]) {
  def render: JsDom.Frag =
    div("Time: ", time(), " seconds")
}
