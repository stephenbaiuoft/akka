package com.example

import akka.actor.typed.{ActorRef, Behavior, PostStop, Signal}
import akka.actor.typed.scaladsl.AbstractBehavior
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.LoggerOps
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.wordspec.AnyWordSpecLike

class DeviceSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike {

  import Device._

  "Device actor" must {

    "reply with latest temperature reading" in {
      val recordProbe = createTestProbe[TemperatureRecorded]()
      val readProbe = createTestProbe[RespondTemperature]()
      val deviceActor = spawn(Device("group", "device"))

      deviceActor ! Device.RecordTemperature(1, 65.0, recordProbe.ref)
      // expectMessage instead of using readProbe.receiveMessages()
      recordProbe.expectMessage(Device.TemperatureRecorded(requestId = 1))

      deviceActor ! Device.ReadTemperature(2, readProbe.ref)
      val readResponse = readProbe.receiveMessage()
      readResponse.requestId should === (2)
      readResponse.value should === (Some(65.0))

      deviceActor ! Device.RecordTemperature(3, 70.0, recordProbe.ref)
      val tmp = recordProbe.receiveMessage()
      tmp.requestId should === (3)

      deviceActor ! Device.ReadTemperature(4, readProbe.ref)
      readProbe.expectMessage(
        Device.RespondTemperature(requestId = 4, Some(70.0))
      )
    }


    "reply with empty reading if no temperature is known" in {
      // probe is mock actor, that contains Behavior[RespondTemperature]
      // The actor is of a behavior? because of probe.ref!! this is how you mock one
      val probe = createTestProbe[RespondTemperature]()
      val deviceActor = spawn(Device("group", "device"))

      deviceActor ! Device.ReadTemperature(requestId = 42, probe.ref)
      val response = probe.receiveMessage()
      response.requestId should ===(42)
      response.value should ===(None)
    }
  }
}

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
  // replyTo: ActorRef[RespondTemperature] ---> an Actor with Behavior[RespondTemperature]
  final case class ReadTemperature(requestId: Long, replyTo: ActorRef[RespondTemperature]) extends Command
  final case class RespondTemperature(requestId: Long, value: Option[Double])

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
        replyTo ! RespondTemperature(id, lastTemperatureReading)
        this

    }
  }

  override def onSignal: PartialFunction[Signal, Behavior[Command]] = {
    case PostStop =>
      context.log.info2("Device actor {}-{} stopped", groupId, deviceId)
      this
  }

}