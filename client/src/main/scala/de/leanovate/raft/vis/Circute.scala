package de.leanovate.raft.vis

import diode._

// Define the root of our application model
case class RootModel(messages: Seq[String], knowNodes: Set[String])

// Define actions
case class NewMessage(msg: String) extends Action

case object Reset extends Action

/**
  * AppCircuit provides the actual instance of the `RootModel` and all the action
  * handlers we need. Everything else comes from the `Circuit`
  */
object AppCircuit extends Circuit[RootModel] {
  // define initial value for the application model
  def initialModel = RootModel(Seq.empty, Set.empty)

  private val lastMessages = new ActionHandler(zoomTo(_.messages)) {
    override def handle = {
      case NewMessage(msg) => updated((msg +: value).take(20))
    }
  }

  private val knownNodes = new ActionHandler(zoomTo(_.knowNodes)) {
    val OneName = """.*(node\d).*""".r
    val TwoNames = """.*(node\d).*(node\d).*""".r

    override def handle = {
      case NewMessage(OneName(name)) if !value(name) => updated(value + name)
      case NewMessage(TwoNames(name1, name2))
          if !value(name1) || !value(name2) =>
        updated(value + name1 + name2)
    }
  }

  private val resetAll: HandlerFunction = {
    case (_, Reset) => Some(ActionResult.ModelUpdate(initialModel))
    case (_, _)     => None
  }

  override val actionHandler: HandlerFunction =
    foldHandlers(lastMessages, knownNodes, resetAll)
}
