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
    case class Topic(sender: Option[String], topic: String, ts: Date = new Date, forceShow: Boolean = false) extends MessageLike
    case class Whois(whois: CharSequence, ts: Date = new Date) extends MessageLike

    // too hard to reference R.string, hardcode <> -- and *
    case class Privmsg(sender: String, message: String,
                       mode: UserMode = UserMode.NoMode, ts: Date = new Date) extends MessageLike
    with ChatMessage {
        override def toString = "<" +
                mode.prefix + sender + "> " + message
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

sealed trait UserMode extends Ordered[UserMode] {
  val order: Int
  val prefix: String

  override def compare(that: UserMode) = order - that.order
}

object UserMode {
  case object Admin extends UserMode {
    val order = 5
    val prefix = "&"
  }
  case object Founder extends UserMode {
    val order = 4
    val prefix = "~"
  }
  case object Op extends UserMode {
    val order = 3
    val prefix = "@"
  }
  case object HalfOp extends UserMode {
    val order = 2
    val prefix = "%"
  }
  case object Voice extends UserMode {
    val order = 1
    val prefix = "+"
  }
  case object NoMode extends UserMode {
    val order = 0
    val prefix = ""
  }
  def apply(admin: Boolean, founder: Boolean, op: Boolean, halfop: Boolean, voice: Boolean): UserMode = {
    if (admin) Admin
    else if (founder) Founder
    else if (op) Op
    else if (halfop) HalfOp
    else if (voice) Voice
    else NoMode
  }
}

sealed trait BusEvent extends com.hanhuy.android.common.BusEvent
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
  case object IgnoreListChanged extends BusEvent
  case class NickChanged(channel: Channel, oldnick: String, newnick: String)
  extends BusEvent
  case class ChannelAdded(channel: Channel) extends BusEvent
  case class StartQuery(query: Query) extends BusEvent
  case class PrivateMessage(query: Query, msg: MessageLike) extends BusEvent
  case class IMEShowing(showing: Boolean) extends BusEvent
  case class LinkClickEvent(url: String) extends BusEvent
  case object NewNotification extends BusEvent
  case object ReadNotification extends BusEvent
}
