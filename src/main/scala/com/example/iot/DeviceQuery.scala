package com.example.iot

import akka.actor.typed.scaladsl.{AbstractBehavior, ActorContext, Behaviors, TimerScheduler}
import akka.actor.typed.{ActorRef, Behavior}
import com.example.iot.DeviceGroupQuery.{CollectionTimeout, DGQueryCommand, DeviceTerminated, WrappedRespondTemperature}
import com.example.iot.DeviceManager._

import scala.concurrent.duration.FiniteDuration

// Working with Device Group (Hierarchy Design for Actor Protocols)
// https://doc.akka.io/docs/akka/current/typed/guide/tutorial_4.html

/**
 * Looking at registration in more detail, we can outline the necessary functionality:

When a DeviceManager receives a request with a group and device id:
If the manager already has an actor for the device group, it forwards the request to it.
Otherwise, it creates a new device group actor and then forwards the request.

The DeviceGroup actor receives the request to register an actor for the given device:
If the group already has an actor for the device it replies with the ActorRef of the existing device actor.
Otherwise, the DeviceGroup actor first creates a device actor and replies with the ActorRef of the newly created device actor.

The sensor will now have the ActorRef of the device actor to send messages directly to it.

 */

object DeviceGroupQuery {
  def apply(
             deviceIdToActor: Map[String, ActorRef[Device.Command]], // deviceIdToActor, from the DeviceManager set
             requestId: Long,
             requester: ActorRef[DeviceManager.RespondAllTemperatures],
             timeout: FiniteDuration): Behavior[DGQueryCommand] = {
    Behaviors.setup { context =>
      // Behaviors.withTimers -> to schedule a message that will be sent after a given delay. (timers delay)
      Behaviors.withTimers { timers =>
        new DeviceGroupQuery(deviceIdToActor, requestId, requester, timeout, context, timers)
      }
    }
  }

  // Commands for DeviceGroupQuery the SQL actor
  trait DGQueryCommand

  // We need to create a message that represents the query timeout. We create a simple message CollectionTimeout without any parameters
  // This is for Query (therefore CollectionTimeout)
  private case object CollectionTimeout extends DGQueryCommand

  // Wrapper for Device.RespondTemperature
  // RespondTemperature replies from the device actor to
  // WrappedRespondTemperature -> the message protocol that the DeviceGroupQuery actor understands (It extends DGQueryCommand)
  final case class WrappedRespondTemperature(response: Device.RespondTemperature) extends DGQueryCommand

  private final case class DeviceTerminated(deviceId: String) extends DGQueryCommand

}

class DeviceGroupQuery(deviceIdToActor: Map[String, ActorRef[Device.Command]],
                       requestId: Long,
                       requester: ActorRef[DeviceManager.RespondAllTemperatures],
                       timeout: FiniteDuration,
                       context: ActorContext[DeviceGroupQuery.DGQueryCommand],
                       timers: TimerScheduler[DGQueryCommand]
                      ) extends AbstractBehavior[DeviceGroupQuery.DGQueryCommand](context) {
  /**
   * Start a timer that will send msg once to the self actor after the given delay.
      Each timer has a key and if a new timer with same key is started the previous is cancelled.
      It is guaranteed that a message from the previous timer is not received,
      even if it was already enqueued in the mailbox when the new timer was started.
   */
  // (key, msg, delay)
  // This will send CollectionTimeout message to Actor itself after 'timeout'
  timers.startSingleTimer(CollectionTimeout, CollectionTimeout, timeout)

  // messageAdapter ->
  // we use a messageAdapter that wraps the RespondTemperature in a WrappedRespondTemperature, which extends DeviceGroupQuery.Command
  private val respondTemperatureAdapter = context.messageAdapter(WrappedRespondTemperature.apply)


  deviceIdToActor.foreach {
    case (deviceId, device) =>
      // keep an eye using context to watch the device in terminated
      // --> Meaning it'll send DeviceTerminated (extends DGQueryCommand
      context.watchWith(device, DeviceTerminated(deviceId))
      // This is where the logic for querying all deviceIds start
      // Put in 'respondTemperatureAdapter', replyTo is this actor DMQuery.Command
      // --> 不然 onMessage怎么能读到 msg呢？？
      device ! Device.ReadTemperature(0, respondTemperatureAdapter)
  }

  /**
   * We keep track of the state with:
    a Map of already received replies
    a Set of actors that we still wait on
   */
  private var repliesSoFar = Map.empty[String, TemperatureReading]
  private var stillWaiting = deviceIdToActor.keySet

  /**
   * We have three events to act on:
    We can receive a RespondTemperature message from one of the devices.
    We can receive a DeviceTerminated message for a device actor that has been stopped in the meantime.
    We can reach the deadline and receive a CollectionTimeout.
   */
  override def onMessage(msg: DGQueryCommand): Behavior[DGQueryCommand] =
    msg match {
      case WrappedRespondTemperature(response) => onRespondTemperature(response)
      case DeviceTerminated(deviceId)          => onDeviceTerminated(deviceId)
      case CollectionTimeout                   => onCollectionTimout()
    }

  private def onRespondTemperature(response: Device.RespondTemperature): Behavior[DGQueryCommand] = {
    val reading = response.value match {
      case Some(value) => Temperature(value)
      case None        => TemperatureNotAvailable
    }

    val deviceId = response.deviceId
    repliesSoFar += (deviceId -> reading)
    stillWaiting -= deviceId

    respondWhenAllCollected()
  }

  private def onDeviceTerminated(deviceId: String): Behavior[DGQueryCommand] = {
    if (stillWaiting(deviceId)) {
      // Replied as deviceId -> DeviceNotAvailable is a valid response
      repliesSoFar += (deviceId -> DeviceNotAvailable)
      // Remove from stillWaiting
      stillWaiting -= deviceId
    }
    respondWhenAllCollected()
  }

  private def onCollectionTimout(): Behavior[DGQueryCommand] = {
    repliesSoFar ++= stillWaiting.map(deviceId => deviceId -> DeviceTimedOut)
    stillWaiting = Set.empty
    respondWhenAllCollected()
  }

  private def respondWhenAllCollected(): Behavior[DGQueryCommand] = {
    // No more await, use requester to Respond with valid results ( 4 cases described above)
    if (stillWaiting.isEmpty) {
      requester ! RespondAllTemperatures(requestId, repliesSoFar)
      // Return this behavior from message processing to signal that this actor shall terminate voluntarily
      // DeviceGroupQuery actor should stop in this case, as it's done already
      Behaviors.stopped
    } else {
      // Continue to wait? with this behavior?
      this
    }
  }

}