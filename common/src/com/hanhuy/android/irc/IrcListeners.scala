package com.hanhuy.android.irc

import com.hanhuy.android.irc.model.BusEvent
import com.hanhuy.android.irc.model.Server
import com.hanhuy.android.irc.model.{Channel => QicrChannel}
import com.hanhuy.android.irc.model.MessageLike.ServerInfo
import com.hanhuy.android.irc.model.MessageLike.Privmsg
import com.hanhuy.android.irc.model.MessageLike.CtcpAction
import com.hanhuy.android.irc.model.MessageLike.Motd
import com.hanhuy.android.irc.model.MessageLike.Notice
import com.hanhuy.android.irc.model.MessageLike.Topic
import com.hanhuy.android.irc.model.MessageLike.Kick
import com.hanhuy.android.irc.model.MessageLike.Join
import com.hanhuy.android.irc.model.MessageLike.Part
import com.hanhuy.android.irc.model.MessageLike.NickChange
import com.hanhuy.android.irc.model.MessageLike.Quit

import android.util.Log

import com.sorcix.sirc._

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
}
class IrcListeners(service: IrcService) extends AdvancedListener
with ServerListener with MessageListener with ModeListener {
    // ServerListener
    // TODO send BusEvents instead
    override def onConnect(c: IrcConnection) {
        val server = service._connections(c)
        UiBus.run {
            // ugh, need to do it here so that the auto commands can run
            server.state = Server.State.CONNECTED
            server.add(ServerInfo(
                    service.getString(R.string.server_connected)))
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
            val ch = service._channels(channel).asInstanceOf[QicrChannel]
            UiBus.send(BusEvent.NickListChanged(ch))
            if (!user.isUs())
                ch.add(Join(user.getNick(), user.address))
        }
    }

    override def onKick(c: IrcConnection, channel: Channel,
            user: User, op: User, msg: String) {
        UiBus.run {
            val ch = service._channels(channel).asInstanceOf[QicrChannel]
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
            val ch = service._channels(channel).asInstanceOf[QicrChannel]
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
        UiBus.run { server.add(Motd(motd)) }
    }

    override def onNick(c: IrcConnection, oldnick: User, newnick: User) {
        val server = service._connections(c)
        if (oldnick.isUs() || newnick.isUs()) {
            server.currentNick = newnick.getNick()

            UiBus.run {
                service._channels.values foreach { c =>
                    if (c.server == server) {
                        UiBus.send(BusEvent.NickListChanged(
                                c.asInstanceOf[QicrChannel]))
                        c.add(NickChange(oldnick.getNick(), newnick.getNick()))
                    }
                }
            }
        } else {
            UiBus.run {
                service.channels.values collect {
                    case c: Channel if c.hasUser(newnick) =>
                        service._channels(c).asInstanceOf[QicrChannel]
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
            service.channels.values collect {
                case c: Channel if c.hasUser(user) =>
                    service._channels(c).asInstanceOf[QicrChannel]
            } foreach { c =>
                if (c.server == server) {
                    UiBus.send(BusEvent.NickListChanged(c))
                    c.add(Quit(user.getNick(), user.address, msg))
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

    // TODO
    override def onCtcpReply(c: IrcConnection, src: User,
            cmd: String, reply: String) = ()

    override def onAction(c: IrcConnection, src: User, action: String) {
        UiBus.run {
            service.addQuery(c, src.getNick(), action, action = true)
        }
    }
    override def onNotice(c: IrcConnection, src: User, msg: String) {
        UiBus.run {
            service.addQuery(c, src.getNick(), msg, notice = true)
        }
    }
    override def onPrivateMessage(c: IrcConnection, src: User, msg: String) {
        UiBus.run { service.addQuery(c, src.getNick(), msg) }
    }

    // AdvancedListener
    // TODO
    override def onUnknown(c: IrcConnection, line: IrcDecoder) {
        service._connections.get(c) foreach { server =>
            if (line.isNumeric()) {
                // 306 = away
                // 333 = topic change info
                // 366 = end of /names list
                line.getNumericCommand() match {
                    case 306 | 333 | 366 => return
                    case _ => ()
                }
            }
            UiBus.run {
                server.add(ServerInfo(
                        format("Unknown command: [%s](%s):[%s]",
                        line.getCommand(), line.getArguments(),
                        line.getMessage())))
            }
        }
                
    }
    // Never happens
    override def onUnknownReply(c: IrcConnection, line: IrcDecoder) = ()
}
