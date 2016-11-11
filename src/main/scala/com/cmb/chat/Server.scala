package com.cmb.chat

import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit

import akka.actor._
import akka.io.{IO, Tcp}
import akka.pattern.AskableActorSelection
import akka.util.{Timeout, ByteString}

import scala.io.StdIn
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Success, Failure}

/**
  * Created by ryan on 11/4/2016.
  */
class Server extends Actor {

  import Tcp._
  import context.system

  IO(Tcp) ! Bind(self, new InetSocketAddress("localhost", 9090))

  def receive = {
    case b @ Bound(localAddress) =>
      system.log.info("Server bound on {}", localAddress)
    case CommandFailed(_: Bind) =>
      system.log.error("Failed to bind! Exiting...")
      context stop self
    case c @ Connected(remote, local) =>
      system.log.info("Connection received from {}", remote)
      val handler = context.actorOf(Props[ClientHandler])
      val connection = sender()
      connection ! Register(handler)
  }

}

class ClientHandler extends Actor {

  import Tcp._
  import context.system

  var clientIdent: Option[String] = Option.empty

  implicit val timeout = Timeout(1, TimeUnit.SECONDS)

  var lastSender: ActorRef = null

  val parser: ChatProtocolParser = new ChatProtocolParser()

  def receive = {
    case Received(data) =>
      lastSender = sender()
      parser.add(data)
      parser.popMessage.map {
        case m: MessageCommand => clientIdent.foreach { ident =>
            system.actorSelection(s"/user/${m.channel}").resolveOne().foreach { ref =>
              ref ! ChannelMessage(ident, m.message)
            }
          }
          m
        case i: IdentCommand => clientIdent = Option(i.identity)
          i
        case j: JoinCommand =>
          val sel = system.actorSelection(s"/user/${j.channel}")
          val asker: AskableActorSelection  = new AskableActorSelection(sel)
          asker.ask(new Identify(1)).map { i =>
            val ref = i.asInstanceOf[ActorIdentity].getRef
            Option(ref) match {
              case None =>
                clientIdent.foreach { ident =>
                  system.log.info("Creating new channel: {}", j.channel)
                  val channelHandler = ChannelHandler.createChannel(j.channel)
                  system.log.info("Created new channel {} at path {}", j.channel, channelHandler.path)
                  channelHandler ! UserJoinMessage(ident, self)
                }
              case Some(channelRef) =>
                clientIdent.foreach { ident =>
                  system.log.info("User {} joined channel {}", ident, j.channel)
                  channelRef ! UserJoinMessage(ident, self)
                }
            }
          }
          j
        case l: LeaveCommand => clientIdent.foreach { ident =>
            system.log.info("User {} left channel {}", ident, l.channel)
            system.actorSelection(s"/user/${l.channel}").resolveOne().map(_ ! UserLeaveMessage(ident))
          }

      } match {
        case Failure(t) => system.log.error(t, "Failed parsing message")
        case Success(something) =>
      }
    case PeerClosed => context stop self
    case b: ByteString =>
      Option(b) match {
        case Some(_) => lastSender ! Write(b)
        case None => system.log.warning("Client handler sent null bytestring.")
      }
  }
}

object ChannelHandler {
  def createChannel(channel: String)(implicit system: ActorSystem): ActorRef = {
    this.synchronized {
      system.actorOf(Props[ChannelHandler], channel)
    }
  }
}

class ChannelHandler extends Actor {

  import context.system

  var userMap = Map.empty[String, ActorRef]

  val stats = context.actorSelection("/user/stats")

  def receive = {
    case msg: ChannelMessage =>
      if (userMap.contains(msg.from)) {
        userMap.values.foreach {

          _ ! ChatProtocolCommand.serialize(MessageCommand(msg.from, msg.formattedMessage))
        }
      } else {
        system.log.warning("Dropping message from user not in channel")
      }
      stats ! 1
    case join: UserJoinMessage =>
      userMap.values.foreach {

        _ ! ChatProtocolCommand.serialize(MessageCommand("System", SystemMessage(s"${join.name} has joined the channel").formattedMessage))
      }
      userMap += join.name -> join.client
      stats ! 1
    case leave: UserLeaveMessage =>
      userMap -= leave.name
      userMap.values.foreach {
        _ ! ChatProtocolCommand.serialize(MessageCommand("System", SystemMessage(s"${leave.name} has left the channel").formattedMessage))
      }
      stats ! 1
  }
}

case class UserJoinMessage(name: String, client: ActorRef)
case class UserLeaveMessage(name: String)
case class ChannelMessage(from: String, message: String) {
  def formattedMessage: String = {
    s"[$from]: $message"
  }
}

object SystemMessage {
  def apply(msg: String): ChannelMessage = ChannelMessage("System", msg)
}

class StatsActor extends Actor {
  var counter: Int = 0
  var lastChunk = System.currentTimeMillis()
  def receive = {
    case i: Int =>
      counter += 1
      val now = System.currentTimeMillis()
      if (now - lastChunk > 60000) {
        val seconds = (now-lastChunk) / 1000
        context.system.log.info(s"$counter messages processed in $seconds seconds. ${counter.toFloat / seconds.toFloat}/s avg")
        lastChunk = now
      }
  }

}

object Server extends App {

  implicit val actorSystem = ActorSystem("chat-system")
  actorSystem.log.info("Starting server...")
  val serverActor = actorSystem.actorOf(Props[Server])
  val statsActor = actorSystem.actorOf(Props[StatsActor], "stats")

  actorSystem.log.info("Press return to exit...")

  StdIn.readLine()
  serverActor ! Kill
  actorSystem.terminate()
}