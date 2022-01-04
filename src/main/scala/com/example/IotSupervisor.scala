package com.example
import akka.actor.typed.scaladsl.{AbstractBehavior, ActorContext, Behaviors}
import akka.actor.typed.{ActorSystem, Behavior, PostStop, Signal}

  object IotSupervisor {
    def apply(): Behavior[Nothing] = {
      // the following println statement is correct
      println("this happens first should, before IoT Application started")
      Behaviors.setup[Nothing](context => new IotSupervisor(context))
    }
  }

  class IotSupervisor(context: ActorContext[Nothing]) extends AbstractBehavior[Nothing](context) {
    context.log.info("IoT Application started")

    override def onMessage(msg: Nothing): Behavior[Nothing] = {
      // No need to handle any messages
      Behaviors.unhandled
    }

    override def onSignal: PartialFunction[Signal, Behavior[Nothing]] = {
      case PostStop =>
        context.log.info("IoT Application stopped")
        this
    }
  }

object IotApp {

  def main(args: Array[String]): Unit = {
    // Create ActorSystem and top level supervisor
    ActorSystem[Nothing](IotSupervisor(), "iot-system")
  }

}
