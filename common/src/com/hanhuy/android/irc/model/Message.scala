package com.hanhuy.android.irc.model

import java.util.Date

abstract class MessageLike {
    val ts: Date = new Date()
}

case class ServerInfo(message: String) extends MessageLike
case class SslInfo(message: String) extends MessageLike
case class SslError(message: String) extends MessageLike
case class Privmsg(sender: String, message: String) extends MessageLike
case class CtcpAction(sender: String, message: String) extends MessageLike
case class Notice(sender: String, message: String) extends MessageLike
case class Motd(message: String) extends MessageLike
