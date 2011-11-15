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

class IrcListeners(service: IrcService) extends AdvancedListener
with ServerListener with MessageListener with ModeListener {
    // ServerListener
    override def onConnect(c: IrcConnection) {
        val server = service._connections(c)
        service.runOnUI(() =>
                server.messages.add(ServerInfo(
                        service.getString(R.string.server_connected))))
    }
    override def onDisconnect(c: IrcConnection) {
        service._connections.get(c) match {
            case Some(server) => {
                service.runOnUI(() => service.disconnect(server))
            }
            case None => Unit
        }
    }
    override def onInvite(c: IrcConnection, sender: User, user: User,
            channel: Channel) {
        // TODO
    }

    override def onJoin(c: IrcConnection, channel: Channel, user: User) {
        if (user.isUs())
            service.addChannel(c, channel)
    }

    override def onKick(c: IrcConnection, channel: Channel,
            sender: User, user: User) {
        if (user.isUs()) {
            service.runOnUI(() => {
                //service._channels(channel).state = QicrChannel.State.KICKED
            })
        }
    }
    override def onPart(c: IrcConnection, channel: Channel,
            user: User, msg: String) {
        if (user.isUs()) {
            service.runOnUI(() => {
                //service._channels(channel).state = QicrChannel.State.PARTED
            })
        }
    }
    override def onTopic(c: IrcConnection, channel: Channel,
            sender: User, topic: String) = Unit // TODO
    override def onMode(c: IrcConnection, channel: Channel,
            sender: User, mode: String) = Unit // TODO
    override def onMotd(c: IrcConnection, motd: String) {
        val server = service._connections(c)
        service.runOnUI(() => server.messages.add(Motd(motd)))
    }
    // TODO
    override def onNick(c: IrcConnection, oldnick: User, newnick: User) = Unit
    // TODO
    override def onQuit(c: IrcConnection, user: User, msg: String) = Unit

    // ModeListener
    // TODO
    override def onAdmin(c: IrcConnection, channel: Channel,
            sender: User, user: User) = Unit
    override def onDeAdmin(c: IrcConnection, channel: Channel,
            sender: User, user: User) = Unit
    override def onDeFounder(c: IrcConnection, channel: Channel,
            sender: User, user: User) = Unit
    override def onDeHalfop(c: IrcConnection, channel: Channel,
            sender: User, user: User) = Unit
    override def onDeOp(c: IrcConnection, channel: Channel,
            sender: User, user: User) = Unit
    override def onDeVoice(c: IrcConnection, channel: Channel,
            sender: User, user: User) = Unit
    override def onFounder(c: IrcConnection, channel: Channel,
            sender: User, user: User) = Unit
    override def onHalfop(c: IrcConnection, channel: Channel,
            sender: User, user: User) = Unit
    override def onOp(c: IrcConnection, channel: Channel,
            sender: User, user: User) = Unit
    override def onVoice(c: IrcConnection, channel: Channel,
            sender: User, user: User) = Unit

    // MessageListener
    override def onAction(c: IrcConnection, sender: User, channel: Channel,
            msg: String) {
        val c = service._channels(channel)
        service.runOnUI(() => c.messages.add(CtcpAction(sender.getNick(), msg)))
    }
    override def onMessage(c: IrcConnection, sender: User, channel: Channel,
            msg: String) {
        val c = service._channels(channel)
        service.runOnUI(() => c.messages.add(Privmsg(sender.getNick(), msg)))
    }
    override def onNotice(c: IrcConnection, sender: User, channel: Channel,
            msg: String) {
        val c = service._channels(channel)
        service.runOnUI(() => c.messages.add(Notice(sender.getNick(), msg)))
    }

    // TODO
    override def onAction(c: IrcConnection, sender: User, action: String) {
    }
    override def onCtcpReply(c: IrcConnection, sender: User,
            cmd: String, reply: String) = Unit
    override def onNotice(c: IrcConnection, sender: User, msg: String) = Unit
    override def onPrivateMessage(c: IrcConnection, sender: User, msg: String) {
    }

    // AdvancedListener
    // TODO
    override def onUnknown(c: IrcConnection, line: IrcDecoder) {
        val server = service._connections(c)
        if (line.isNumeric()) {
            // 306 = away
            // 333 = topic change info
            // 366 = end of /names list
            line.getNumericCommand() match {
                case 306 | 333 | 366 => return
                case _ => Unit
            }
        }
        service.runOnUI(() => server.messages.add(ServerInfo(
                String.format("Unknown command: [%s](%s):[%s]",
                line.getCommand(), line.getArguments(), line.getMessage()))))
                
    }
    override def onUnknownReply(c: IrcConnection, line: IrcDecoder) {
        val server = service._connections(c)
        service.runOnUI(() => server.messages.add(ServerInfo(
                String.format("Unknown reply: [%s](%s):[%s]",
                line.getCommand(), line.getArguments(), line.getMessage()))))
    }
}
