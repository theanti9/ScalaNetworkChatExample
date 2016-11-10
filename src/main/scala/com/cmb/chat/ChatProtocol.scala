package com.cmb.chat

import java.nio.ByteOrder

import akka.util.ByteString

import scala.util.{Failure, Try}

object ChatProtocol {
  // Protocol frames must start with this Magic
  val Magic: ByteString = ByteString(0x01, 0x02, 0x03, 0x04)

  // After the Magic, the command type should be specified by one of the following bytes
  object Commands {
    val Ident = 0x08
    val Join = 0x09
    val Leave = 0x0A
    val Message = 0x0B
  }
}

abstract class ChatProtocolCommand(val byteLength: Int)
case object ChatProtocolCommand {
  def parse(byteString: ByteString): Try[ChatProtocolCommand] = {
    if (byteString.startsWith(ChatProtocol.Magic)) {
      Try(byteString(4)).flatMap {
        case ChatProtocol.Commands.Ident => IdentCommand.parse(byteString.drop(5))
        case ChatProtocol.Commands.Join => JoinCommand.parse(byteString.drop(5))
        case ChatProtocol.Commands.Leave => LeaveCommand.parse(byteString.drop(5))
        case ChatProtocol.Commands.Message => MessageCommand.parse(byteString.drop(5))
        case _ => Failure(new Exception("Unidentified command byte."))
      }
    } else {
      Failure(new Exception("Buffer doesn't start with Magic."))
    }
  }

  def serialize(chatProtocolCommand: ChatProtocolCommand): ByteString = {
    ChatProtocol.Magic ++
      (chatProtocolCommand match {
        case m: MessageCommand => ByteString(ChatProtocol.Commands.Message) ++ MessageCommand.serialize(m)
        case i: IdentCommand => ByteString(ChatProtocol.Commands.Ident) ++ IdentCommand.serialize(i)
        case j: JoinCommand => ByteString(ChatProtocol.Commands.Join) ++ JoinCommand.serialize(j)
        case l: LeaveCommand => ByteString(ChatProtocol.Commands.Leave) ++ LeaveCommand.serialize(l)
      })
  }
}

case class IdentCommand(identity: String, override val byteLength: Int = 0) extends ChatProtocolCommand(byteLength)
case object IdentCommand {
  implicit val byteOrder: ByteOrder = ByteOrder.LITTLE_ENDIAN

  def parse(byteString: ByteString): Try[IdentCommand] = Try {
    val byteIterator = byteString.iterator
    val length = byteIterator.getByte.toInt
    IdentCommand(byteIterator.getByteString(length).utf8String, length + 1)
  }

  def serialize(identCommand: IdentCommand): ByteString = {
    val builder = ByteString.newBuilder
    builder.putByte(identCommand.identity.length.toByte)
      .putBytes(identCommand.identity.getBytes)
      .result()
  }
}

case class JoinCommand(channel: String, override val byteLength: Int = 0) extends ChatProtocolCommand(byteLength)
case object JoinCommand {
  implicit val byteOrder: ByteOrder = ByteOrder.LITTLE_ENDIAN

  def parse(byteString: ByteString): Try[JoinCommand] = Try {
    val byteIterator = byteString.iterator
    val length = byteIterator.getByte.toInt
    JoinCommand(byteIterator.getByteString(length).utf8String, length + 1)
  }

  def serialize(joinCommand: JoinCommand): ByteString = {
    val builder = ByteString.newBuilder
    builder.putByte(joinCommand.channel.length.toByte)
      .putBytes(joinCommand.channel.getBytes)
      .result()
  }
}

case class LeaveCommand(channel: String, override val byteLength: Int = 0) extends ChatProtocolCommand(byteLength)
case object LeaveCommand {
  implicit val byteOrder: ByteOrder = ByteOrder.LITTLE_ENDIAN

  def parse(byteString: ByteString): Try[LeaveCommand] = Try {
    val byteIterator = byteString.iterator
    val length = byteIterator.getByte.toInt
    LeaveCommand(byteIterator.getByteString(length).utf8String, length + 1)
  }

  def serialize(leaveCommand: LeaveCommand): ByteString = {
    val builder = ByteString.newBuilder
    builder.putByte(leaveCommand.channel.length.toByte)
      .putBytes(leaveCommand.channel.getBytes)
      .result()
  }
}

case class MessageCommand(channel: String, message: String, override val byteLength: Int = 0) extends ChatProtocolCommand(byteLength)
case object MessageCommand {
  implicit val byteOrder: ByteOrder = ByteOrder.LITTLE_ENDIAN

  def parse(byteString: ByteString): Try[MessageCommand] = Try {
    val byteIterator = byteString.iterator
    val channelLength = byteIterator.getByte.toInt
    val channelName = byteIterator.getByteString(channelLength).utf8String
    val messageLength = byteIterator.getShort.toInt
    val message = byteIterator.getByteString(messageLength).utf8String
    MessageCommand(channelName, message, channelLength + messageLength + 3)
  }

  def serialize(messageCommand: MessageCommand): ByteString = {
    val builder = ByteString.newBuilder
    builder.putByte(messageCommand.channel.length.toByte)
      .putBytes(messageCommand.channel.getBytes)
      .putShort(messageCommand.message.length)
      .putBytes(messageCommand.message.getBytes)
      .result()
  }
}


class ChatProtocolParser {

  var buffer: ByteString = ByteString()

  def add(byteString: ByteString): Unit = {
    buffer = buffer ++ byteString
  }

  def popMessage: Try[ChatProtocolCommand] = {
    ChatProtocolCommand.parse(buffer).map { cmd =>
      buffer = buffer.drop(cmd.byteLength + 5)
      cmd
    }
  }
}
