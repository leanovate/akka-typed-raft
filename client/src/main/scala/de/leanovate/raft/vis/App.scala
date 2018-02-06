package de.leanovate.raft.vis

import org.scalajs.dom
import org.scalajs.dom._
import upickle.default.read

import scala.scalajs.js.JSApp
import scala.scalajs.js.annotation.JSExportTopLevel
import scalatags.JsDom.all._

@JSExportTopLevel("App")
object App extends JSApp {
  // create a view for the counter
  val messages =
    new MessageView(AppCircuit.zoom(_.networkEvents))
  val nodes = new NodesView(AppCircuit.zoom(_.currentTime),
                            AppCircuit.zoom(_.knowNodes),
                            AppCircuit.zoom(_.networkEvents),
                          AppCircuit)

  val timeView = new TimeView(AppCircuit.zoom(_.currentTime))

  def onlyLastState(seq: Seq[NetworkEvent]): Map[NodeName, String] =
    seq
      .collect { case update: NodeUpdate => update }
      .groupBy(_.node)
      .mapValues(_.maxBy(_.sendTime).content.toString())

  val nodeOverview = new NodeOverview(
    AppCircuit.zoom(_.selected),
    AppCircuit.zoom(rm => onlyLastState(rm.networkEvents)))

  @JSExportTopLevel("App.main")
  override def main(): Unit = {

    val root = document.getElementById("root")
    // subscribe to changes in the application model and call render when anything changes
    AppCircuit.subscribe(AppCircuit.zoom(identity))(_ => render(root))
    // start the application by dispatching a Reset action

    val source = new dom.EventSource("/vis/events")

    source.onmessage = { message: dom.MessageEvent =>
      if (message.data.toString.nonEmpty)
        AppCircuit(NewEvent(read[NetworkEvent](message.data.toString)))
    }

    source.onerror = { _ =>
      source.onmessage = { _ =>
        dom.document.location.reload()
      }
    }

    window.setInterval(() => AppCircuit(Tick), 20)
  }

  private def render(root: Element) = {
    val e = div(
      h1("Raft-Visualisation"),
      timeView.render,
      nodeOverview.render,
      nodes.render,
      messages.render
    ).render
    // clear and update contents
    root.innerHTML = ""
    root.appendChild(e)
  }
}
