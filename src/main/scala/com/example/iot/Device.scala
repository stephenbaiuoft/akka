package com.example.iot

import akka.actor.typed.scaladsl.{AbstractBehavior, ActorContext, Behaviors, LoggerOps}
import akka.actor.typed.{ActorRef, Behavior, PostStop, Signal}

/**
 * An ActorRef is the identity or address of an Actor instance.
 * 'It is valid only during the Actor’s lifetime and allows messages to be sent to that Actor instance.
 * Sending a message to an Actor that has terminated before receiving the message will lead to that
 * message being discarded; such messages are delivered to the DeadLetter channel of the akka.event.
 *
 * EventStream on a best effort basis (i.e. this delivery is not reliable
 */

// https://doc.akka.io/docs/akka/current/typed/guide/tutorial_3.html
object Device {
  def apply(groupId: String, deviceId: String): Behavior[Command] =
    Behaviors.setup(context => new Device(context, groupId, deviceId))

  // Each actor defines the type of messages it accepts.
  // The device actor has the responsibility to use the same ID parameter for the response of a given query,
  sealed trait Command
  // This is to ask Device to stop working
  case object Passivate extends Command

  // replyTo: ActorRef[RespondTemperature] ---> an Actor with Behavior[RespondTemperature]
  final case class ReadTemperature(requestId: Long, replyTo: ActorRef[RespondTemperature]) extends Command
  final case class RespondTemperature(requestId: Long, deviceId: String, value: Option[Double])

  // Adding a write protocol
  // write protocol is to update the currentTemperature field when the actor receives a message that contains the temperature
  final case class RecordTemperature(requestId: Long, value: Double, replyTo: ActorRef[TemperatureRecorded])
    extends Command
  final case class TemperatureRecorded(requestId: Long)
}

class Device(context: ActorContext[Device.Command], groupId: String, deviceId: String)
  extends AbstractBehavior[Device.Command](context) {
  // Importing from Device Object
  import Device._

  var lastTemperatureReading: Option[Double] = None

  context.log.info2("Device actor {}-{} started", groupId, deviceId)

  override def onMessage(msg: Command): Behavior[Command] = {
    msg match {
      case RecordTemperature(id, value, replyTo) =>
        context.log.info2("Recorded temperature reading {} with {}", value, id)
        lastTemperatureReading = Some(value)
        replyTo ! TemperatureRecorded(id)
        this

      case ReadTemperature(id, replyTo) =>
        // Note deviceId is given in this actor when creation
        replyTo ! RespondTemperature(id, deviceId, lastTemperatureReading)
        this

      // Have the actor stop
      case Passivate =>
        Behaviors.stopped

    }
  }

  override def onSignal: PartialFunction[Signal, Behavior[Command]] = {
    case PostStop =>
      context.log.info2("Device actor {}-{} stopped", groupId, deviceId)
      this
  }

}