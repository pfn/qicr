package com.hanhuy.android.irc.model

import scala.collection.mutable.HashSet

object Channel {
    object State extends Enumeration {
        type State = Value
        val NEW, JOINED, KICKED, PARTED = Value
    }
}

class Channel(_server: Server, _name: String) {
    import Channel.State._
    val stateChangedListeners = new HashSet[(Channel, State) => Any]
    val messages = new MessageAdapter
    def name = _name
    def server = _server
    private var _state = NEW
    def state = _state
    def state_=(state: State) = {
        stateChangedListeners.foreach(_(this, _state))
        _state = state
    }

    override def equals(o: Any): Boolean = {
        o match {
            case other: Channel =>
                    name == other.name && server.name == other.server.name
            case _ => false
        }
    }
    override def hashCode(): Int = name.hashCode()
}

class ChannelComparator extends java.util.Comparator[Channel] {
    private def stripInitial(_name: String): String = {
        var name = _name
        name = name.charAt(0) match {
            case '#' => name.substring(1)
            case '&' => name.substring(1)
            case _   => name
        }
        return if (name == _name) name else stripInitial(name)
    }
    override def compare(c1: Channel, c2: Channel): Int = {
        var c1name = c1.name
        var c2name = c2.name
        var r = stripInitial(c1.name).compareTo(stripInitial(c2.name))
        if (r == 0)
            r = c1.server.name.compareTo(c2.server.name)
        r
    }
}
