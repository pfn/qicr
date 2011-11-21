package com.hanhuy.android.irc

import com.hanhuy.android.irc.model.Server
import com.hanhuy.android.irc.model.{Channel => QicrChannel}
import com.hanhuy.android.irc.model.MessageLike.ServerInfo
import com.hanhuy.android.irc.model.MessageLike.Privmsg
import com.hanhuy.android.irc.model.MessageLike.CtcpAction
import com.hanhuy.android.irc.model.MessageLike.Motd
import com.hanhuy.android.irc.model.MessageLike.Notice

import com.sorcix.sirc._

import AndroidConversions._
import IrcListeners._

object IrcListeners {
    def matchesNick(server: Server, msg: String): Boolean = {
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

        matches
    }
}
class IrcListeners(service: IrcService) extends AdvancedListener
with ServerListener with MessageListener with ModeListener {
    // ServerListener
    override def onConnect(c: IrcConnection) {
        val server = service._connections(c)
        service.runOnUI(() =>
                server.add(ServerInfo(
                        service.getString(R.string.server_connected))))
    }
    override def onDisconnect(c: IrcConnection) {
        service._connections.get(c) match {
        case Some(server) => service.serverDisconnected(server)
        case None => Unit
        }
    }
    override def onInvite(c: IrcConnection, src: User, user: User,
            channel: Channel) {
        // TODO
    }

    override def onJoin(c: IrcConnection, channel: Channel, user: User) {
        if (user.isUs())
            service.addChannel(c, channel)
    }

    override def onKick(c: IrcConnection, channel: Channel,
            src: User, user: User) {
        if (user.isUs()) {
            service.runOnUI(() => {
                service._channels(channel) match {
                case c: QicrChannel => c.state = QicrChannel.State.KICKED
                }
            })
        }
    }
    override def onPart(c: IrcConnection, channel: Channel,
            user: User, msg: String) {
        if (user.isUs()) {
            service.runOnUI(() => {
                service._channels(channel) match {
                case c: QicrChannel => c.state = QicrChannel.State.PARTED
                }
            })
        }
    }
    override def onTopic(c: IrcConnection, channel: Channel,
            src: User, topic: String) = Unit // TODO
    override def onMode(c: IrcConnection, channel: Channel,
            src: User, mode: String) = Unit // TODO
    override def onMotd(c: IrcConnection, motd: String) {
        val server = service._connections(c)
        service.runOnUI(() => server.add(Motd(motd)))
    }
    // TODO
    override def onNick(c: IrcConnection, oldnick: User, newnick: User) {
        if (oldnick.isUs() || newnick.isUs())
            service._connections.get(c) match {
            case Some(server) => server.currentNick = newnick.getNick()
            case None => Unit
            }
    }
    // TODO
    override def onQuit(c: IrcConnection, user: User, msg: String) = Unit

    // ModeListener
    // TODO
    override def onAdmin(c: IrcConnection, channel: Channel,
            src: User, user: User) = Unit
    override def onDeAdmin(c: IrcConnection, channel: Channel,
            src: User, user: User) = Unit
    override def onDeFounder(c: IrcConnection, channel: Channel,
            src: User, user: User) = Unit
    override def onDeHalfop(c: IrcConnection, channel: Channel,
            src: User, user: User) = Unit
    override def onDeOp(c: IrcConnection, channel: Channel,
            src: User, user: User) = Unit
    override def onDeVoice(c: IrcConnection, channel: Channel,
            src: User, user: User) = Unit
    override def onFounder(c: IrcConnection, channel: Channel,
            src: User, user: User) = Unit
    override def onHalfop(c: IrcConnection, channel: Channel,
            src: User, user: User) = Unit
    override def onOp(c: IrcConnection, channel: Channel,
            src: User, user: User) = Unit
    override def onVoice(c: IrcConnection, channel: Channel,
            src: User, user: User) = Unit

    // MessageListener
    override def onAction(c: IrcConnection, src: User, channel: Channel,
            msg: String) {
        val c = service._channels(channel)
        val action = CtcpAction(src.getNick(), msg)
        service.runOnUI(() => c.add(action))
        if (matchesNick(c.server, msg))
            service.addChannelMention(c, action)
    }
    override def onMessage(c: IrcConnection, src: User, channel: Channel,
            msg: String) {
        val c = service._channels(channel)

        val prefix = if (false /* replace with pref */) ""
        else if (src.hasOperator()) "@"
        else if (src.hasVoice()) "+"
        else ""
        val nick = prefix + src.getNick()

        // TODO fix regex
        val pm = Privmsg(nick, msg)
        if (matchesNick(c.server, msg))
            service.addChannelMention(c, pm)

        service.runOnUI(() => c.add(pm))
    }
    override def onNotice(c: IrcConnection, src: User, channel: Channel,
            msg: String) {
        val c = service._channels(channel)
        val notice = Notice(src.getNick(), msg)
        service.runOnUI(() => c.add(notice))
        if (matchesNick(c.server, msg))
            service.addChannelMention(c, notice)
    }

    // TODO
    override def onCtcpReply(c: IrcConnection, src: User,
            cmd: String, reply: String) = Unit

    override def onAction(c: IrcConnection, src: User, action: String) {
        service.runOnUI(() => service.addQuery(c, src.getNick(), action,
                action = true))
    }
    override def onNotice(c: IrcConnection, src: User, msg: String) {
        service.runOnUI(() =>
                service.addQuery(c, src.getNick(), msg, notice = true))
    }
    override def onPrivateMessage(c: IrcConnection, src: User, msg: String) {
        service.runOnUI(() => service.addQuery(c, src.getNick(), msg))
    }

    // AdvancedListener
    // TODO
    override def onUnknown(c: IrcConnection, line: IrcDecoder) {
        val server = service._connections.get(c)
        if (server.isEmpty) return
        if (line.isNumeric()) {
            // 306 = away
            // 333 = topic change info
            // 366 = end of /names list
            line.getNumericCommand() match {
                case 306 | 333 | 366 => return
                case _ => Unit
            }
        }
        service.runOnUI(() => server.get.add(ServerInfo(
                String.format("Unknown command: [%s](%s):[%s]",
                line.getCommand(), line.getArguments(), line.getMessage()))))
                
    }
    override def onUnknownReply(c: IrcConnection, line: IrcDecoder) {
        val server = service._connections(c)
        service.runOnUI(() => server.add(ServerInfo(
                String.format("Unknown reply: [%s](%s):[%s]",
                line.getCommand(), line.getArguments(), line.getMessage()))))
    }
}
