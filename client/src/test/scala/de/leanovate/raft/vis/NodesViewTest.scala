package de.leanovate.raft.vis

import de.leanovate.raft.vis.NodesView._
import org.scalacheck.Gen
import org.scalatest.prop.PropertyChecks
import org.scalatest.{FlatSpec, Matchers}

class NodesViewTest extends FlatSpec with Matchers with PropertyChecks {
  "all nodes" should "lie on a circle" in {
    forAll { nodes: Set[String] =>
      whenever(nodes.nonEmpty) {
        val positions = nodePositions(nodes)

        val distances = positions.values.map {
          case (x, y) => (x * x) + (y * y)
        }

        val first: Double = distances.head

        all(distances) should be(first +- first * 0.001)
      }
    }
  }

  it should "have a position" in {
    forAll { nodes: Set[String] =>
      nodePositions(nodes).keys should be(nodes)
    }
  }

  "relative time" should "be 0 in the beginning" in {
    forAll(startAndEnd) {
      case (start, end) =>
        relativeTime(start, start, end) should be(0.0 +- 0.001)
    }
  }

  it should "be 1.0 in the end" in {
    forAll(startAndEnd) {
      case (start, end) =>
        relativeTime(start, end, end) should be(1.0 +- 0.001)
    }
  }

  it should "be 0.5 in the middle" in {
    forAll(startAndEnd) {
      case (start, end) =>
        relativeTime(start, (start + end) / 2, end) should be(0.5 +- 0.001)
    }
  }

  "position between" should "be the first position for 0.0" in {
    forAll(startAndEnd) {
      case (start, end) =>
        positionBetween(start, end, 0.0) should be(start +- start * 0.001)
    }
  }

  it should "be the second position for 1.0" in {
    forAll(startAndEnd) {
      case (start, end) =>
        positionBetween(start, end, 1.0) should be(end +- end * 0.001)
    }
  }

  it should "be in the middle for 0.5" in {
    forAll(startAndEnd) {
      case (start, end) =>
        positionBetween(start, end, 0.5) should be((start + end) / 2 +- 0.001)
    }
  }

  private val startAndEnd: Gen[(Double, Double)] =
    for {
      start <- Gen.posNum[Double].label("start")
      end <- Gen.posNum[Double].label("end")
      if start < end
    } yield (start, end)
}
