//#full-example
package com.example


import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import com.example.GreeterMain.SayHello

// https://doc.akka.io/docs/akka/current/typed/actors.html?_ga=2.198636527.1194207410.1641331526-1275753416.1629355619
//#greeter-actor
object Greeter {
  // The Greet type contains not only the information of whom to greet,
  //  it also holds an ActorRef that the sender of the message supplies so that the HelloWorld Actor can send back the confirmation message.
  final case class Greet(whom: String, replyTo: ActorRef[Greeted])
  final case class Greeted(whom: String, from: ActorRef[Greet])

  def apply(): Behavior[Greet] = {
  // The behavior of the Actor is defined as the Greeter with the help of the receive behavior factory
    Behaviors.receive { (context, message) =>
      // The type of the messages handled by this behavior is declared to be of class Greet,
      //  meaning that message argument is also typed as such

      context.log.info("Hello {}!", message.whom)
      //#greeter-send-messages
      // message.replyTo is an ActorRef that takes in the Behaviors[Greet]
      message.replyTo ! Greeted(message.whom, context.self)
      //#greeter-send-messages
      Behaviors.same
    }
  }
}
//#greeter-actor

//#greeter-bot
object GreeterBot {

  // ActorRef[Greeted] from case class Greet(whom: String, replyTo: ActorRef[Greeted])
  def apply(max: Int): Behavior[Greeter.Greeted] = {
    // No sending messages to other actors
    // No spawning child actors
    // This is a case where a computation is performed
    // -> It calls finish with Behavior.stopped defined in the bot( method

    bot(0, max)
  }

  // 1 actor will recursively call bot() max # of times
  // Proof is the following:
  //[2022-01-04 17:23:27,329] [INFO] [com.example.GreeterBot$] [AkkaQuickStart-akka.actor.default-dispatcher-3] [akka://AkkaQuickStart/user/Stephen] - Greeting 1/3 times for Stephen for the actor com.example.GreeterBot$@4a0fa5fc
  //[2022-01-04 17:23:27,329] [INFO] [com.example.Greeter$] [AkkaQuickStart-akka.actor.default-dispatcher-7] [akka://AkkaQuickStart/user/greeter] - Hello Stephen!
  //[2022-01-04 17:23:27,329] [INFO] [com.example.GreeterBot$] [AkkaQuickStart-akka.actor.default-dispatcher-3] [akka://AkkaQuickStart/user/Stephen] - Greeting 2/3 times for Stephen for the actor com.example.GreeterBot$@4a0fa5fc
  //[2022-01-04 17:23:27,329] [INFO] [com.example.Greeter$] [AkkaQuickStart-akka.actor.default-dispatcher-7] [akka://AkkaQuickStart/user/greeter] - Hello Stephen!
  //[2022-01-04 17:23:27,330] [INFO] [com.example.GreeterBot$] [AkkaQuickStart-akka.actor.default-dispatcher-7] [akka://AkkaQuickStart/user/Stephen] - Greeting 3/3 times for Stephen for the actor com.example.GreeterBot$@4a0fa5fc

  private def bot(greetingCounter: Int, max: Int): Behavior[Greeter.Greeted] =
    Behaviors.receive { (context, message) =>
      val n = greetingCounter + 1
      context.log.info("Greeting {}/{} times for {} for the actor {}", n, max, message.whom, this)
      if (n == max) {
        Behaviors.stopped
      } else {
        // message.from is Actor that takes Behavior[Greet]
        // -> context.self vs this????
        // This is a behavior
        // context.self is the actor
        context.log.info("context.self {} vs this {}", context.self, this)
        // The output is (context.self is Actor[... )
        // Actor[akka://AkkaQuickStart/user/Stephen-ActorBeingGreeted#937196547] vs com.example.GreeterBot$@4687ae3f

        message.from ! Greeter.Greet(message.whom, context.self)
        // Recursion for the next invocation
        bot(n, max)
      }
    }
}
//#greeter-bot

//#greeter-main
object GreeterMain {

  final case class SayHello(name: String)

  def apply(): Behavior[SayHello] =
    Behaviors.setup { context =>
      //#create-actors
      val greeter = context.spawn(Greeter(), "greeter")
      //#create-actors

      Behaviors.receiveMessage { message =>
        //#create-actors
        // --> message is SayHello class, ---> note message.name
        // Note this is using 'message.name' + '-ActorBeingGreeted' as the context name for the actor
        val replyTo = context.spawn(GreeterBot(max = 3), message.name + "-ActorBeingGreeted")

        //#create-actors
        greeter ! Greeter.Greet(message.name, replyTo)
        Behaviors.same
      }
    }
}
//#greeter-main

//#main-class
object AkkaQuickstart extends App {
  //#actor-system
  val greeterMain: ActorSystem[GreeterMain.SayHello] = ActorSystem(GreeterMain(), "AkkaQuickStart")
  //#actor-system

  //#main-send-messages
  // no space allowed in the message as it's being used to creating the actor being greeted
  greeterMain ! SayHello("Stephen")
  greeterMain ! SayHello("Hello-World")


  //#main-send-messages
}
//#main-class
//#full-example
