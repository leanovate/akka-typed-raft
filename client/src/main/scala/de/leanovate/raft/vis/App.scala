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
  val messages = new MessageView(AppCircuit.zoom(_.networkEvents), AppCircuit)
  val nodes = new NodesView(AppCircuit.zoom(_.knowNodes), AppCircuit.zoom(_.networkEvents))
  val slider = new SlideView(AppCircuit.zoom(_.networkEvents.headOption.map(_.secondsSinceStart).getOrElse(0.0)))

  @JSExportTopLevel("App.main")
  override def main(): Unit = {

    val root = document.getElementById("root")
    // subscribe to changes in the application model and call render when anything changes
    AppCircuit.subscribe(AppCircuit.zoom(identity))(_ => render(root))
    // start the application by dispatching a Reset action
    AppCircuit(Reset)

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
  }

  private def render(root: Element) = {
    val e = div(
      h1("Raft-Visualisation"),
      slider.render,
      nodes.render,
      messages.render
    ).render
    // clear and update contents
    root.innerHTML = ""
    root.appendChild(e)
  }
}
