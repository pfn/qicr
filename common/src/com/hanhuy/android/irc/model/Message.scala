package com.hanhuy.android.irc.model

import com.hanhuy.android.irc.{Setting, Settings}

import java.util.Date

object MessageLike {
    object ChatMessage {
        def unapply(in: ChatMessage) = Some((in.sender, in.message))
    }
    trait ChatMessage extends MessageLike {
        def sender: String
        def message: String
    }
    case class Query(ts: Date = new Date) extends MessageLike
    case class Kick(op: String, nick: String, msg: String, ts: Date = new Date) extends MessageLike
    case class Join(nick: String, uhost: String, ts: Date = new Date) extends MessageLike
    case class Part(nick: String, uhost: String, msg: String, ts: Date = new Date)
    extends MessageLike
    case class Quit(nick: String, uhost: String, msg: String, ts: Date = new Date)
    extends MessageLike

    case class CtcpRequest(server: Server, target: String, cmd: String,
      args: Option[String], ts: Date = new Date)
    extends MessageLike
    case class CtcpReply(server: Server, src: String, cmd: String,
      args: Option[String], ts: Date = new Date) extends MessageLike
    case class NickChange(oldnick: String, newnick: String, ts: Date = new Date) extends MessageLike
    case class CommandError(message: String, ts: Date = new Date) extends MessageLike
    case class ServerInfo(message: String, ts: Date = new Date) extends MessageLike
    case class SslInfo(message: String, ts: Date = new Date) extends MessageLike
    case class SslError(message: String, ts: Date = new Date) extends MessageLike
    case class Motd(message: String, ts: Date = new Date) extends MessageLike
    case class Topic(sender: Option[String], topic: String, ts: Date = new Date) extends MessageLike
    case class Whois(whois: CharSequence, ts: Date = new Date) extends MessageLike

    // too hard to reference R.string, hardcode <> -- and *
    case class Privmsg(sender: String, message: String,
            op: Boolean = false, voice: Boolean = false, ts: Date = new Date) extends MessageLike
    with ChatMessage {
        override def toString = "<" +
                (if (op) "@" else if (voice) "+" else "") +
                sender + "> " + message
    }
    case class CtcpAction(sender: String, message: String, ts: Date = new Date) extends MessageLike
    with ChatMessage {
        override def toString = " * " + sender + " " + message
    }
    case class Notice(sender: String, message: String, ts: Date = new Date) extends MessageLike
    with ChatMessage {
        override def toString = "-" + sender + "- " + message
    }
}

trait MessageLike {
    def ts: Date
}

trait BusEvent extends com.hanhuy.android.common.BusEvent
object BusEvent {
  case object ExitApplication extends BusEvent
  case object IrcManagerStart extends BusEvent
  case object IrcManagerStop extends BusEvent
  case object MainActivityStart extends BusEvent
  case object MainActivityDestroy extends BusEvent
  case object MainActivityStop extends BusEvent
  case class ChannelStatusChanged(channel: ChannelLike) extends BusEvent
  case class ServerChanged(server: Server) extends BusEvent
  case class ServerAdded(server: Server) extends BusEvent
  case class ServerRemoved(server: Server) extends BusEvent
  case class ServerStateChanged(
      server: Server, oldstate: Server.State) extends BusEvent
  case class PreferenceChanged(settings: Settings, pref: Setting[_])
  extends BusEvent
  case class ServerMessage(server: Server, msg: MessageLike) extends BusEvent
  case class ChannelMessage(channel: ChannelLike, msg: MessageLike)
    extends BusEvent
  case class ChannelStateChanged(channel: Channel, oldstate: Channel.State)
    extends BusEvent
  case class NickListChanged(channel: Channel) extends BusEvent
  case class NickChanged(channel: Channel, oldnick: String, newnick: String)
  extends BusEvent
  case class ChannelAdded(channel: Channel) extends BusEvent
  case class StartQuery(query: Query) extends BusEvent
  case class PrivateMessage(query: Query, msg: MessageLike) extends BusEvent
}
