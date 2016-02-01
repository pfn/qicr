package com.hanhuy.android.irc

import android.app.NotificationManager
import com.hanhuy.android.irc.model.BusEvent
import com.hanhuy.android.irc.model.Server
import com.hanhuy.android.irc.model.ChannelLike
import com.hanhuy.android.irc.model.{Channel => QicrChannel}
import com.hanhuy.android.irc.model.MessageAdapter
import com.hanhuy.android.irc.model.MessageLike._

import android.widget.Toast

import android.util.Log

import com.sorcix.sirc._
import com.sorcix.sirc.event.MessageEventListener.{Message, Action}
import com.sorcix.sirc.event.ServerEventListener.{Invite, Nick, Mode}
import com.sorcix.sirc.event.{ServerEventListener, MessageEventListener}

import scala.util.Try
import com.hanhuy.android.common._
import SpannedGenerator._
import IrcListeners._
import scala.annotation.tailrec
import android.text.SpannableStringBuilder
import com.hanhuy.android.irc.model.MessageLike.Kick
import com.hanhuy.android.irc.model.MessageLike.Privmsg
import com.hanhuy.android.irc.model.MessageLike.Join
import com.hanhuy.android.irc.model.MessageLike.Motd
import com.hanhuy.android.irc.model.MessageLike.Part
import com.hanhuy.android.irc.model.MessageLike.Quit
import com.hanhuy.android.irc.model.MessageLike.CtcpAction
import com.hanhuy.android.irc.model.MessageLike.CtcpReply
import com.hanhuy.android.irc.model.MessageLike.NickChange
import com.hanhuy.android.irc.model.MessageLike.ServerInfo
import com.hanhuy.android.irc.model.MessageLike.Topic
import com.hanhuy.android.irc.model.MessageLike.Notice

object IrcListeners {
  val TAG = "IrcListeners"
  @tailrec
  private def matchesNickIndex(nick: String, m: String, cur: Int): Int = {
    val mlen = m.length
    val nlen = nick.length
    val idx = m.indexOf(nick, cur)
    if (idx < 0) idx else {
      if (idx > 0) { // not at start of line
        val before = !Character.isJavaIdentifierPart(m.charAt(idx - 1))
        if (idx + nlen < mlen) {
          if (before && !Character.isJavaIdentifierPart(m.charAt(idx + nlen)))
            idx
          else
            matchesNickIndex(nick, m, idx + nlen)
        } else if (before) idx else -1
      } else {
        // beginning of line
        if (nlen < mlen) {
          if (!Character.isJavaIdentifierPart(m.charAt(nlen)))
            idx
          else
            matchesNickIndex(nick, m, nlen)
        } else idx
      }
    }
  }

  // sometimes a null is passed in...
  def matchesNick(server: Server, msg: CharSequence) = {
    msg.?.fold(false)(m =>
      matchesNickIndex(server.currentNick.toLowerCase, m.toString.toLowerCase, 0) != -1)
  }

  implicit class EnhancedUser(val u: User) extends AnyVal {
    @inline def address = u.getUserName + "@" + u.getHostName
  }

  @inline implicit def toQicrChannel(c: ChannelLike): QicrChannel = c.asInstanceOf[QicrChannel]
}
class IrcListeners(manager: IrcManager) extends AdvancedListener
with ModeListener with ServerEventListener with MessageEventListener {
  // ServerListener
  // TODO send BusEvents instead
  override def onConnect(c: IrcConnection) {
    manager._connections.get(c) match {
      case Some(server) =>
        UiBus.run {
          // ugh, need to do it here so that the auto commands can run
          server.state() = Server.State.CONNECTED
          server += ServerInfo(manager.getString(R.string.server_connected))
        }
        if (server.autorun.isDefined || server.autojoin.isDefined) {
          val proc = CommandProcessor(Application.context, null)
          proc.server = Some(server)
          server.autorun.foreach { autorun =>
            autorun.split(";") foreach { cmd =>
              if (cmd.trim().length() > 0) {
                val command = cmd.trim()
                UiBus.run {
                  proc.executeLine(
                    if (command.charAt(0) != '/') "/" + command else command)
                }
              }
            }
          }
          server.autojoin.foreach { autojoin =>
            val join = manager.getString(R.string.command_join_1)
            val channels = autojoin.split(";")
            channels foreach { c =>
              if (c.trim().length() > 0)
                UiBus.run {
                  proc.executeCommand(join, Some(c.trim()))
                }
            }
          }
        }
        UiBus.run {
          NotificationCenter += ServerNotification(R.drawable.ic_wifi_black_24dp, server.name, "Connected")
        }
      case None =>
        Log.w(TAG, s"server not found in onConnect?! $c", new StackTrace)
    }
  }
  override def onDisconnect(c: IrcConnection) {
//    Log.w(TAG, "Connection dropped: " + c, new StackTrace)
    manager._connections.get(c) foreach { s =>
      manager.serverDisconnected(s)
      UiBus.run {
        NotificationCenter += ServerNotification(R.drawable.ic_error_black_24dp, s.name, "Disconnected")
      }
    }
  }

  override def onMotd(motd: ServerEventListener.Motd) {
    manager._connections.get(motd.connection) foreach { server =>
      // sIRC sends motd as one big blob of lines, split before adding
      UiBus.run { motd.motd.split("\r?\n") foreach { m => server += Motd(m) } }
    }
  }

    // ModeListener
    // TODO
  override def onAdmin(c: IrcConnection, channel: Channel,
      src: User, user: User) = ()
  override def onDeAdmin(c: IrcConnection, channel: Channel,
      src: User, user: User) = ()
  override def onDeFounder(c: IrcConnection, channel: Channel,
      src: User, user: User) = ()
  override def onDeHalfop(c: IrcConnection, channel: Channel,
      src: User, user: User) = ()
  override def onDeOp(c: IrcConnection, channel: Channel,
      src: User, user: User) = ()
  override def onDeVoice(c: IrcConnection, channel: Channel,
      src: User, user: User) = ()
  override def onFounder(c: IrcConnection, channel: Channel,
      src: User, user: User) = ()
  override def onHalfop(c: IrcConnection, channel: Channel,
      src: User, user: User) = ()
  override def onOp(c: IrcConnection, channel: Channel,
      src: User, user: User) = ()
  override def onVoice(c: IrcConnection, channel: Channel,
      src: User, user: User) = ()

  val CtcpPing = """(\d+)\s+(\d+)""".r

  def handleWhois(line: IrcPacket, start: Boolean = false) {
    val realNick = line.getArgumentsArray()(1)
    val nick = realNick.toLowerCase
    if (start)
      whoisBuffer += (nick -> List(line))
    else {
      if (whoisBuffer.contains(nick)) {
        whoisBuffer += (nick -> (whoisBuffer(nick) ++ List(line)))

        def whois(): Unit = {
          for {
            c <- manager.lastChannel
            w <- accumulateWhois(nick, realNick)
          } {
            UiBus.run(c += w)
          }
        }
        line.getNumericCommand match {
          case 318 => whois()
          case 369 => whois()
          case _ =>
        }
      }
    }
  }

  def whoisPrefix = "%1%2%3" formatSpans( textColor(0xff777777, "-"),
    textColor(0xff00ff00, "!"), textColor(0xff777777, "-"))
  def accumulateWhois(nick: String, realNick: String) = {
    whoisBuffer.get(nick).map { lines =>
      val b = lines.foldLeft(new SpannableStringBuilder) { (buf, line) =>
        val args = line.getArgumentsArray
        val m = line.getMessage
        line.getNumericCommand match {
          case 318 => // end of whois
          case 369 => // end of whowas
          case 311 =>
            val (user, host, name) = (args(2), args(3), m)
            val t = "%1 %2 [%3@%4]\n%6   name: %5" formatSpans(
              whoisPrefix, MessageAdapter.colorNick(realNick), user, host,
              bold(name), whoisPrefix)
            buf.append(t)
          case 314 =>
            val (user, host, name) = (args(2), args(3), m)
            val t = "%1 %2 was [%3@%4]\n%1   name: %5" formatSpans(
              whoisPrefix, MessageAdapter.colorNick(realNick), user, host, bold(name))
            buf.append(t)
          case 312 =>
            val (server, tag) = (args(2), m)
            val t = "\n%1   server: %2 [%3]" formatSpans(whoisPrefix, server, tag)
            buf.append(t)
          case 330 =>
            val (login, tag) = (args(2), m)
            val t = "\n%1   %3: %2" formatSpans(whoisPrefix, login, tag)
            buf.append(t)
          case 319 =>
            buf.append("\n%1   channels: %2" formatSpans(whoisPrefix, m))
          case 338 =>
            if (args.length > 2)
              buf.append("\n%1   %2: %3" formatSpans(whoisPrefix, m, args(2)))
            else
              buf.append("\n%1   %2" formatSpans(whoisPrefix, m))
          case 317 =>
            val (idle, time) = (args(2).toInt, args(3).toLong * 1000)
            buf.append("\n%1   %2: %3, %4" formatSpans(whoisPrefix, m,
              args(2), new java.util.Date(time).toString))
          case 401 =>
            buf.append("%1 %2: %3" formatSpans(whoisPrefix,
              MessageAdapter.colorNick(realNick), m))
          case 406 =>
            buf.append("%1 %2: %3" formatSpans(whoisPrefix,
              MessageAdapter.colorNick(realNick), m))
          case 671 =>
            buf.append("\n%1   %2" formatSpans(whoisPrefix, m))
          case _ =>
        }
        buf
      }
      whoisBuffer -= nick
      Whois(b)
    }
  }
  // AdvancedListener
  override def onUnknown(c: IrcConnection, line: IrcPacket) {
    manager._connections.get(c) foreach { server =>
      if (line.isNumeric) {
        // 306 = away
        // 333 = topic change info
        // 366 = end of /names list
        line.getNumericCommand match {
          case   5 => // server capabilities list
          case 251 => () // msg, network users info
          case 252 => () // args[1] ircops online
          case 254 => () // args[1] channel count
          case 255 => () // msg, local clients info
          case 261 => () // msg, highest connection count
          case 265 => () // args[1], local users, args[2], local max
          case 266 => () // args[1], global users, args[2], max
          case 301 => () // args[1], target nick, away msg (reply)
          case 305 => () // unaway (user)
          case 306 => () // nowaway (user)
          case 333 => // topic change timestamp
          // whois response line1
          // 1: nick, 2: username, 3: host, 4: ?; msg = realname
          case 311 => handleWhois(line, true)
          case 314 => handleWhois(line, true) // like 311, whowas
          case 312 => handleWhois(line) // 1: server, 2: server comment (whois l2)
          case 313 => () // 1: nick, whois operator
          case 318 => handleWhois(line) // args[1], nick, end of whois
          case 330 => handleWhois(line) // args[1] nick, args[2] login, msg/2
          case 319 => handleWhois(line) // args[1], nick, msg channels, whois
          case 338 => handleWhois(line) // args[2] host/ip, whois l3
          case 317 => handleWhois(line) // args[1], idle, args[2], signon whois l4
          case 369 => handleWhois(line) // args[1], nick, end of whowas
          case 401 => handleWhois(line, true) // args[1], nick, whois not there
          case 406 => handleWhois(line, true) // args[1], nick, whowas not there
          case 671 => handleWhois(line) // args[1], nick, is using a secure connection
          case 366 => // end of names list
          case 375 => // motd start
          case _ => ()
        }
      } else {
        line.getCommand match {
          case "PONG" =>
            for {
              ping <- server.currentPing
              p    <- Try((line.getMessage.? getOrElse
                line.getArgumentsArray()(1)).toLong).toOption
            } {
              server.currentPing = None
              // non-numeric should pass through
              // should always match if the lag timer is working
              if (p == ping) {
                val t = System.currentTimeMillis
                server.currentLag() = (t - p).toInt
                UiBus.send(BusEvent.ServerChanged(server))
              }
            }
          case _ => ()
        }
      }
      if (line.getCommand != "PONG") UiBus.run {
        server += ServerInfo("[%s](%s):[%s]" format (
          line.getCommand, line.getArguments,
          line.getMessage))
      }
    }
  }

  // Never happens
  override def onUnknownReply(c: IrcConnection, line: IrcPacket) = ()

  private var whoisBuffer: Map[String,List[IrcPacket]] = Map.empty

  override def onInvite(invite: Invite) = () // TODO

  override def onNick(nick: Nick): Unit = {
    val c = nick.connection
    val oldnick = nick.oldUser
    val newnick = nick.newUser
    val server = manager._connections(c)
    if (oldnick.isUs || newnick.isUs) {
      server.currentNick = newnick.getNick

      UiBus.run {
        manager._channels.values foreach { c =>
          if (c.server == server) {
            UiBus.send(BusEvent.NickListChanged(c))
            c += NickChange(oldnick.getNick, newnick.getNick, nick.timestamp)
          }
        }
      }
    } else {
      UiBus.run {
        manager.channels.values.collect {
          case c: Channel if c.hasUser(newnick) && manager._channels.get(c).isDefined =>
            manager._channels(c)
        }.toSeq.distinct foreach { c =>
          if (c.server == server) {
            UiBus.send(BusEvent.NickListChanged(c))
            c += NickChange(oldnick.getNick, newnick.getNick, nick.timestamp)
          }
        }
      }
    }
  }

  override def onQuit(quit: ServerEventListener.Quit): Unit = {
    val user = quit.sender
    val msg = quit.message
    val c = quit.connection
    if (!user.isUs) {
      manager._connections.get(c) foreach { server =>
        UiBus.run {
          try {
            // guard can change values if slow...
            manager.channels.values collect {
              case c: Channel if c.hasUser(user) => manager._channels.get(c)
            } foreach { c =>
              if (c.isDefined && c.get.server == server) {
                UiBus.send(BusEvent.NickListChanged(c.get))
                c.get += Quit(user.getNick, user.address, msg, quit.timestamp)
              }
            }
          } catch {
            case _: MatchError => ()
          }
        }
      }
    }
  }

  override def onMode(mode: Mode) = () // TODO

  override def onPart(part: ServerEventListener.Part): Unit = {
    val channel = part.channel
    val user = part.sender
    val msg = part.message
    if (manager._channels.contains(channel)) {
      UiBus.run {
        val ch = manager._channels(channel)
        if (user.isUs) {
          ch.state = QicrChannel.State.PARTED
        } else {
          UiBus.send(BusEvent.NickListChanged(ch))
        }
        ch += Part(user.getNick, user.address, msg, part.timestamp)
      }
    }
  }

  override def onTopic(topic: ServerEventListener.Topic): Unit = {
    val channel = topic.channel
    val src = topic.sender
    manager._channels.get(channel) foreach { c =>
      UiBus.run {
        c += Topic(src.?.map(_.getNick), topic.topic, topic.timestamp)
      }
    }
  }

  override def onKick(kick: ServerEventListener.Kick): Unit = {
    val channel = kick.channel
    val msg = kick.message
    val user = kick.target
    val op = kick.sender
    UiBus.run {
      manager._channels.get(channel) foreach { ch =>
        if (user.isUs) {
          // TODO update adapter's channel state
          ch.state = QicrChannel.State.KICKED
        } else {
          UiBus.send(BusEvent.NickListChanged(ch))
        }
        ch += Kick(op.getNick, user.getNick, msg, kick.timestamp)
      }
    }

  }

  override def onJoin(join: ServerEventListener.Join): Unit = {
    val user = join.sender
    val channel = join.channel
    val c = join.connection

    if (user.isUs)
      manager.addChannel(c, channel)
    UiBus.run {
      // sometimes channel hasn't been set yet (on connect)
      manager._channels.get(channel) foreach { ch =>
        UiBus.send(BusEvent.NickListChanged(ch))
        if (!user.isUs)
          ch += Join(user.getNick, user.address, join.timestamp)
      }
    }

  }

  override def onPrivateMessage(m: Message) {
    UiBus.run {
      manager.addQuery(
        m.connection, m.sender.getNick, m.message, ts = m.timestamp)
    }
  }

  override def onMessage(message: Message) {
    val channel = message.target
    val src = message.sender
    val msg = message.message
    manager._channels.get(channel) foreach {
      c =>
        if (src.isUs) {
          cancelNotifications(c)
        }

        val pm = Privmsg(src.getNick, msg, src.hasOperator, src.hasVoice, ts = message.timestamp)
        if (matchesNick(c.server, msg) && !src.isUs && !Config.Ignores(src.getNick))
          manager.addChannelMention(c, pm)

        UiBus.run {
          c += pm
        }
    }
  }

  override def onCtcpReply(ctcp: MessageEventListener.CtcpReply) {
    val c = ctcp.connection
    val src = ctcp.sender
    val cmd = ctcp.command
    val reply = ctcp.message
    val s = manager._connections(c)
    val r = CtcpReply(s, src.getNick, cmd, cmd match {
      case "PING" => reply match {
        case CtcpPing(seconds, micros) =>
          Try {
            // prevent malicious overflow causing a crash
            val ts = seconds.toLong * 1000 + (micros.toLong / 1000)
            Server.intervalString(System.currentTimeMillis - ts)
          }.toOption orElse reply.?
        case _ => reply.?
      }
      case _ => reply.?
    }, ctcp.timestamp)

    // TODO show in current WidgetChatActivity
    if (manager.showing) {
      UiBus.run {
        val msg = MessageAdapter.formatText(Application.context, r)
        MainActivity.instance foreach { activity =>
          val tab = activity.adapter.currentTab
          (tab.channel orElse tab.server).fold {
            s += r
            Toast.makeText(Application.context, msg, Toast.LENGTH_LONG).show()
          }{ _ += r }
        }
      }
    } else {
      s += r
    }
  }

  override def onNotice(n: MessageEventListener.Notice) {
    val chan = n.target.?
    val src = n.sender
    val msg = n.message
    chan.fold(UiBus.run {
      val sn = manager._connections.get(n.connection).map(_.name).getOrElse(n.connection.getServer.getAddress)
      NotificationCenter += NotifyNotification(
        n.timestamp, sn, src.getNick, s"-${MessageAdapter.colorNick(src.getNick)}- $msg")
    }) { channel =>
      val c = manager._channels(channel)
      val notice = Notice(src.getNick, msg, n.timestamp)
      UiBus.run { c += notice }
      if (matchesNick(c.server, msg) && !src.isUs && !Config.Ignores(src.getNick))
        manager.addChannelMention(c, notice)
    }
  }

  private def cancelNotifications(c: ChannelLike): Unit = {
    val nm = Application.context.systemService[NotificationManager]
    nm.cancel(IrcManager.MENTION_ID)
    nm.cancel(IrcManager.PRIVMSG_ID)
    c.newMessages = false
    c.newMentions = false
    ServiceBus.send(BusEvent.ChannelStatusChanged(c))
    manager.channels.keys filter (n =>
      n.newMentions && n.server == c.server && n != c) foreach {
      changed =>
        changed.newMentions = false
        ServiceBus.send(BusEvent.ChannelStatusChanged(changed))
    }
  }

  override def onAction(a: Action) {
    val chan = a.target.?
    val src = a.sender
    val msg = a.action

    chan.fold(UiBus.run {
      manager.addQuery(a.connection, src.getNick, msg,
        action = true, ts = a.timestamp)
    }) { channel =>
      manager._channels.get(channel) foreach { c =>
        if (src.isUs) {
          cancelNotifications(c)
        }
        val action = CtcpAction(src.getNick, msg, a.timestamp)
        UiBus.run {
          c += action
        }
        if (matchesNick(c.server, msg) && !src.isUs && !Config.Ignores(src.getNick))
          manager.addChannelMention(c, action)
      }
    }
  }
}
