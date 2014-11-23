package com.hanhuy.android.irc.model

import com.hanhuy.android.irc.{MessageLog, IrcManager, IrcListeners}

import MessageLike._
import scala.annotation.tailrec
import android.util.Log
import com.hanhuy.android.common.{AndroidConversions, UiBus, ServiceBus}
import AndroidConversions._

object Channel {
  trait State
  object State {
    case object NEW extends State
    case object JOINED extends State
    case object KICKED extends State
    case object PARTED extends State
  }

  def apply(s: Server, n: String) = ChannelLike.created.get(s, n) match {
    case Some(c: Channel) => c
    case _ => new Channel(s, n)
  }
}

object Query {
  def apply(s: Server, n: String) = ChannelLike.created.get(s, n) match {
    case Some(c: Query) => c
    case _ => new Query(s, n)
  }
}

object ChannelLike {
  private[model] var created = Map.empty[(Server,String), ChannelLike]
}

abstract class ChannelLike(val server: Server, val name: String)
  extends MessageAppender with Ordered[ChannelLike] {
  val messages = new MessageAdapter(this)

  if (ChannelLike.created.get(server -> name).isDefined) {
    Log.e("ChannelLike", "Already created: " + this, new IllegalStateException)
    throw new IllegalStateException("already created channel %s" format name)
  }

  ChannelLike.created += (server -> name) -> this

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
  var lastTs = 0l
  def isNew(m: MessageLike) = lastTs < m.ts.getTime

  def clear(): Unit = {
    messages.clear()
  }

  def add(m: MessageLike) {
    if (isNew(m)) {
      messages.add(m)
      val msg = m match {
        case Privmsg(src, m2, o, v, _) =>
          lastTs = m.ts.getTime
          newMessages = true
          m2
        case CtcpAction(src, m2, _) =>
          lastTs = m.ts.getTime
          newMessages = true
          m2
        case Notice(src, m2, _) =>
          lastTs = m.ts.getTime
          newMessages = true
          m2
        case _ => ""
      }

      if (IrcListeners.matchesNick(server, msg)) {
        newMentions = true
        ServiceBus.send(BusEvent.ChannelStatusChanged(this))
      }
      ServiceBus.send(BusEvent.ChannelMessage(this, m))
      UiBus.send(BusEvent.ChannelMessage(this, m))
      MessageLog.log(m, this)
    }
  }

  override def toString = name

  def compare(that: ChannelLike): Int =
    ChannelLikeComparator.compare(this, that)

  lazy val manager = IrcManager.start()
}

class Channel private(s: Server, n: String) extends ChannelLike(s, n) {
  import Channel._
  private var _state: State = State.NEW
  def topic = lastTopic
  private var lastTopic = Option.empty[Topic]
  def state = _state
  def state_=(state: State) = {
    ServiceBus.send(BusEvent.ChannelStateChanged(this, _state))
    _state = state
  }

  override def add(m: MessageLike) = m match {
    case t@Topic(sender, topic, ts, force) =>
      // do not repeat topic when re-connecting to a bnc
      if (!lastTopic.exists(_.topic == topic) || force)
        super.add(m)

      lastTopic = Some(t)
    case _ =>
      super.add(m)
  }
}
class Query private(s: Server, n: String) extends ChannelLike(s, n) {
  override def add(m: MessageLike) {
    newMentions = true // always true in a query
    super.add(m)
  }
}

object ChannelLikeComparator extends java.util.Comparator[ChannelLike] {
  @tailrec
  private def stripInitial(_name: String): String = {
    var name = _name
    name = if (name.length == 0) "" else (name.charAt(0) match {
      case '#' => name.substring(1)
      case '&' => name.substring(1)
      case _   => name
    })
    if (name == _name) name else stripInitial(name)
  }
  override def compare(c1: ChannelLike, c2: ChannelLike): Int = {
    if (c1.isInstanceOf[Channel] && c2.isInstanceOf[Query])
      return 1
    if (c1.isInstanceOf[Query] && c2.isInstanceOf[Channel])
      return -1
    val c1name = c1.name
    val c2name = c2.name
    val ch1 = if (c1name.length > 0) c1name.charAt(0)
    val ch2 = if (c2name.length > 0) c2name.charAt(0)
    (ch1, ch2) match {
      case ('&', '#') => return -1
      case ('#', '&') => return 1
      case          _ => ()
    }
    var r = stripInitial(c1.name).compareToIgnoreCase(stripInitial(c2.name))
    if (r == 0)
      r = c1.server.name.compareToIgnoreCase(c2.server.name)
    r
  }
}
