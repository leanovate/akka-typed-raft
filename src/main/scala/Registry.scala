import akka.NotUsed
import akka.typed.scaladsl.Actor
import akka.typed.{ActorRef, Behavior}

object Registry {
  def behaviour: Behavior[Command] = running(Map.empty)

  private def running(registry: Map[Symbol, Int]): Behavior[Command] = Actor.immutable {
    case (_, Get(name, replyTo)) =>
      replyTo ! registry.get(name)
      Actor.same

    case (_, Set(name, value, replyTo)) =>
      replyTo ! NotUsed
      running(registry + (name -> value))

    case (_, Delete(name, replyTo)) =>
      replyTo ! NotUsed
      running(registry - name)
  }

  sealed trait Command

  final case class Get(name: Symbol, replyTo: ActorRef[Option[Int]]) extends Command

  final case class Set(name: Symbol, value: Int, replyTo: ActorRef[NotUsed]) extends Command

  final case class Delete(name: Symbol, replyTo: ActorRef[NotUsed]) extends Command

}
