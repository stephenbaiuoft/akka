package com.example.iot

import akka.actor.typed.scaladsl.{AbstractBehavior, ActorContext, Behaviors, LoggerOps}
import akka.actor.typed.{ActorRef, Behavior, PostStop, Signal}
import com.example.iot.DeviceGroup.DeviceTerminated
import com.example.iot.DeviceManager.{DeviceRegistered, ReplyDeviceList, RequestAllTemperatures, RequestDeviceList, RequestTrackDevice}

import scala.concurrent.duration.DurationInt


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


object DeviceGroup {
  trait DGCommand
  // Behavior[Command] --> This is the Command of DeviceGroup
  def apply(groupId: String): Behavior[DGCommand] = {
    Behaviors.setup(context => new DeviceGroup(context, groupId))
  }

  // List of commands
  private final case class DeviceTerminated(device: ActorRef[Device.Command],
                                            groupId: String, deviceId: String)
    extends DGCommand
}

class DeviceGroup(context: ActorContext[DeviceGroup.DGCommand], groupId: String)
  extends AbstractBehavior[DeviceGroup.DGCommand](context) {
  // DeviceManager (Need to implement later on)

  // ActorRef[Device.Command] as Device Behavior is of Command
  private var deviceIdToActor = Map.empty[String, ActorRef[Device.Command]]
  context.log.info("DeviceGroup of groupId {} started", groupId)

  override def onMessage(msg: DeviceGroup.DGCommand): Behavior[DeviceGroup.DGCommand] =
    msg match {
      // Adding query capability to the group
      case RequestAllTemperatures(requestId, gId, replyTo) =>
        if (gId == groupId) {
          // Create QueryGroupCommand Anonymous
          // Why Anonymous?? Because of the replyTo ==> it's already got a hold of regardless
          context.spawnAnonymous(
            DeviceGroupQuery(deviceIdToActor, requestId = requestId, requester = replyTo, 3.seconds)
          )
          this
        } else {
          // Return this behavior from message processing in order to advise the system to reuse the previous behavior,
          Behaviors.unhandled
        }

      case RequestDeviceList(requestId, gId, replyTo) =>
        if (gId == groupId) {
          replyTo ! ReplyDeviceList(requestId, deviceIdToActor.keySet)
          this
        } else {
          /**
           * Return this behavior from message processing in order to advise the
           * system to reuse the previous behavior, including the hint that the
           * message has not been handled. This hint may be used by composite
           * behaviors that delegate (partial) handling to other behaviors.
           */
          Behaviors.unhandled
        }

      // `groupId` refers to the id given when DeviceGroup is created
      case trackMsg @ RequestTrackDevice(`groupId`, deviceId, replyTo) =>
        deviceIdToActor.get(deviceId) match {
          case Some(deviceActor) =>
            replyTo ! DeviceRegistered(deviceActor)
          case None =>
            context.log.info("Creating device actor for {}", trackMsg.deviceId)
            // context.spawn for creating an actor called deviceActor for deviceId
            val deviceActor = context.spawn(Device(groupId, deviceId), s"device-$deviceId")

            // This is how you'd let DeviceGroup know when the deviceActor shuts down
            /**
             * Register for termination notification with a custom message (Note a custom message!!!!!!)
             * once the Actor identified by the given ActorRef terminates.
             * This message is also sent when the watched actor is on a node
             * that has been removed from the cluster when using using Akka Cluster
             */
            // Custom message is where you'd pass in groupId, and deviceId info
            context.watchWith(deviceActor, DeviceTerminated(deviceActor, groupId, deviceId))
            deviceIdToActor += deviceId -> deviceActor
            replyTo ! DeviceRegistered(deviceActor)
        }
        this

      case RequestTrackDevice(gId, _, _) =>
        context.log.warn2("Ignoring TrackDevice request for {}. This actor is responsible for {}.", gId, groupId)
        this

      // Case when DeviceTerminated gets sent to Device Manager
      // In the DeviceTerminated case class, we'd put in groupId and the deviceId
      case DeviceTerminated(_, gId, deviceId) =>
        context.log.info("Device actor for {} from group {} has been terminated", deviceId, gId)
        deviceIdToActor -= deviceId
        this

    }

  override def onSignal: PartialFunction[Signal, Behavior[DeviceGroup.DGCommand]] = {
    case PostStop =>
      context.log.info("DeviceGroup {} stopped", groupId)
      this
  }
}
