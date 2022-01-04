package com.example

import akka.actor.typed.scaladsl.{AbstractBehavior, ActorContext, Behaviors}
import akka.actor.typed.{ActorSystem, Behavior, PostStop, PreRestart, Signal, SupervisorStrategy}

// https://doc.akka.io/docs/akka/current/typed/guide/tutorial_1.html

/**
An Actor is given by the combination of a Behavior and a context in which this behavior is executed.
- context defines where a behavior is executed
- a behavior is an abstract class which the actor extends on, so you'd have this-> referring to behavior


As per the Actor Model an Actor can perform the following actions when processing a message:
- send a finite number of messages to other Actors it knows
- create a finite number of Actors
- designate the behavior for the next message
In Akka the first capability is accessed by using the ! or tell method on an ActorRef,
the second is provided by #spawn and
the third is implicit in the signature of Behavior in that the next behavior is always returned from the message processing logic.

An ActorContext in addition provides access to the Actor’s own identity (“self”), // -> means what `this` refers to
the ActorSystem it is part of, methods for querying the list of child Actors it created, access to Terminated and timed message scheduling.
 * @param context
 */

object PrintMyActorRefActor {
  /**
   * setup is a factory for a behavior. Creation of the behavior instance is deferred until the actor is started, as opposed to Behaviors.receive
   * that creates the behavior instance immediately before the actor is running.
   *
   * The factory function pass the ActorContext as parameter and that can for example be used for spawning child actors.
   * setup is typically used as the outer most behavior when spawning an actor,
   * but it can also be returned as the next behavior when processing a message or signal.
   * In that case it will be started immediately after it is returned, i.e. next message will be processed by the started behavior.
   *
   *
   * apply() is the built-in when PrintMyActorRefActor is spawned from context.spawn(PrintMyActorRefActor(), "first-actor") (Line 59)
   */
  def apply(): Behavior[String] =
    Behaviors.setup(context => new PrintMyActorRefActor(context))
}

class PrintMyActorRefActor(context: ActorContext[String]) extends AbstractBehavior[String](context) {

  override def onMessage(msg: String): Behavior[String] =
    msg match {
      case "printit" =>
        // context.self = Actor[akka://testSystem/user/first-actor#-1200642789]
        // context.system = akka://testSystem (Same system, being the same root guardian
        val secondRef = context.spawn(Behaviors.empty[String], "second-actor")
        println(s"Second: $secondRef")
        // This ---> PrintMyActorRefActor this Actor itself, which is of type Behavior[String], as it's what's being extended
        this
    }
}

object Main {
  def apply(): Behavior[String] =
    Behaviors.setup(context => new Main(context))

}

class Main(context: ActorContext[String]) extends AbstractBehavior[String](context) {
  override def onMessage(msg: String): Behavior[String] =
    msg match {
      case "start" =>
        // context in this case
        // context.self = Actor[akka://testSystem/user#0] --> ?? self referring to an actor
        // context.system = akka://testSystem

        val firstRef = context.spawn(PrintMyActorRefActor(), "first-actor")
        println(s"First: $firstRef")
        firstRef ! "printit"
        // this is com.example.Main@48cfff5f  --> so this is Main class which is a class extending the AbstractBehavior
        // println("this is " + this)
        // context to string?akka.actor.typed.internal.adapter.ActorContextAdapter@77362927
        // println("context to string?" + this.context.toString)
        this
    }
}

object MainActorLifecycleTest {
  def apply(): Behavior[String] =
    Behaviors.setup(context => new MainActorLifecycleTest(context))
}

// This is the class that tests the actor life cycle, to be used in `ActorHierarchyExperiments`
class MainActorLifecycleTest(context: ActorContext[String]) extends AbstractBehavior[String](context) {
  override def onMessage(msg: String): Behavior[String] =
    msg match {
      case "start" =>

        val first = context.spawn(StartStopActor1(), "first")
        println(s"First: $first")
        // See case "stop" in StartStopActor1
        first ! "stop"
        this
    }
}

// How to stop an actor
/**
 * To stop an actor, the recommended pattern is to return Behaviors.stopped() inside the actor to stop itself, usually as a response to some user
 * defined stop message or when the actor is done with its job.
 *
 * Stopping a child actor is technically possible by calling context.stop(childRef) from the parent,
 * but it’s not possible to stop arbitrary (non-child) actors this way.
 */

object StartStopActor1 {
  // apply tells you how a StartStopActor1() instance get spawned
  def apply(): Behavior[String] =
    Behaviors.setup(context => new StartStopActor1(context))
}

class StartStopActor1(context: ActorContext[String]) extends AbstractBehavior[String](context) {
  println("first started")
  // Actor1 spawns an Actor2 instance
  context.spawn(StartStopActor2(), "second")

  override def onMessage(msg: String): Behavior[String] =
    msg match {
      //Return this behavior from message processing to signal that this actor shall terminate voluntarily.
      // If this actor has created child actors then these will be stopped as part of the shutdown procedure.

      //The PostStop signal that results from stopping this actor will be passed to the current behavior.
      // All other messages and signals will effectively be ignored.

      // Why this works?
      // To stop an actor, the recommended pattern is to return Behaviors.stopped() inside the actor to stop itself

      // Note this also means StartStopActor2 will be stopped even we don't define onMessage with case "stop" => Behavior.stopped
      case "stop" => Behaviors.stopped
    }

  override def onSignal: PartialFunction[Signal, Behavior[String]] = {
    case PostStop =>
      println("first stopped")
      this
  }

}

object StartStopActor2 {
  def apply(): Behavior[String] =
    Behaviors.setup(new StartStopActor2(_))
}

class StartStopActor2(context: ActorContext[String]) extends AbstractBehavior[String](context) {
  println("second started")

  override def onMessage(msg: String): Behavior[String] = {
    // no messages handled by this actor
    // onMessage is not triggered as the StartStopActor1 never sends any message to StartSopActor2
    println("StartStopActor2 - getting message ", msg)
    Behaviors.unhandled
  }

  override def onSignal: PartialFunction[Signal, Behavior[String]] = {
    case PostStop =>
      println("second stopped")
      this
  }

}

object SupervisingActor {
  def apply(): Behavior[String] =
    Behaviors.setup(context => new SupervisingActor(context))
}

class SupervisingActor(context: ActorContext[String]) extends AbstractBehavior[String](context) {
  private val child = context.spawn(
    // Spawning SupervisedActor() as a child (the actor being supervised)
    Behaviors.supervise(SupervisedActor()).onFailure(SupervisorStrategy.restart),
    name = "supervised-actor")

  override def onMessage(msg: String): Behavior[String] =
    msg match {
      case "failChild" =>
        child ! "fail"
        this
    }
}

object SupervisedActor {
  def apply(): Behavior[String] =
    Behaviors.setup(context => new SupervisedActor(context))
}

class SupervisedActor(context: ActorContext[String]) extends AbstractBehavior[String](context) {
  println("supervised actor started")

  override def onMessage(msg: String): Behavior[String] =
    msg match {
      case "fail" =>
        println("supervised actor fails now")
        // in this case we are throwing an exception!!! still -> matching with behavior[String] signature
        throw new Exception("I failed!")
    }

  override def onSignal: PartialFunction[Signal, Behavior[String]] = {
    case PreRestart =>
      println("supervised actor will be restarted")
      this
    case PostStop =>
      println("supervised actor stopped")
      this
  }

}

object MainForFailureHandlingActor {
  def apply(): Behavior[String] =
    Behaviors.setup(new MainForFailureHandlingActor(_))
}

class MainForFailureHandlingActor(context: ActorContext[String]) extends AbstractBehavior[String](context) {
  override def onMessage(msg: String): Behavior[String] =
    msg match {
      case "start" =>

        val supervisingActor = context.spawn(SupervisingActor(), "supervising-actor")
        println(s"supervisingActor: $supervisingActor")
        supervisingActor ! "failChild"
        this
    }
}

object ActorHierarchyExperiments extends App {
  val testSystem = ActorSystem(Main(), "testSystem")
  testSystem ! "start"

  // The output is the following
  // ---> Note actors are created under testSystem/user , where testSystem is root guardian. This is the parent of all actors in the system,
  // and the last one to stop when the system itself is terminated
  // This is the top level actor that you provide to start all other actors in your application

  //First: Actor[akka://testSystem/user/first-actor#-63264161]
  //Second: Actor[akka://testSystem/user/first-actor/second-actor#-107121719]
  // --> #-63264161 is the unique identifier, can be ignored

  // ########## Let's focus on the Actor Life Cycle #####
  val testActorLifeCycleSystem = ActorSystem(MainActorLifecycleTest(), "testActorLifeCycleSystem")
  testActorLifeCycleSystem ! "start"
  // first started
  //First: Actor[akka://testActorLifeCycleSystem/user/first#133905354]
  //second started
  //second stopped
  //first stopped

  // ####### Let's focus on the Failure handling #######
  // The supervision strategy is typically defined by the parent actor when it spawns a child actor.
  // In this way, parents act as supervisors for their children.
  // The default supervisor strategy is to stop the child. If you don’t define the strategy all failures result in a stop.

  val testFailureHandlingActor = ActorSystem(MainForFailureHandlingActor(), "testFailureHandlingActor")
  testFailureHandlingActor ! "start"

  // The following is expected
  // supervisingActor: Actor[akka://testFailureHandlingActor/user/supervising-actor#1999759868]
  //supervised actor started
  //supervised actor fails now
  //supervised actor will be restarted
  //[2022-01-04 15:19:45,643] [ERROR] [akka.actor.typed.Behavior$] [testFailureHandlingActor-akka.actor.default-dispatcher-3] [akka://testFailureHandlingActor/user/supervising-actor/supervised-actor] - Supervisor RestartSupervisor saw failure: I failed!
  //java.lang.Exception: I failed!
  //	at com.example.SupervisedActor.onMessage(ActorHierarchyExperiments.scala:197)
  //	at com.example.SupervisedActor.onMessage(ActorHierarchyExperiments.scala:190)
  //	at akka.actor.typed.scaladsl.AbstractBehavior.receive(AbstractBehavior.scala:84)
  //	at akka.actor.typed.Behavior$.interpret(Behavior.scala:274)
  //	at akka.actor.typed.Behavior$.interpretMessage(Behavior.scala:230)
  //	at akka.actor.typed.internal.InterceptorImpl$$anon$2.apply(InterceptorImpl.scala:57)
  //	at akka.actor.typed.internal.RestartSupervisor.aroundReceive(Supervision.scala:275)
  //	at akka.actor.typed.internal.InterceptorImpl.receive(InterceptorImpl.scala:85)
  //	at akka.actor.typed.Behavior$.interpret(Behavior.scala:274)
}
