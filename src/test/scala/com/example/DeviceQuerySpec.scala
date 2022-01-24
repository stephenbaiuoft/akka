package com.example

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import com.example.iot.DeviceGroupQuery.WrappedRespondTemperature
import com.example.iot.{Device, DeviceGroupQuery, DeviceManager}
import com.example.iot.DeviceManager.{DeviceNotAvailable, DeviceTimedOut, RespondAllTemperatures, Temperature}
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration.DurationInt

class DeviceQuerySpec extends ScalaTestWithActorTestKit with AnyWordSpecLike {
  "return DeviceTimedOut if device does not answer in time" in {
    val requester = createTestProbe[RespondAllTemperatures]()

    val device1 = createTestProbe[Device.Command]()
    val device2 = createTestProbe[Device.Command]()

    val deviceIdToActor = Map("device1" -> device1.ref, "device2" -> device2.ref)

    val queryActor = {
      // 3 seconds --> meaning the timer within DeviceGroupQuery will send CollectionTimeout after 3 seconds
      spawn(DeviceGroupQuery(deviceIdToActor, requestId = 1, requester = requester.ref, timeout = 3.seconds))
    }

    device1.expectMessageType[Device.ReadTemperature]
    device2.expectMessageType[Device.ReadTemperature]

    queryActor ! WrappedRespondTemperature(Device.RespondTemperature(requestId = 0, "device1", Some(1.0)))

    // no reply from device2

    requester.awaitAssert(
      RespondAllTemperatures(
        requestId = 1,
        temperatures = Map("device1" -> Temperature(1.0), "device2" -> DeviceTimedOut))
      , 5.seconds //5 seconds is longer than 3 seconds, which is what we set
    )
  }


  "return temperature reading even if device stops after answering" in {
    val requester = createTestProbe[RespondAllTemperatures]()
    val device1 = createTestProbe[Device.Command]()
    val device2 = createTestProbe[Device.Command]()
    val deviceIdToActor = Map("device1" -> device1.ref, "device2" -> device2.ref)

    val queryActor = spawn(
      DeviceGroupQuery(deviceIdToActor, requestId = 1, requester = requester.ref, timeout = 3.seconds)
    )

    device2.expectMessageType[Device.ReadTemperature]
    device1.expectMessageType[Device.ReadTemperature]
    // This mock is saying device1 received the RespondTemperature
    queryActor ! WrappedRespondTemperature(Device.RespondTemperature(requestId = 0, "device1", Some(1.0)))
    queryActor ! WrappedRespondTemperature(Device.RespondTemperature(requestId = 0, "device2", Some(88.0)))

    // make device1 not available
    device1.stop()

    requester.expectMessage(
      RespondAllTemperatures(
        requestId = 1, Map("device1" -> Temperature(1.0), "device2" -> Temperature(88.0))
      )
    )
  }

  "return TemperatureNotAvailable for devices with no readings" in {
    val requester = createTestProbe[RespondAllTemperatures]()

    val device1 = createTestProbe[Device.Command]()
    val device2 = createTestProbe[Device.Command]()

    val deviceIdToActor = Map("device1" -> device1.ref, "device2" -> device2.ref)

    val queryActor =
      spawn(DeviceGroupQuery(deviceIdToActor, requestId = 1, requester = requester.ref, timeout = 3.seconds))

    device1.expectMessageType[Device.ReadTemperature]
    device2.expectMessageType[Device.ReadTemperature]

    queryActor ! WrappedRespondTemperature(Device.RespondTemperature(requestId = 0, "device1", None))
    queryActor ! WrappedRespondTemperature(Device.RespondTemperature(requestId = 0, "device2", Some(2.0)))

    requester.expectMessage(
      RespondAllTemperatures(
        requestId = 1,
        temperatures = Map("device1" -> DeviceManager.TemperatureNotAvailable, "device2" -> Temperature(2.0))))
  }

  "return DeviceNotAvailable if device stops before answering" in {
    val requester = createTestProbe[RespondAllTemperatures]()

    val device1 = createTestProbe[Device.Command]()
    val device2 = createTestProbe[Device.Command]()

    val deviceIdToActor = Map("device1" -> device1.ref, "device2" -> device2.ref)

    val queryActor =
      spawn(DeviceGroupQuery(deviceIdToActor, requestId = 1, requester = requester.ref, timeout = 3.seconds))

    device1.expectMessageType[Device.ReadTemperature]
    device2.expectMessageType[Device.ReadTemperature]

    // Mock message send with only 1 response
    queryActor ! WrappedRespondTemperature(Device.RespondTemperature(requestId = 0, "device1", Some(2.0)))

    // stop device2, and this will trigger stop???
    // Yeah, this will trigger on DeviceQuery onMessage -> case DeviceTerminated(deviceId)          => onDeviceTerminated(deviceId)
    device2.stop()

    requester.expectMessage(
      RespondAllTemperatures(
        requestId = 1,
        temperatures = Map("device1" -> Temperature(2.0), "device2" -> DeviceNotAvailable)))
  }

  "Device Manager Query" must {
    "return temperature value for working devices" in {
      val requester = createTestProbe[RespondAllTemperatures]()
      val device1 = createTestProbe[Device.Command]()
      val device2 = createTestProbe[Device.Command]()

      val deviceIdToActor = Map("device1" -> device1.ref, "device2" -> device2.ref)

      // Create a queryActor
      // --> Now you realize, the input is deviceIdToActor, could have been
      // DeviceManager, as deviceManager actor would contain deviceIdToActor
      val queryActor =
        spawn(DeviceGroupQuery(deviceIdToActor, requestId = 101, requester = requester.ref, timeout = 3.seconds))

      // When queryActor is spawned, device1 and device2 would be queried
      device1.expectMessageType[Device.ReadTemperature]
      device2.expectMessageType[Device.ReadTemperature]

      // Due to device1 and device2 are stubs, so they will not do WrappedRespondTemperature from the query logic
      // So at this point, we'd simulate so by sending message to queryActor itself
      queryActor ! WrappedRespondTemperature(Device.RespondTemperature(requestId = 0, "device1", Some(11.1)))
      queryActor ! WrappedRespondTemperature(Device.RespondTemperature(requestId = 0, "device2", Some(22.0)))

      requester.expectMessage(
        RespondAllTemperatures(
          requestId = 101,
          temperatures = Map("device1" -> Temperature(11.1), "device2" -> Temperature(22.0))))
    }
  }
}