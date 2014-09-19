package com.hanhuy.android.irc

import com.hanhuy.android.irc.model.BusEvent
import com.hanhuy.android.irc.model.Server
import com.hanhuy.android.irc.model.ChannelLike
import com.hanhuy.android.irc.model.{Channel => QicrChannel}
import com.hanhuy.android.irc.model.MessageAdapter
import com.hanhuy.android.irc.model.MessageLike._

import android.widget.Toast

import android.util.Log

import com.sorcix.sirc._

import scala.util.control.Exception._
import com.hanhuy.android.common.{UiBus, SpannedGenerator, AndroidConversions}
import AndroidConversions._
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
import scala.Some
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
    if (msg != null)
      matchesNickIndex(server.currentNick.toLowerCase, msg.toLowerCase, 0) != -1
    else
      false
  }

  class EnhancedUser(u: User) {
    def address = u.getUserName + "@" + u.getHostName
  }

  implicit def toEnhancedUser(u: User) = new EnhancedUser(u)
  implicit def toQicrChannel(c: ChannelLike) = c.asInstanceOf[QicrChannel]
}
class IrcListeners(manager: IrcManager) extends AdvancedListener
with ServerListener with MessageListener with ModeListener {
  // ServerListener
  // TODO send BusEvents instead
  override def onConnect(c: IrcConnection) {
    manager._connections.get(c) map { server =>
      UiBus.run {
        // ugh, need to do it here so that the auto commands can run
        server.state = Server.State.CONNECTED
        server.add(ServerInfo(manager.getString(R.string.server_connected)))
      }
      if (server.autorun != null || server.autojoin != null) {
        val proc = CommandProcessor(Application.context, null)
        proc.server = Some(server)
        if (server.autorun != null) {
          server.autorun.split(";") foreach { cmd =>
            if (cmd.trim().length() > 0) {
              var command = cmd.trim()
              if (command.charAt(0) != '/')
                command = "/" + command
              UiBus.run { proc.executeLine(command) }
            }
          }
        }
        if (server.autojoin != null) {
          val join = manager.getString(R.string.command_join_1)
          val channels = server.autojoin.split(";")
          channels foreach { c =>
            if (c.trim().length() > 0)
              UiBus.run {
                proc.executeCommand(join, Some(c.trim()))
              }
          }
        }
      }
    } getOrElse {
      Log.w(TAG, "server not found in onConnect?!", new StackTrace)
    }
  }
  override def onDisconnect(c: IrcConnection) {
    //Log.w(TAG, "Connection dropped: " + c, new StackTrace)
    manager._connections.get(c) foreach { manager.serverDisconnected }
  }
  override def onInvite(c: IrcConnection, src: User, user: User,
      channel: Channel) {
    // TODO
  }

  override def onJoin(c: IrcConnection, channel: Channel, user: User) {
    if (user.isUs)
      manager.addChannel(c, channel)
    UiBus.run {
      // sometimes channel hasn't been set yet (on connect)
      manager._channels.get(channel) foreach { ch =>
        UiBus.send(BusEvent.NickListChanged(ch))
        if (!user.isUs)
          ch.add(Join(user.getNick, user.address))
      }
    }
  }

  override def onKick(c: IrcConnection, channel: Channel,
      user: User, op: User, msg: String) {
    UiBus.run {
      manager._channels.get(channel) map { ch =>
        if (user.isUs) {
          // TODO update adapter's channel state
          ch.state = QicrChannel.State.KICKED
        } else {
          UiBus.send(BusEvent.NickListChanged(ch))
        }
        ch.add(Kick(op.getNick, user.getNick, msg))
      }
    }
  }

  override def onPart(c: IrcConnection, channel: Channel,
      user: User, msg: String) {
    if (!manager._channels.contains(channel)) return
      UiBus.run {
        val ch = manager._channels(channel)
        if (user.isUs) {
          ch.state = QicrChannel.State.PARTED
        } else {
          UiBus.send(BusEvent.NickListChanged(ch))
      }
      ch.add(Part(user.getNick, user.address, msg))
    }
  }

  override def onTopic(c: IrcConnection, channel: Channel,
      src: User, topic: String) {
    manager._channels.get(channel) foreach { c =>
      UiBus.run {
        c.add(Topic(if (src != null) Some(src.getNick) else None, topic))
      }
    }
  }

  override def onMode(c: IrcConnection, channel: Channel,
      src: User, mode: String) = () // TODO

  override def onMotd(c: IrcConnection, motd: String) {
    manager._connections.get(c) foreach { server =>
      // sIRC sends motd as one big blob of lines, split before adding
      UiBus.run { motd.split("\r?\n") foreach { m => server.add(Motd(m)) } }
    }
  }

  override def onNick(c: IrcConnection, oldnick: User, newnick: User) {
    val server = manager._connections(c)
    if (oldnick.isUs || newnick.isUs) {
      server.currentNick = newnick.getNick

      UiBus.run {
        manager._channels.values foreach { c =>
          if (c.server == server) {
            UiBus.send(BusEvent.NickListChanged(c))
            c.add(NickChange(oldnick.getNick, newnick.getNick))
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
            c.add(NickChange(oldnick.getNick, newnick.getNick))
          }
        }
      }
    }
  }

  override def onQuit(c: IrcConnection, user: User, msg: String) {
    if (user.isUs) return
    manager._connections.get(c) foreach { server =>
      UiBus.run {
        try { // guard can change values if slow...
          manager.channels.values collect {
            case c: Channel if c.hasUser(user) => manager._channels.get(c)
          } foreach { c =>
            if (c.isDefined && c.get.server == server) {
              UiBus.send(BusEvent.NickListChanged(c.get))
              c.get.add(Quit(user.getNick, user.address, msg))
            }
          }
        } catch {
          case _: MatchError => ()
        }
      }
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

  // MessageListener
  override def onAction(c: IrcConnection, src: User, channel: Channel,
      msg: String) {
    manager._channels.get(channel) foreach { c =>
      val action = CtcpAction(src.getNick, msg)
      UiBus.run { c.add(action) }
      if (matchesNick(c.server, msg))
        manager.addChannelMention(c, action)
    }
  }

  override def onMessage(c: IrcConnection, src: User, channel: Channel,
      msg: String) {
    manager._channels.get(channel) foreach {
      c =>

        val pm = Privmsg(src.getNick, msg, src.hasOperator, src.hasVoice)
        if (matchesNick(c.server, msg))
          manager.addChannelMention(c, pm)

        UiBus.run {
          c.add(pm)
        }
    }
  }

  override def onNotice(c: IrcConnection, src: User, channel: Channel,
      msg: String) {
    val c = manager._channels(channel)
    val notice = Notice(src.getNick, msg)
    UiBus.run { c.add(notice) }
    if (matchesNick(c.server, msg))
      manager.addChannelMention(c, notice)
  }

  val CtcpPing = """(\d+)\s+(\d+)""".r
  override def onCtcpReply(c: IrcConnection, src: User,
      cmd: String, reply: String) {
    val s = manager._connections(c)
    val r = CtcpReply(s, src.getNick, cmd, cmd match {
      case "PING" => reply match {
        case CtcpPing(seconds, micros) =>
          catching(classOf[Exception]) opt {
            // prevent malicious overflow causing a crash
            val ts = seconds.toLong * 1000 + (micros.toLong / 1000)
            Server.intervalString(System.currentTimeMillis - ts)
          } orElse Option(reply)
        case _ => Option(reply)
      }
      case _ => Option(reply)
    })

    // TODO show in current WidgetChatActivity
    if (manager.showing) {
      UiBus.run {
        val msg = MessageAdapter.formatText(Application.context, r)
        MainActivity.instance map { activity =>
          val tab = activity.adapter.currentTab
          tab.channel orElse tab.server map { _.add(r) } getOrElse {
            s.add(r)
            Toast.makeText(Application.context, msg, Toast.LENGTH_LONG).show()
          }
        }
      }
    } else {
      s.add(r)
    }
  }

  override def onAction(c: IrcConnection, src: User, action: String) =
    UiBus.run { manager.addQuery(c, src.getNick, action, action = true) }

  override def onNotice(c: IrcConnection, src: User, msg: String) =
    UiBus.run { manager.addQuery(c, src.getNick, msg, notice = true) }

  override def onPrivateMessage(c: IrcConnection, src: User, msg: String) =
    UiBus.run { manager.addQuery(c, src.getNick, msg) }

  def handleWhois(line: IrcPacket, start: Boolean = false) {
    val nick = line.getArgumentsArray()(1)
    if (start)
      whoisBuffer += (nick -> List(line))
    else {
      if (whoisBuffer.contains(nick)) {
        whoisBuffer += (nick -> (whoisBuffer(nick) ++ List(line)))

        line.getNumericCommand match {
          case 318 =>
            UiBus.run(manager.lastChannel foreach (_.add(accumulateWhois(nick))))
          case 369 =>
            UiBus.run(manager.lastChannel foreach (_.add(accumulateWhois(nick))))
          case _ =>
        }
      }
    }
  }

  def whoisPrefix = "%1%2%3" formatSpans( textColor(0xff777777, "-"),
    textColor(0xff00ff00, "!"), textColor(0xff777777, "-"))
  def accumulateWhois(nick: String) = {
    val b = (new SpannableStringBuilder /: whoisBuffer(nick)) { (buf,line) =>
      val args = line.getArgumentsArray
      val m = line.getMessage
      line.getNumericCommand match {
        case 318 => // end of whois
        case 369 => // end of whowas
        case 311 =>
          val (user, host, name) = (args(2), args(3), m)
          val t = "%1 %2 [%3@%4]\n%6   name: %5" formatSpans(
            whoisPrefix, MessageAdapter.colorNick(nick), user, host,
            bold(name), whoisPrefix)
          buf.append(t)
        case 314 =>
          val (user, host, name) = (args(2), args(3), m)
          val t = "%1 %2 was [%3@%4]\n%1   name: %5" formatSpans(
            whoisPrefix, MessageAdapter.colorNick(nick), user, host, bold(name))
          buf.append(t)
        case 312 =>
          val (server,tag) = (args(2), m)
          val t = "\n%1   server: %2 [%3]" formatSpans(whoisPrefix, server, tag)
          buf.append(t)
        case 330 =>
          val (login,tag) = (args(2), m)
          val t = "\n%1   %3: %2" formatSpans(whoisPrefix, login,tag)
          buf.append(t)
        case 319 =>
          buf.append("\n%1   channels: %2" formatSpans(whoisPrefix, m))
        case 338 =>
          buf.append("\n%1   %2: %3" formatSpans(whoisPrefix, m, args(2)))
        case 317 =>
          val (idle, time) = (args(2).toInt, args(3).toLong * 1000)
          buf.append("\n%1   %2: %3, %4" formatSpans(whoisPrefix, m,
            args(2), new java.util.Date(time).toString))
        case 401 =>
          buf.append("\n%1 %2: %3" formatSpans(whoisPrefix,
            MessageAdapter.colorNick(nick), m))
        case 406 =>
          buf.append("\n%1 %2: %3" formatSpans(whoisPrefix,
            MessageAdapter.colorNick(nick), m))
        case _ =>
      }
      buf
    }
    whoisBuffer -= nick
    Whois(b)
  }
  // AdvancedListener
  override def onUnknown(c: IrcConnection, line: IrcPacket) {
    manager._connections.get(c) foreach { server =>
      if (line.isNumeric) {
        // 306 = away
        // 333 = topic change info
        // 366 = end of /names list
        line.getNumericCommand match {
          case   5 => return // server capabilities list
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
          case 333 => return // topic change timestamp
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
          case 366 => return // end of names list
          case 375 => return // motd start
          case _ => ()
        }
      } else {
        line.getCommand match {
          case "PONG" =>
            server.currentPing map { ping =>
              server.currentPing = None
              catching(classOf[Exception]) opt {
                (Option(line.getMessage) getOrElse
                  line.getArgumentsArray()(1)).toLong
              } map { p =>
                // non-numeric should pass through
                // should always match if the lag timer is working
                if (p == ping) {
                  val t = System.currentTimeMillis
                  server.currentLag = (t - p).toInt
                  UiBus.send(BusEvent.ServerChanged(server))
                }
                // TODO make interval into a pref?
                manager.handler.delayed(30000) {
                  manager.ping(c, server)
                }
              }
            }
          case _ => ()
        }
      }
      if (line.getCommand != "PONG") UiBus.run {
        server.add(ServerInfo("[%s](%s):[%s]" format (
          line.getCommand, line.getArguments,
          line.getMessage)))
      }
    }
  }

  // Never happens
  override def onUnknownReply(c: IrcConnection, line: IrcPacket) = ()

  private var whoisBuffer: Map[String,List[IrcPacket]] = Map.empty
}
