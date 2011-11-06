package com.hanhuy.android.irc.model

import com.hanhuy.android.irc.Config

import android.content.ContentValues
import android.provider.BaseColumns

import scala.collection.mutable.HashSet

object Server {
    object State extends Enumeration {
        type State = Value
        val INITIAL, DISCONNECTED, CONNECTING, CONNECTED = Value
    }
}
class Server {

    import Server.State._
    val stateChangedListeners = new HashSet[(Server, State) => Any]

    val messages = new QueueAdapter[String]
    private var _state: State = INITIAL
    def state = _state
    def state_=(state: State) = {
        stateChangedListeners.foreach(_(this, _state))
        _state = state
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
    var password: String = _

    var autorun: String = _
    var autojoin: String = _

    var socks = false
    var socksHost: String = _
    var socksPort: Int = _
    var socksUser: String = _
    var socksPass: String = _

    var sasl = false
    var saslUser: String = _
    var saslPass: String = _

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
