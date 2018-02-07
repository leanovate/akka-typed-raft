package de.leanovate.raft

import org.scalacheck.{Arbitrary, Gen}
import org.scalacheck.Arbitrary.arbitrary

package object vis {

  implicit val arbNodeName: Arbitrary[NodeName] =
    Arbitrary(Gen.alphaNumStr.map(NodeName(_)))

  implicit val arbNodeUpdate: Arbitrary[NodeUpdate] = Arbitrary(
    for {
      name <- arbitrary[NodeName]
      time <- arbitrary[Double]
      content <- arbitrary[Map[String, String]]
    } yield NodeUpdate(name, time, content)
  )
}
