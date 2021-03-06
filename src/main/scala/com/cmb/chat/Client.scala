package com.cmb.chat

import java.net.InetSocketAddress

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.io.{IO, Tcp}
import akka.util.ByteString

import scala.io.StdIn
import scala.util.{Random, Success}

object Client {
  def props(remote: InetSocketAddress, logging: Boolean) = Props(classOf[Client], remote, logging)
}

class Client(remote: InetSocketAddress, logging: Boolean) extends Actor {

  import Tcp._
  import context.system

  IO(Tcp) ! Connect(remote)

  var received = 0
  var last_tick = System.nanoTime()

  val chatProtocolParser: ChatProtocolParser = new ChatProtocolParser()

  def receive = {
    case CommandFailed(_: Connect) =>
      context stop self
    case c @ Connected(remote, local) =>
      val connection = sender()
      connection ! Register(self)
      context become {
        case data: ByteString =>
          connection ! Write(data)
        case CommandFailed(w: Write) =>
          system.log.error("Write to server failed!")
        case Received(data) =>
          if (logging) {
            chatProtocolParser.add(data)
            chatProtocolParser.popMessage match {
              case msg: Success[MessageCommand] => context.system.log.info(msg.value.message)
            }
          }
        case "close" =>
          connection ! Close
        case _: ConnectionClosed =>
          context stop self
      }
  }

}

object ClientRunner extends App {

  implicit val actorSystem = ActorSystem("client-system")
  actorSystem.log.info("Starting client...")
  val clientsSize = 20
  val clients: List[(ActorRef, Int)] = (for (i <- 1 to clientsSize) yield (actorSystem.actorOf(Client.props(new InetSocketAddress("localhost", 9090), false)), i)).toList
  Thread.sleep(1000)
  actorSystem.log.info("Sending idents")
  clients.foreach { case (client: ActorRef, i: Int) =>
    val ident = Random.alphanumeric.take(6).mkString
    actorSystem.log.info(s"Client $i sending ident $ident")
    client ! ChatProtocolCommand.serialize(IdentCommand(ident))
    Thread.sleep(100)
  }
  Thread.sleep(1000)
  actorSystem.log.info("Sending chan joins")
  Thread.sleep(100)
  clients.foreach {case (client: ActorRef, id: Int) =>
    client ! ChatProtocolCommand.serialize(JoinCommand(s"${id % (clientsSize / 2)}"))
    Thread.sleep(500)
  }
  Thread.sleep(1000)

  val threads = clients.map { client =>
    new Thread(new Runnable {
      override def run(): Unit = {
        var i = 0
        val channel = s"${client._2 % (clientsSize / 2)}"
        while (true) {
          client._1 ! ChatProtocolCommand.serialize(MessageCommand(channel, s"Message number $i"))
          i += 1
          Thread.sleep(Random.nextInt(5))
        }
      }
    })
  }

  threads.foreach { t => t.start() }

  StdIn.readLine()
}

object CommandLineRunner extends App {

  implicit val actorSystem = ActorSystem("client-system")
  val ident = StdIn.readLine("Set nick: ")
  val chan = StdIn.readLine("Set Chan: ")

  actorSystem.log.info(s"Joining channel $chan as $ident")
  val client = actorSystem.actorOf(Client.props(new InetSocketAddress("localhost", 9090), true))
  Thread.sleep(1000)
  client ! ChatProtocolCommand.serialize(IdentCommand(ident))
  Thread.sleep(1000)
  client ! ChatProtocolCommand.serialize(JoinCommand(chan))
  Thread.sleep(10)
  var input = StdIn.readLine("> ")
  while (input != "/exit") {
    if (!input.isEmpty) client ! ChatProtocolCommand.serialize(MessageCommand(chan, input))
    input = StdIn.readLine("> ")
  }
  client ! ChatProtocolCommand.serialize(LeaveCommand(chan))
  Thread.sleep(500)
  actorSystem.terminate().value
}