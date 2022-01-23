package com.example

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration.DurationInt

class DeviceSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike {

  import Device._
  import DeviceManager._

  "Device actor" must {

    "be able to list active devices after one shuts down" in {
      val registeredProbe = createTestProbe[DeviceRegistered]()
      val groupActor = spawn(DeviceGroup("group"))

      groupActor ! RequestTrackDevice("group", "device1", registeredProbe.ref)
      val registered1 = registeredProbe.receiveMessage()
      val toShutDown = registered1.device

      groupActor ! RequestTrackDevice("group", "device2", registeredProbe.ref)
      registeredProbe.receiveMessage()

      val deviceListProbe = createTestProbe[ReplyDeviceList]()
      groupActor ! RequestDeviceList(requestId = 0, groupId = "group", deviceListProbe.ref)
      deviceListProbe.expectMessage(ReplyDeviceList(requestId = 0, Set("device1", "device2")))

      // Shut down the first here
      toShutDown ! Passivate
      registeredProbe.expectTerminated(toShutDown, registeredProbe.remainingOrDefault)

      // using awaitAssert to retry because it might take longer for the groupActor
      // to see the Terminated, that order is undefined
      registeredProbe.awaitAssert({
        groupActor ! RequestDeviceList(requestId = 1, groupId = "group", deviceListProbe.ref)
        deviceListProbe.expectMessage(ReplyDeviceList(requestId = 1, Set("device2")))},
        2000.milliseconds)
    }

    // Test the RequestDeviceList Command
    "be able to list active devices" in {
       val replyDeviceListProbe = createTestProbe[ReplyDeviceList]()
      val groupId = "testlistdevices"
      val dg = spawn(DeviceGroup(groupId))

      dg ! RequestDeviceList(1, groupId, replyDeviceListProbe.ref)
      replyDeviceListProbe.receiveMessage().deviceIds.size should === (0)

      val deviceRegisterProbe = createTestProbe[DeviceRegistered]()
      dg ! RequestTrackDevice(groupId, "device-a", deviceRegisterProbe.ref)
      val deviceA = deviceRegisterProbe.receiveMessage().device
      dg ! RequestTrackDevice(groupId, "device-b", deviceRegisterProbe.ref)
      val deviceB = deviceRegisterProbe.receiveMessage().device


      dg ! RequestDeviceList(2, groupId, replyDeviceListProbe.ref)
      replyDeviceListProbe.expectMessage(
        ReplyDeviceList(2, Set("device-a", "device-b"))
      )
    }


    "return same actor for same deviceId" in {
      val registerProbe = createTestProbe[DeviceRegistered]()
      val dg = spawn(DeviceGroup("mygroup"))

      // create actor1 when it first gets created
      dg ! RequestTrackDevice("mygroup", "id1", registerProbe.ref)
      // You get result from receiveMessage
      // Note where DeviceGroup.onMessage ==> DeviceRegistered(deviceActor)
      val registered1 = registerProbe.receiveMessage()
      val device1 = registered1.device

      val recordProbe = createTestProbe[TemperatureRecorded]()
      device1 ! RecordTemperature(100, 80.0, recordProbe.ref)

      // Now let's send another message of same mygroup and id1
      dg ! RequestTrackDevice("mygroup", "id1", registerProbe.ref)
      val registered2 = registerProbe.receiveMessage()

      device1 should === (registered2.device)

    }

    "be able to register a device actor" in {
      val probe = createTestProbe[DeviceRegistered]()
      val groupActor = spawn(DeviceGroup("group"))

      groupActor ! RequestTrackDevice("group", "device1", probe.ref)
      val registered1 = probe.receiveMessage()
      val deviceActor1 = registered1.device

      // another deviceId
      groupActor ! RequestTrackDevice("group", "device2", probe.ref)
      val registered2 = probe.receiveMessage()
      val deviceActor2 = registered2.device
      deviceActor1 should !==(deviceActor2)

      // Check that the device actors are working
      val recordProbe = createTestProbe[TemperatureRecorded]()
      deviceActor1 ! RecordTemperature(requestId = 0, 1.0, recordProbe.ref)
      recordProbe.expectMessage(TemperatureRecorded(requestId = 0))
      deviceActor2 ! Device.RecordTemperature(requestId = 1, 2.0, recordProbe.ref)
      recordProbe.expectMessage(Device.TemperatureRecorded(requestId = 1))

      // Add the part for ReadTemperature
      val readProbe = createTestProbe[RespondTemperature]()
      deviceActor1 ! ReadTemperature(2, readProbe.ref)
      readProbe.expectMessage(RespondTemperature(2, Some(1.0)))
    }

    "ignore requests for wrong groupId" in {
      val probe = createTestProbe[DeviceRegistered]()
      val groupActor = spawn(DeviceGroup("group"))

      // wrongGroup !== group
      groupActor ! RequestTrackDevice("wrongGroup", "device1", probe.ref)
      probe.expectNoMessage(500.milliseconds)
    }

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