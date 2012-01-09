package com.hanhuy.android.irc.model

import com.hanhuy.android.irc.IrcListeners
import com.hanhuy.android.irc.ServiceBus
import com.hanhuy.android.irc.UiBus

import MessageLike._

object Channel {
    // TODO fix this to be scala-style, use case classes
    object State extends Enumeration {
        type State = Value
        val NEW, JOINED, KICKED, PARTED = Value
    }
}

abstract class ChannelLike(_server: Server, _name: String) {
    val messages = new MessageAdapter
    def name = _name
    def server = _server

    var newMessages = false
    var newMentions = false
    override def equals(o: Any): Boolean = {
        o match {
            case other: ChannelLike =>
                    name == other.name && server.name == other.server.name
            case _ => false
        }
    }
    override def hashCode(): Int = name.hashCode()
    def add(m: MessageLike) {
        messages.add(m)
        val msg = m match {
        case Privmsg(src, msg, o, v) => {newMessages = true; msg}
        case CtcpAction(src, msg)    => {newMessages = true; msg}
        case Notice(src, msg)        => {newMessages = true; msg}
        case _ => ""
        }

        if (IrcListeners.matchesNick(server, msg))
            newMentions = true
        ServiceBus.send(BusEvent.ChannelMessage(this, m))
        UiBus.send(BusEvent.ChannelMessage(this, m))
    }

    override def toString() = name
}
class Channel(s: Server, n: String) extends ChannelLike(s, n) {
    import Channel.State._
    private var _state = NEW
    def state = _state
    def state_=(state: State) = {
        ServiceBus.send(BusEvent.ChannelStateChanged(this, _state))
        _state = state
    }
}
class Query(s: Server, n: String) extends ChannelLike(s, n) {
    override def add(m: MessageLike) {
        newMentions = true // always true in a query
        super.add(m)
    }
}

class ChannelLikeComparator extends java.util.Comparator[ChannelLike] {
    private def stripInitial(_name: String): String = {
        var name = _name
        name = name.charAt(0) match {
            case '#' => name.substring(1)
            case '&' => name.substring(1)
            case _   => name
        }
        return if (name == _name) name else stripInitial(name)
    }
    override def compare(c1: ChannelLike, c2: ChannelLike): Int = {
        if (c1.isInstanceOf[Channel] && c2.isInstanceOf[Query])
            return 1
        if (c1.isInstanceOf[Query] && c2.isInstanceOf[Channel])
            return -1
        var c1name = c1.name
        var c2name = c2.name
        var r = stripInitial(c1.name).compareTo(stripInitial(c2.name))
        if (r == 0)
            r = c1.server.name.compareTo(c2.server.name)
        r
    }
}
