package de.leanovate.raft

import de.leanovate.raft.shared.SharedMessages
import org.scalajs.dom

object ScalaJSExample {

  def main(args: Array[String]): Unit = {

    val source = new dom.EventSource("/vis/events")

    source.onmessage = { message =>
      dom.console.log(message.data.toString)
    }

    dom.document.getElementById("scalajsShoutOut").textContent =
      SharedMessages.itWorks
  }
}
