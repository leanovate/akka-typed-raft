package de.leanovate.raft.vis

import de.leanovate.raft.vis.AppCircuit.updateTime
import org.scalatest.FlatSpec
import org.scalatest.Matchers

class AppCircuitTest extends FlatSpec with Matchers {

  "UpdateTimer" should "use the time given by events" in {
    val givenTime = 1200.0

    val newTime =
      updateTime(NewEvent(
                   MessageSent(NodeName(""),
                               NodeName(""),
                               givenTime,
                               1300.0,
                               Map.empty)),
                 0)

    newTime should be(givenTime)
  }

  it should "increment the time every tick" in {
    updateTime(Tick, 0) should be(0.02 +- 0.001)
  }

}
