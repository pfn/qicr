package com.hanhuy.android.irc.model

import com.hanhuy.android.common._

import android.content.ContentValues
import com.hanhuy.android.irc.model.BusEvent.ServerMessage
import com.hanhuy.android.common.{UiBus, ServiceBus}
import rx.Var

object Server {
  sealed trait State
  case object INITIAL extends State
  case object DISCONNECTED extends State
  case object CONNECTING extends State
  case object CONNECTED extends State

  def intervalString(l: Long) = {
    // ms if under 1s
    // x.xxs if under 5s
    // x.xs if under 10s
    // xs if over 10s
    val (fmt, lag) = l match {
      case x if x < 1000 => ("%dms",x)
      case x if x > 9999 => ("%ds", x / 1000)
      case x if x < 5000 => ("%.2fs", x / 1000.0f)
      case x => ("%.1fs", x / 1000.0f)
    }
    fmt format lag
  }
}
class Server extends MessageAppender with Ordered[Server] {

  import Server._
  val messages = new MessageAdapter(null)

  override def clear() = messages.clear()

  def +=(m: MessageLike) = {
    ServiceBus.send(ServerMessage(this, m))
    messages.add(m)
  }
  val state: Var[State] = Var(INITIAL)
  state.trigger {
    ServiceBus.send(BusEvent.ServerStateChanged(this, state.now))
    UiBus.send(BusEvent.ServerChanged(this))
  }

  var id: Long = -1
  var name: String = _

  var autoconnect = true

  var hostname: String = _
  var port = 6667
  var ssl = false
  var logging = false

  var nickname: String = _
  var altnick: String = _
  var username: String = "qicruser"
  var realname: String = "strong faster qicr"
  var _password: String = _
  def password = _password
  def password_=(p: String) = _password = p.?.find(_.trim.nonEmpty).orNull

  var _autorun: String = _
  import com.hanhuy.android.irc._
  def autorun = _autorun.?
  def autorun_=(a: String) = _autorun = a.?.find(_.trim.nonEmpty).orNull

  var _autojoin: String = _
  def autojoin = _autojoin.?
  def autojoin_=(a: String) = _autojoin = a.?.find(_.trim.nonEmpty).orNull

  var socks = false
  var socksHost: String = _
  var socksPort: Int = _
  var socksUser: String = _
  var socksPass: String = _

  var sasl = false
  var saslUser: String = _
  var saslPass: String = _

  var currentPing: Option[Long] = None
  val currentLag = Var(0)

  var currentNick = nickname
  override def toString = name

  def blank(s: String) : Boolean = s.?.forall(_.trim.isEmpty)
  def valid: Boolean = {
    var valid = true
    valid = valid && !blank(name)
    valid = valid && !blank(hostname)
    valid = valid && port > 0
    valid = valid && !blank(nickname)
    valid = valid && (!sasl || (!blank(username) && !blank(password)))
    if (valid && !blank(nickname) && blank(altnick))
      altnick = nickname + "_"
    valid
  }
  def values: ContentValues = {
    val values = new ContentValues
    values.put(Config.FIELD_NAME,        name)
    values.put(Config.FIELD_AUTOCONNECT, autoconnect)
    values.put(Config.FIELD_HOSTNAME,    hostname)
    values.put(Config.FIELD_PORT,        new java.lang.Integer(port))
    values.put(Config.FIELD_SSL,         ssl)
    values.put(Config.FIELD_NICKNAME,    nickname)
    values.put(Config.FIELD_ALTNICK,     altnick)
    values.put(Config.FIELD_USERNAME,    username)
    values.put(Config.FIELD_PASSWORD,    password)
    values.put(Config.FIELD_REALNAME,    realname)
    values.put(Config.FIELD_AUTOJOIN,    autojoin.orNull)
    values.put(Config.FIELD_AUTORUN,     autorun.orNull)

    values.put(Config.FIELD_LOGGING,     logging)
    values.put(Config.FIELD_USESASL,     sasl)
    values.put(Config.FIELD_USESOCKS,    socks)
    values
  }

  def copy(other: Server): Unit = {
    id = other.id
    name = other.name
    hostname = other.hostname
    autoconnect = other.autoconnect
    port = other.port
    ssl = other.ssl
    nickname = other.nickname
    altnick = other.altnick
    realname = other.realname
    username = other.username
    password = other.password
    autojoin = other.autojoin.orNull
    autorun = other.autorun.orNull
    logging = other.logging
    sasl = other.sasl
    socks = other.socks
  }

  override def equals(o: Any): Boolean = o match {
    case s: Server => id == s.id
    case _ => false
  }

  def compare(that: Server) = ServerComparator.compare(this, that)
}

object ServerComparator extends java.util.Comparator[Server] {
  override def compare(s1: Server, s2: Server): Int = {
    val r = s1.name.compareTo(s2.name)
    if (r == 0) s1.username.compareTo(s2.username)
    else r
  }
}
