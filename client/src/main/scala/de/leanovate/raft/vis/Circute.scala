package de.leanovate.raft.vis

import diode._

// Define the root of our application model
case class RootModel(networkEvents: Seq[NetworkEvent],
                     knowNodes: Set[String],
                     lifeTime: Double)

// Define actions
case class NewEvent(event: NetworkEvent) extends Action

/**
  * AppCircuit provides the actual instance of the `RootModel` and all the action
  * handlers we need. Everything else comes from the `Circuit`
  */
object AppCircuit extends Circuit[RootModel] {
  // define initial value for the application model
  protected def initialModel = RootModel(Seq.empty, Set.empty, 0)

  private val lastMessages = new ActionHandler(zoomTo(_.networkEvents)) {
    override val handle = {
      case NewEvent(msg) => updated((msg +: value).take(500))
    }
  }

  private val knownNodes = new ActionHandler(zoomTo(_.knowNodes)) {
    override val handle = {
      case NewEvent(MessageSent(from, to, _, _)) =>
        updated(value + from + to)
    }
  }

  protected override val actionHandler: HandlerFunction =
    foldHandlers(lastMessages, knownNodes)
}
