package com.example.iot

import akka.actor.typed.scaladsl.{AbstractBehavior, ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior, PostStop, Signal}


/**
 What you have learnt so far

Request-respond (for temperature recordings)
Create-on-demand (for registration of devices)
Create-watch-terminate (for creating the group and device actor as children)
 */
object DeviceManager {

  def apply: Behavior[DMCommand] = {
    Behaviors.setup(context => new DeviceManager(context))
  }

  // Define commands that can be sent to DeviceManager
  trait DMCommand

  // Command RequestAllTemperatures
  final case class RequestAllTemperatures(requestId: Long, groupId: String, replyTo: ActorRef[RespondAllTemperatures])
  // DeviceGroupQuery??? We'd implement it using an actor, this is because it's a task
    extends DeviceGroupQuery.DGQueryCommand
      with DeviceGroup.DGCommand
      with DeviceManager.DMCommand
  // Command RequestAllTemperatures  Response
  final case class RespondAllTemperatures(requestId: Long, temperatures: Map[String, TemperatureReading])
    // sealed, meaning on this package can extend TemperatureReading
    sealed trait TemperatureReading
    final case class Temperature(value: Double) extends TemperatureReading
    case object TemperatureNotAvailable extends TemperatureReading
    case object DeviceNotAvailable extends TemperatureReading
    case object DeviceTimedOut extends TemperatureReading

  // RequestDeviceList Command -> Request device list for a given groupId to a DeviceManager
  final case class RequestDeviceList(requestId: Long,
                                     groupId: String,
                                     replyTo: ActorRef[ReplyDeviceList]) extends
    DMCommand with DeviceGroup.DGCommand
  // RequestDeviceList Command Response!!!!!!!
  final case class ReplyDeviceList(requestId: Long,
                             deviceIds: Set[String])

  // RequestTrackDevice goes to DeviceManager first, then goes to DeviceGroup
  // So it extends DeviceManager.DMCommand, then DeviceGroup.DGCommand
  // Now? why replyTo is ActorRef[DeviceRegistered]???? --> you Need some Actor Behavior that confirms with DeviceRegistered
  final case class RequestTrackDevice(groupId: String,
                                      deviceId: String,
                                      replyTo: ActorRef[DeviceRegistered]) extends DeviceManager.DMCommand with DeviceGroup.DGCommand
  // DeviceRegistered, -> device of the Actor Type where Behavior[Device.Command]
  final case class DeviceRegistered(device: ActorRef[Device.Command])

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
