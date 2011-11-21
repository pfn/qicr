package com.hanhuy.android.irc.model

import java.util.Date

object MessageLike {
    case class CommandError(message: String) extends MessageLike
    case class ServerInfo(message: String) extends MessageLike
    case class SslInfo(message: String) extends MessageLike
    case class SslError(message: String) extends MessageLike
    case class Motd(message: String) extends MessageLike

    // too hard to reference R.string, hardcode <> -- and *
    case class Privmsg(sender: String, message: String) extends MessageLike {
        override def toString = "<" + sender + "> " + message
    }
    case class CtcpAction(sender: String, message: String) extends MessageLike {
        override def toString = " * " + sender + " " + message
    }
    case class Notice(sender: String, message: String) extends MessageLike {
        override def toString = "-" + sender + "- " + message
    }
}

abstract class MessageLike {
    val ts: Date = new Date()
}
