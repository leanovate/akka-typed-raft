package de.leanovate.raft.vis
import upickle.default._

/**
  * Messages exchanged between visualisation server and client. This is an internal API,
  * so the JSON don't have to be pretty right now and can be macro generated.
  */
sealed trait NetworkEvent {
  def sendTime: Double
}

object NetworkEvent {
  implicit val networkEventRW: ReadWriter[NetworkEvent] =
    ReadWriter.merge(NodeUpdate.nodeUpdateRW, MessageSent.messageSentRW)
}

//TODO: find a way to use java.time.Instant instead of secondsSinceStart
final case class MessageSent(from: NodeName, to: NodeName, sendTime: Double, receiveTime: Double, content: Map[String, String]) extends NetworkEvent

object MessageSent {
  implicit val messageSentRW: ReadWriter[MessageSent] = macroRW
}

final case class NodeUpdate(node: NodeName, sendTime: Double, content: Map[String, String]) extends NetworkEvent

object NodeUpdate {
  implicit val nodeUpdateRW: ReadWriter[NodeUpdate] = macroRW

}

case class NodeName(name: String)

object NodeName {
  implicit val nodeNameRW: ReadWriter[NodeName] = macroRW
  implicit val ordering: Ordering[NodeName] =
    Ordering.by(_.name)
}
