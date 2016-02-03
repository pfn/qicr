package com.hanhuy.android.irc.model

import android.content.ContentValues
import com.hanhuy.android.irc.Config
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
  object ServerData {
    val EMPTY = "UNINITIALIZED"
    def empty = ServerData(-1, EMPTY, EMPTY, EMPTY, EMPTY)
  }
  case class ServerData(id: Long,
                        name: String,
                        hostname: String,
                        nickname: String,
                        altnick: String,
                        port: Int = 6667,
                        realname: String = "stronger, better, qicr",
                        username: String = "qicruser",
                        ssl: Boolean = false,
                        autoconnect: Boolean = false,
                        password: Option[String] = None,
                        sasl: Boolean = false,
                        autorun: Option[String] = None,
                        autojoin: Option[String] = None
                       ) {
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
      values.put(Config.FIELD_PASSWORD,    password.orNull)
      values.put(Config.FIELD_REALNAME,    realname)
      values.put(Config.FIELD_AUTOJOIN,    autojoin.orNull)
      values.put(Config.FIELD_AUTORUN,     autorun.orNull)

      values.put(Config.FIELD_LOGGING,     false)
      values.put(Config.FIELD_USESASL,     sasl)
      values.put(Config.FIELD_USESOCKS,    false)
      values
    }
  }
}
class Server extends MessageAppender with Ordered[Server] {

  import Server._
  val messages = new MessageAdapter(null)
  private[this] var _data = ServerData.empty
  def data = _data
  def data_=(d: ServerData) = {
    _data = d
    if (state.now != Server.CONNECTED)
      currentNick = d.nickname
    UiBus.send(BusEvent.ServerChanged(this))
  }
  var currentNick = data.nickname

  def name = data.name
  def id = data.id
  def altnick = data.altnick
  def nickname = data.nickname
  def port = data.port
  def hostname = data.hostname
  def password = data.password
  def realname = data.realname
  def ssl = data.ssl
  def username = data.username
  def autoconnect = data.autoconnect
  def sasl = data.sasl
  def autorun = data.autorun
  def autojoin = data.autojoin

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

  var currentPing: Option[Long] = None
  val currentLag = Var(0)

  override def toString = data.name

  def values = data.values

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
