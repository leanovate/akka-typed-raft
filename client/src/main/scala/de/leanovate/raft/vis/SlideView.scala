package de.leanovate.raft.vis

import diode.ModelRO

import scalatags.JsDom
import scalatags.JsDom.all._

class SlideView(runningSince: ModelRO[Double]) {
  def render: JsDom.Frag = {
    div(
      "Running since ",
      runningSince(),
      " seconds"
    )
  }
}
