package com.hanhuy.android.irc.model

import com.hanhuy.android.irc.Config
import com.hanhuy.android.irc.UiBus
import com.hanhuy.android.irc.ServiceBus

import android.content.ContentValues
import android.provider.BaseColumns

object Server {
  trait State
  object State {
    case object INITIAL extends State
    case object DISCONNECTED extends State
    case object CONNECTING extends State
    case object CONNECTED extends State
  }
}
class Server {

  import Server._
  val messages = new MessageAdapter

  def add(m: MessageLike) = messages.add(m)
  private var _state: State = State.INITIAL
  def state = _state
  def state_=(state: State) = {
    val oldstate = _state
    _state = state
    if (oldstate != state) {
      ServiceBus.send(BusEvent.ServerStateChanged(this, oldstate))
      UiBus.send(BusEvent.ServerChanged(this))
    }
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
  def password_=(p: String) = _password =
    if (p == null || p.trim().length() == 0) null else p

  var _autorun: String = _
  def autorun = _autorun
  def autorun_=(a: String) = _autorun =
    if (a == null || a.trim().length() == 0) null else a

  var _autojoin: String = _
  def autojoin = _autojoin
  def autojoin_=(a: String) = _autojoin =
    if (a == null || a.trim().length() == 0) null else a

  var socks = false
  var socksHost: String = _
  var socksPort: Int = _
  var socksUser: String = _
  var socksPass: String = _

  var sasl = false
  var saslUser: String = _
  var saslPass: String = _

  var currentPing: Option[Long] = None
  var currentLag: Float = Float.NaN
  var currentNick = nickname
  override def toString() = name

  def blank(s: String) : Boolean = s == null || s == ""
  def valid: Boolean = {
    var valid = true
    valid = valid && !blank(name)
    valid = valid && !blank(hostname)
    valid = valid && port > 0
    valid = valid && !blank(nickname)
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
    values.put(Config.FIELD_AUTOJOIN,    autojoin)
    values.put(Config.FIELD_AUTORUN,     autorun)

    values.put(Config.FIELD_LOGGING,     logging)
    values.put(Config.FIELD_USESASL,     sasl)
    values.put(Config.FIELD_USESOCKS,    socks)
    values
  }
}

class ServerComparator extends java.util.Comparator[Server] {
  override def compare(s1: Server, s2: Server): Int = {
    var s1name = s1.name
    var s2name = s2.name
    var r = s1name.compareTo(s2name)
    if (r == 0)
      r = s1.username.compareTo(s2.username)
    r
  }
}
