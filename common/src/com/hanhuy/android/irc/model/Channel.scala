package com.hanhuy.android.irc.model

import scala.collection.mutable.HashSet

object Channel {
    object State extends Enumeration {
        type State = Value
        val NEW, JOINED, KICKED, PARTED = Value
    }
}

abstract class ChannelLike(_server: Server, _name: String) {
    val messages = new MessageAdapter
    def name = _name
    def server = _server
    override def equals(o: Any): Boolean = {
        o match {
            case other: ChannelLike =>
                    name == other.name && server.name == other.server.name
            case _ => false
        }
    }
    override def hashCode(): Int = name.hashCode()
}
class Channel(s: Server, n: String) extends ChannelLike(s, n) {
    import Channel.State._
    val stateChangedListeners = new HashSet[(Channel, State) => Any]
    private var _state = NEW
    def state = _state
    def state_=(state: State) = {
        stateChangedListeners.foreach(_(this, _state))
        _state = state
    }
}
class Query(s: Server, n: String) extends ChannelLike(s, n)

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
