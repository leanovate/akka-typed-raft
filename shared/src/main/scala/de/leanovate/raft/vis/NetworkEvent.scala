package de.leanovate.raft.vis
import upickle.default._

sealed trait NetworkEvent {
  def timeInSeconds: Double
}

object NetworkEvent {
  implicit val networkEventRW: ReadWriter[NetworkEvent] =
    ReadWriter.merge(NodeUpdate.nodeUpdateRW, MessageSent.messageSentRW)
}

//TODO: find a way to use java.time.Instant instead of secondsSinceStart
final case class MessageSent(from: String, to: String, timeInSeconds: Double, content: Map[String, String]) extends NetworkEvent

object MessageSent {
  implicit val messageSentRW: ReadWriter[MessageSent] = macroRW
}

final case class NodeUpdate(node: String, timeInSeconds: Double, content: Map[String, String]) extends NetworkEvent

object NodeUpdate {
  implicit val nodeUpdateRW: ReadWriter[NodeUpdate] = macroRW

}
