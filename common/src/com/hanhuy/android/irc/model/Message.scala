package com.hanhuy.android.irc.model

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

abstract class MessageLike {
    val ts: Date = new Date()
}
