package com.hanhuy.android.irc

import com.hanhuy.android.irc.model.Server

import com.sorcix.sirc._

import AndroidConversions._

class ConnectionListeners(service: IrcService) extends AdvancedListener
with ServerListener with MessageListener with ModeListener {
    // ServerListener
    override def onConnect(c: IrcConnection) {
        val server = service._connections(c)
        service.runOnUI(() =>
                server.messages.add(
                        service.getString(R.string.server_connected)))
    }
    override def onDisconnect(c: IrcConnection) {
        val server = service._connections(c)
        service.runOnUI(() => service.disconnect(server))
    }
    override def onInvite(c: IrcConnection, sender: User, user: User, channel: Channel) = Unit
    override def onJoin(c: IrcConnection, channel: Channel, user: User) = Unit
    override def onKick(c: IrcConnection, channel: Channel, sender: User, user: User) = Unit
    override def onPart(c: IrcConnection, channel: Channel, sender: User, msg: String) = Unit
    override def onTopic(c: IrcConnection, channel: Channel, sender: User, topic: String) = Unit
    override def onMode(c: IrcConnection, channel: Channel, sender: User, mode: String) = Unit
    override def onMotd(c: IrcConnection, motd: String) {
        val server = service._connections(c)
        service.runOnUI(() => server.messages.add(motd))
    }
    override def onNick(c: IrcConnection, oldnick: User, newnick: User) = Unit
    override def onQuit(c: IrcConnection, user: User, msg: String) = Unit

    // ModeListener
    override def onAdmin(c: IrcConnection, channel: Channel, sender: User, user: User) = Unit
    override def onDeAdmin(c: IrcConnection, channel: Channel, sender: User, user: User) = Unit
    override def onDeFounder(c: IrcConnection, channel: Channel, sender: User, user: User) = Unit
    override def onDeHalfop(c: IrcConnection, channel: Channel, sender: User, user: User) = Unit
    override def onDeOp(c: IrcConnection, channel: Channel, sender: User, user: User) = Unit
    override def onDeVoice(c: IrcConnection, channel: Channel, sender: User, user: User) = Unit
    override def onFounder(c: IrcConnection, channel: Channel, sender: User, user: User) = Unit
    override def onHalfop(c: IrcConnection, channel: Channel, sender: User, user: User) = Unit
    override def onOp(c: IrcConnection, channel: Channel, sender: User, user: User) = Unit
    override def onVoice(c: IrcConnection, channel: Channel, sender: User, user: User) = Unit

    // MessageListener
    override def onAction(c: IrcConnection, sender: User, channel: Channel, action: String) = Unit
    override def onAction(c: IrcConnection, sender: User, action: String) = Unit
    override def onCtcpReply(c: IrcConnection, sender: User, cmd: String, reply: String) = Unit
    override def onNotice(c: IrcConnection, sender: User, msg: String) = Unit
    override def onMessage(c: IrcConnection, sender: User, channel: Channel, msg: String) = Unit
    override def onNotice(c: IrcConnection, sender: User, channel: Channel, msg: String) = Unit
    override def onPrivateMessage(c: IrcConnection, sender: User, msg: String) = Unit

    // AdvancedListener
    override def onUnknown(c: IrcConnection, line: IrcDecoder) {
        val server = service._connections(c)
        android.util.Log.w("AdvancedListener", "Got message: " + line)
        service.runOnUI(() => server.messages.add(String.format("cmd: [%s] args [%s] line [%s]", line.getCommand(), line.getArguments(), line.getMessage())))
                
    }
    override def onUnknownReply(c: IrcConnection, line: IrcDecoder) {
        android.util.Log.w("AdvancedListener", "Got reply: " + line)
        val server = service._connections(c)
        service.runOnUI(() => server.messages.add(String.format("cmd: [%s] args [%s] line [%s]", line.getCommand(), line.getArguments(), line.getMessage())))
    }
}
