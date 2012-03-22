package com.hanhuy.android.irc

import com.hanhuy.android.irc.model.BusEvent
import com.hanhuy.android.irc.model.Server
import com.hanhuy.android.irc.model.ChannelLike
import com.hanhuy.android.irc.model.{Channel => QicrChannel}
import com.hanhuy.android.irc.model.MessageAdapter
import com.hanhuy.android.irc.model.MessageLike.ServerInfo
import com.hanhuy.android.irc.model.MessageLike.Privmsg
import com.hanhuy.android.irc.model.MessageLike.CtcpAction
import com.hanhuy.android.irc.model.MessageLike.CtcpReply
import com.hanhuy.android.irc.model.MessageLike.Motd
import com.hanhuy.android.irc.model.MessageLike.Notice
import com.hanhuy.android.irc.model.MessageLike.Topic
import com.hanhuy.android.irc.model.MessageLike.Kick
import com.hanhuy.android.irc.model.MessageLike.Join
import com.hanhuy.android.irc.model.MessageLike.Part
import com.hanhuy.android.irc.model.MessageLike.NickChange
import com.hanhuy.android.irc.model.MessageLike.Quit

import android.widget.Toast

import android.util.Log

import com.sorcix.sirc._

import scala.util.control.Exception._
import AndroidConversions._
import IrcListeners._

object IrcListeners {
  val TAG = "IrcListeners"
  // TODO FIXME only looks for the first instance in a string
  // won't match if the first match is bogus but there's a real
  // match later on in the string.  e.g. with a nick of "foo"
  // and a message of "foobar sucks bad, foo" -- the line will not report
  // a match
  def matchesNickIndex(server: Server, msg: String): Int = {
    val m = msg.toLowerCase()
    val nick = server.currentNick.toLowerCase()

    val idx = m.indexOf(nick)
    val nlen = nick.length()
    val mlen = m.length()
    var matches = false
    if (idx != -1) {
      matches = true
      if (idx > 0) { // matches intra-line
        matches = !Character.isJavaIdentifierPart(msg.charAt(idx-1))
        if (idx + nlen < mlen) { // if not at end of line
          matches = matches && !Character.isJavaIdentifierPart(
            msg.charAt(idx+nlen))
        }
      } else {
        if (nlen < mlen) // matches at start of line
          matches = !Character.isJavaIdentifierPart(msg.charAt(nlen))
        }
      }

      if (matches) idx else -1
  }

  def matchesNick(server: Server, msg: String) =
    matchesNickIndex(server, msg) != -1

  class EnhancedUser(u: User) {
    def address = u.getUserName() + "@" + u.getHostName()
  }

  implicit def toEnhancedUser(u: User) = new EnhancedUser(u)
  implicit def toQicrChannel(c: ChannelLike) = c.asInstanceOf[QicrChannel]
}
class IrcListeners(service: IrcService) extends AdvancedListener
with ServerListener with MessageListener with ModeListener {
  // ServerListener
  // TODO send BusEvents instead
  override def onConnect(c: IrcConnection) {
    service._connections.get(c) map { server =>
      UiBus.run {
        // ugh, need to do it here so that the auto commands can run
        server.state = Server.State.CONNECTED
        server.add(ServerInfo(service.getString(R.string.server_connected)))
      }
      if (server.autorun != null || server.autojoin != null) {
        val proc = CommandProcessor(service)
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
          val join = service.getString(R.string.command_join_1)
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
    service._connections.get(c) foreach { service.serverDisconnected(_) }
  }
  override def onInvite(c: IrcConnection, src: User, user: User,
      channel: Channel) {
    // TODO
  }

  override def onJoin(c: IrcConnection, channel: Channel, user: User) {
    if (user.isUs())
      service.addChannel(c, channel)
    UiBus.run {
      // sometimes channel hasn't been set yet (on connect)
      service._channels.get(channel) foreach { ch =>
        UiBus.send(BusEvent.NickListChanged(ch))
        if (!user.isUs())
          ch.add(Join(user.getNick(), user.address))
      }
    }
  }

  override def onKick(c: IrcConnection, channel: Channel,
      user: User, op: User, msg: String) {
    UiBus.run {
      val ch = service._channels(channel)
      if (user.isUs()) {
        // TODO update adapter's channel state
        ch.state = QicrChannel.State.KICKED
      } else {
        UiBus.send(BusEvent.NickListChanged(ch))
      }
      ch.add(Kick(op.getNick(), user.getNick(), msg))
    }
  }

  override def onPart(c: IrcConnection, channel: Channel,
      user: User, msg: String) {
    if (!service._channels.contains(channel)) return
      UiBus.run {
        val ch = service._channels(channel)
        if (user.isUs()) {
          ch.state = QicrChannel.State.PARTED
        } else {
          UiBus.send(BusEvent.NickListChanged(ch))
      }
      ch.add(Part(user.getNick(), user.address, msg))
    }
  }

  override def onTopic(c: IrcConnection, channel: Channel,
      src: User, topic: String) {
    val c = service._channels(channel)
    UiBus.run {
      c.add(Topic(if (src != null) Some(src.getNick()) else None, topic))
    }
  }

  override def onMode(c: IrcConnection, channel: Channel,
      src: User, mode: String) = () // TODO

  override def onMotd(c: IrcConnection, motd: String) {
    val server = service._connections(c)
    // sIRC sends motd as one big blob of lines, split before adding
    UiBus.run { motd.split("\r?\n") foreach { m => server.add(Motd(m)) } }
  }

  override def onNick(c: IrcConnection, oldnick: User, newnick: User) {
    val server = service._connections(c)
    if (oldnick.isUs() || newnick.isUs()) {
      server.currentNick = newnick.getNick()

      UiBus.run {
        service._channels.values foreach { c =>
          if (c.server == server) {
            UiBus.send(BusEvent.NickListChanged(c))
            c.add(NickChange(oldnick.getNick(), newnick.getNick()))
          }
        }
      }
    } else {
      UiBus.run {
        service.channels.values collect {
          case c: Channel if c.hasUser(newnick) => service._channels(c)
        } foreach { c =>
          if (c.server == server) {
            UiBus.send(BusEvent.NickListChanged(c))
            c.add(NickChange(oldnick.getNick(), newnick.getNick()))
          }
        }
      }
    }
  }

  override def onQuit(c: IrcConnection, user: User, msg: String) {
    if (user.isUs()) return
    val server = service._connections(c)
    UiBus.run {
      try { // guard can change values if slow...
        service.channels.values collect {
          case c: Channel if c.hasUser(user) => service._channels(c)
        } foreach { c =>
          if (c.server == server) {
            UiBus.send(BusEvent.NickListChanged(c))
            c.add(Quit(user.getNick(), user.address, msg))
          }
        }
      } catch {
        case _: MatchError => ()
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
    val c = service._channels(channel)
    val action = CtcpAction(src.getNick(), msg)
    UiBus.run { c.add(action) }
    if (matchesNick(c.server, msg))
      service.addChannelMention(c, action)
  }

  override def onMessage(c: IrcConnection, src: User, channel: Channel,
      msg: String) {
    val c = service._channels(channel)

    val pm = Privmsg(src.getNick(), msg, src.hasOperator(), src.hasVoice())
    if (matchesNick(c.server, msg))
      service.addChannelMention(c, pm)

    UiBus.run { c.add(pm) }
  }

  override def onNotice(c: IrcConnection, src: User, channel: Channel,
      msg: String) {
    val c = service._channels(channel)
    val notice = Notice(src.getNick(), msg)
    UiBus.run { c.add(notice) }
    if (matchesNick(c.server, msg))
      service.addChannelMention(c, notice)
  }

  val CtcpPing = """(\d+)\s+(\d+)""".r
  override def onCtcpReply(c: IrcConnection, src: User,
      cmd: String, reply: String) {
    val s = service._connections(c)
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

    if (service.showing) {
      UiBus.run {
        val msg = MessageAdapter.formatText(service, r)
        service.activity map { activity =>
          val tab = activity.adapter.currentTab
          tab.channel orElse tab.server map { _.add(r) } getOrElse {
            s.add(r)
            Toast.makeText(service, msg, Toast.LENGTH_LONG).show()
          }
        }
      }
    } else {
      s.add(r)
    }
  }

  override def onAction(c: IrcConnection, src: User, action: String) =
    UiBus.run { service.addQuery(c, src.getNick(), action, action = true) }

  override def onNotice(c: IrcConnection, src: User, msg: String) =
    UiBus.run { service.addQuery(c, src.getNick(), msg, notice = true) }

  override def onPrivateMessage(c: IrcConnection, src: User, msg: String) =
    UiBus.run { service.addQuery(c, src.getNick(), msg) }

  // AdvancedListener
  override def onUnknown(c: IrcConnection, line: IrcDecoder) {
    service._connections.get(c) foreach { server =>
      if (line.isNumeric()) {
        // 306 = away
        // 333 = topic change info
        // 366 = end of /names list
        line.getNumericCommand() match {
          case 005 => return // server capabilities list
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
          // whois response line1
          // 1: nick, 2: username, 3: host, 4: ?; msg = realname
          case 311 => ()
          case 312 => () // 1: server, 2: server comment (whois l2)
          case 313 => () // 1: nick, whois operator
          case 314 => () // like 311, whowas
          case 318 => () // args[1], nick, end of whois
          case 319 => () // args[1], nick, msg channels, whois
          case 333 => return // topic change timestamp
          case 338 => () // args[1], host/ip, whois l3
          case 317 => () // args[1], idle, args[2], signon whois l4
          case 366 => return // end of names list
          case 369 => () // args[1], nick, end of whowas
          case 375 => return // motd start
          case 401 => () // args[1], nick, whois not there
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
                  // only schedule the next ping if it works
                  // need another job to update if no response?
                  // TODO make interval into a pref?
                  service.handler.delayed(30000) {
                    service.ping(c, server)
                  }
                  UiBus.send(BusEvent.ServerChanged(server))
                  return // don't print
                }
              }
            }
          case _ => ()
        }
      }
      UiBus.run {
        server.add(ServerInfo(format("[%s](%s):[%s]",
          line.getCommand(), line.getArguments(),
          line.getMessage())))
      }
    }
  }

  // Never happens
  override def onUnknownReply(c: IrcConnection, line: IrcDecoder) = ()
}
