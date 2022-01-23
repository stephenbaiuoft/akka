package com.example

import akka.actor.typed.scaladsl.{AbstractBehavior, ActorContext, Behaviors, LoggerOps}
import akka.actor.typed.{ActorRef, Behavior, PostStop, Signal}
import com.example.DeviceGroup.DeviceTerminated
import com.example.DeviceManager.{DeviceRegistered, ReplyDeviceList, RequestDeviceList, RequestTrackDevice}


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

/**
 What you have learnt so far

Request-respond (for temperature recordings)
Create-on-demand (for registration of devices)
Create-watch-terminate (for creating the group and device actor as children)
 */
object DeviceManager {
  trait DMCommand
  def apply: Behavior[DMCommand] = {
    Behaviors.setup(context => new DeviceManager(context))
  }

  final case class ReplyDeviceList(requestId: Long,
                             deviceIds: Set[String])

  // Request device list for a given groupId to a DeviceManager
  final case class RequestDeviceList(requestId: Long,
                               groupId: String,
                               replyTo: ActorRef[ReplyDeviceList]) extends
    DMCommand with DeviceGroup.DGCommand

  // DeviceRegistered, -> device of the Actor Type where Behavior[Device.Command]
  final case class DeviceRegistered(device: ActorRef[Device.Command])

  // RequestTrackDevice goes to DeviceManager first, then goes to DeviceGroup
  // So it extends DeviceManager.DMCommand, then DeviceGroup.DGCommand
  // Now? why replyTo is ActorRef[DeviceRegistered]???? --> you Need some Actor Behavior that confirms with DeviceRegistered
  final case class RequestTrackDevice(groupId: String,
                                      deviceId: String,
                                      replyTo: ActorRef[DeviceRegistered])
    extends DeviceManager.DMCommand
      with DeviceGroup.DGCommand

  // DeviceGroupTerminated is at Device Manager level
  private final case class DeviceGroupTerminated(groupId: String) extends DMCommand
}

class DeviceManager(context: ActorContext[DeviceManager.DMCommand]) extends AbstractBehavior[DeviceManager.DMCommand](context){
    import DeviceManager._
  var groupIdToActor = Map.empty[String, ActorRef[DeviceGroup.DGCommand]]

  context.log.info("DeviceManager started")

  override def onMessage(msg: DMCommand): Behavior[DMCommand] = {
    msg match {
      case trackMsg @ RequestTrackDevice(groupId, _, replyTo)
        => groupIdToActor.get(groupId) match {
        // Found and forward the request
        case Some(ref) => ref ! trackMsg
        // Not found, we'd need to create a DeviceGroup actor
        case None =>
          context.log.info("Creating device group actor for {}", groupId)
          val groupActor = context.spawn(DeviceGroup(groupId), "group-" + groupId)
          context.watchWith(groupActor, DeviceGroupTerminated(groupId))
          groupActor ! trackMsg
          groupIdToActor += groupId -> groupActor
      }
      this

      case req @ RequestDeviceList(requestId, groupId, replyTo) =>
        groupIdToActor.get(groupId) match {
          case Some(ref) =>
            // Found, and have ref (Device Group actor instance) send the message of req, RequestDeviceList
            ref ! req
          case None => // Not found, then return empty[]
            replyTo ! ReplyDeviceList(requestId, Set.empty)
        }
        this

      case DeviceGroupTerminated(groupId) =>
        context.log.info("Device group actor for {} has been terminated", groupId)
        groupIdToActor -= groupId
        this
    }
  }

  override def onSignal: PartialFunction[Signal, Behavior[DMCommand]] = {
    case PostStop =>
      context.log.info("DeviceManager stopped")
      this
  }
}

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