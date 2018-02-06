package de.leanovate.raft.vis

import diode._

// Define the root of our application model
case class RootModel(networkEvents: Seq[NetworkEvent],
                     knowNodes: Set[NodeName],
                     selected: Option[NodeName],
                     currentTime: Double)

// Define actions
case class NewEvent(event: NetworkEvent) extends Action

case class SelectNode(node: NodeName) extends Action

case object Tick extends Action

/**
  * AppCircuit provides the actual instance of the `RootModel` and all the action
  * handlers we need. Everything else comes from the `Circuit`
  */
object AppCircuit extends Circuit[RootModel] {

  protected def initialModel = RootModel(Seq.empty, Set.empty, None, 0)

  private val lastMessages = new ActionHandler(zoomTo(_.networkEvents)) {
    override val handle = {
      case NewEvent(msg) =>
        updated((msg +: value).take(50))
    }
  }

  private val knownNodes = new ActionHandler(zoomTo(_.knowNodes)) {
    override val handle = {
      case NewEvent(MessageSent(from, to, _, _, _))
          if !value.contains(from) || !value.contains(to) =>
        updated(value + from + to)
    }
  }

  private def alwaysUpdate[T](modelRW: ModelRW[RootModel, T])(
      f: PartialFunction[(Any, T), T]) =
    new ActionHandler[RootModel, T](modelRW) {
      override protected def handle =
        new PartialFunction[Any, ActionResult[RootModel]] {
          override def isDefinedAt(event: Any) = f.isDefinedAt((event, value))

          override def apply(event: Any) = updated(f((event, value)))
        }
    }

  type Reducer[T] = PartialFunction[(Any, T), T]

  private[vis] val updateTime: Reducer[Double] = {
    case (NewEvent(msg), _) =>
      msg.sendTime
    case (Tick, time) =>
      time + 0.02
  }

  private[vis] val updateSelection: Reducer[Option[NodeName]] = {
    case (SelectNode(name), _) => Some(name)
  }

  protected override val actionHandler: HandlerFunction =
    foldHandlers(
      lastMessages,
      knownNodes,
      alwaysUpdate(zoomTo(_.currentTime))(updateTime),
      alwaysUpdate(zoomTo(_.selected))(updateSelection)
    )
}
