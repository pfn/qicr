package com.hanhuy.android.irc.model

import com.hanhuy.android.irc.IrcService

import java.util.Date

object MessageLike {
    object ChatMessage {
        def unapply(in: ChatMessage) = Some((in.sender, in.message))
    }
    trait ChatMessage extends MessageLike {
        def sender: String
        def message: String
    }
    case class Kick(op: String, nick: String, msg: String) extends MessageLike
    case class Join(nick: String, uhost: String) extends MessageLike
    case class Part(nick: String, uhost: String, msg: String)
    extends MessageLike
    case class Quit(nick: String, uhost: String, msg: String)
    extends MessageLike

    case class NickChange(oldnick: String, newnick: String) extends MessageLike
    case class CommandError(message: String) extends MessageLike
    case class ServerInfo(message: String) extends MessageLike
    case class SslInfo(message: String) extends MessageLike
    case class SslError(message: String) extends MessageLike
    case class Motd(message: String) extends MessageLike
    case class Topic(chan: String, sender: Option[String],
            topic: String) extends MessageLike

    // too hard to reference R.string, hardcode <> -- and *
    case class Privmsg(sender: String, message: String,
            op: Boolean = false, voice: Boolean = false) extends MessageLike
    with ChatMessage {
        override def toString = "<" +
                (if (op) "@" else if (voice) "+" else "") +
                sender + "> " + message
    }
    case class CtcpAction(sender: String, message: String) extends MessageLike
    with ChatMessage {
        override def toString = " * " + sender + " " + message
    }
    case class Notice(sender: String, message: String) extends MessageLike
    with ChatMessage {
        override def toString = "-" + sender + "- " + message
    }
}

trait MessageLike {
    val ts: Date = new Date()
}

trait BusEvent
object BusEvent {
    case class ServerChanged(server: Server) extends BusEvent
    case class ServerAdded(server: Server) extends BusEvent
    case class ServerRemoved(server: Server) extends BusEvent
    case class ServerStateChanged(
        server: Server, oldstate: Server.State.State) extends BusEvent
    case class PreferenceChanged(pref: String) extends BusEvent
    case class ServiceConnected(service: IrcService) extends BusEvent
    case class ServiceDisconnected() extends BusEvent
    case class ChannelMessage(
        channel: ChannelLike, msg: MessageLike) extends BusEvent
    case class ChannelStateChanged(
        channel: Channel, oldstate: Channel.State.State) extends BusEvent
}
