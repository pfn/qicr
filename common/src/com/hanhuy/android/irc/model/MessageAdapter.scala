package com.hanhuy.android.irc.model

import com.hanhuy.android.irc.IrcListeners
import com.hanhuy.android.irc.MainActivity
import com.hanhuy.android.irc.Settings
import com.hanhuy.android.irc.EventBus
import com.hanhuy.android.irc.UiBus
import com.hanhuy.android.irc.AndroidConversions._

import MessageLike._

import com.hanhuy.android.irc.R

import scala.collection.mutable.Queue
import scala.ref.WeakReference

import android.graphics.Typeface
import android.view.LayoutInflater
import android.content.Context
import android.text.Html
import android.widget.BaseAdapter
import android.widget.TextView
import android.view.{View, ViewGroup}

import java.text.SimpleDateFormat

import MessageAdapter._

trait MessageAppender {
  def add(m: MessageLike): Unit
}

object MessageAdapter {
  val TAG = "MessageAdapter"
  val DEFAULT_MAXIMUM_SIZE = 256
  private var showTimestamp = false
  private lazy val sdf = new SimpleDateFormat("HH:mm ")

  def formatText(c: Context, msg: MessageLike)
      (implicit channel: ChannelLike = null): CharSequence = {
    val ch = Option(channel)
    msg match {
      case Privmsg(s, m, o, v) => gets(c, R.string.message_template, msg,
        {if (o) "@" else if (v) "+" else ""} + s, m)
      case Notice(s, m) => gets(c, R.string.notice_template, msg, s, m)
      case CtcpAction(s, m) => gets(c, R.string.action_template, msg, s, m)
      case CtcpRequest(server, t, cmd, args) =>
        ch flatMap { chan =>
          if (chan.server != server)
            Some(getString(c, R.string.ctcp_request_template_s,
              t, cmd, args getOrElse "", server.name))
          else
            None
        } getOrElse {
          getString(c, R.string.ctcp_request_template,
            t, cmd, args getOrElse "")
        }
      case CtcpReply(server, s, cmd, a) => a map { arg =>
        ch flatMap { chan =>
          if (chan.server != server)
            Some(getString(c, R.string.ctcp_response_template_s_3,
              cmd, s, arg, server.name))
          else
            None
        } getOrElse {
          getString(c, R.string.ctcp_response_template_3, cmd, s, arg)
        }
      } getOrElse {
        ch flatMap { chan =>
          if (chan.server != server)
            Some(getString(c, R.string.ctcp_response_template_s_2,
              cmd, s, server.name))
          else
            None
        } getOrElse {
          getString(c, R.string.ctcp_response_template_2, cmd, s)
        }
      }
      case Topic(src, t) => src map {
        formatText(c, msg, R.string.topic_template_2, _, channel.name, t)
      } getOrElse {
        formatText(c, msg, R.string.topic_template_1, channel.name, t)
      }
      case NickChange(o, n) =>
        formatText(c, msg, R.string.nick_change_template, o, n)
      case Join(n, u)    => formatText(c, msg, R.string.join_template, n, u)
      case Part(n, u, m) => formatText(c, msg, R.string.part_template, n, u,
        if (m == null) "" else m)
      case Quit(n, u, m) => formatText(c, msg, R.string.quit_template, n, u, m)
      case Kick(o, n, m) => formatText(c, msg, R.string.kick_template, o, n,
        if (m == null) "" else m)

      case CommandError(m)  => formatText(c, msg, -1, m)
      case ServerInfo(m)    => formatText(c, msg, -1, m)
      case Motd(m)          => formatText(c, msg, -1, m)
      case SslInfo(m)       => formatText(c, msg, -1, m)
      case SslError(m)      => formatText(c, msg, -1, m)
    }
  }

  private def formatText(c: Context, msg: MessageLike, res: Int,
      args: String*) =
    (if (showTimestamp) sdf.format(msg.ts) else "") + getString(c, res, args:_*)

  private def getString(c: Context, res: Int, args: String*) = {
    res match {
    case -1 => args(0)
    case _ => c.getString(res, args: _*)
    }
  }

  private def gets(c: Context, res: Int, m: MessageLike, src: String,
      _msg: String)(implicit channel: ChannelLike) = {
    val server = channel.server
    // escape HTML
    val msg = _msg.replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")

    if (channel.isInstanceOf[Query]) {
      if (server.currentNick.toLowerCase() == src.toLowerCase())
        Html.fromHtml(formatText(c, m, res,
          "<font color=#00ffff>" + src + "</font>", msg))
      else
        Html.fromHtml(formatText(c, m, res,
          "<font color=#ff0000>" + src + "</font>", msg))
    } else if (server.currentNick.toLowerCase() == src.toLowerCase()) {
      Html.fromHtml(formatText(c, m, res, "<b>" + src + "</b>", msg))
    } else if (IrcListeners.matchesNick(server, msg) &&
        server.currentNick.toLowerCase() != src.toLowerCase()) {
      Html.fromHtml(formatText(c, m, res,
        "<font color=#00ff00>" + src + "</font>", msg))
    } else
      Html.fromHtml(formatText(c, m, res, src, msg))
  }
}

class MessageAdapter extends BaseAdapter with EventBus.RefOwner {
  implicit var channel: ChannelLike = _

  var showJoinPartQuit = false
  val messages = new Queue[MessageLike]
  var _maximumSize = DEFAULT_MAXIMUM_SIZE
  def maximumSize = _maximumSize
  def maximumSize_=(s: Int) = {
      _maximumSize = s
      ensureSize()
  }
    // only register once to prevent memory leak
  UiBus += { case BusEvent.PreferenceChanged(s, k) =>
      val c = s.context
      if (k == Settings.MESSAGE_LINES) {
        val max = s.get(Settings.MESSAGE_LINES).toInt
        maximumSize = max
      } else if (k == Settings.SHOW_JOIN_PART_QUIT) {
        showJoinPartQuit = s.get(Settings.SHOW_JOIN_PART_QUIT)
        notifyDataSetChanged()
      } else if (k == Settings.SHOW_TIMESTAMP) {
        MessageAdapter.showTimestamp = s.get(Settings.SHOW_TIMESTAMP)
        notifyDataSetChanged()
      }
    }

    var _inflater: WeakReference[LayoutInflater] = _
    def inflater = _inflater.get getOrElse { throw new IllegalStateException }
    var _activity: WeakReference[MainActivity] = _
    // can't make this IrcService due to resource changes on recreation
    def activity_= (c: MainActivity) = {
      if (c != null) {
        _activity = new WeakReference(c)
        _inflater = new WeakReference(c.systemService[LayoutInflater])
        if (c.service != null) {
          val s = c.service.settings
          // It'd be nice to register a ServiceBus listener, but no way
          // to clean up when this adapter goes away?
          // add it to UiBus here maybe?
          maximumSize = s.get(Settings.MESSAGE_LINES).toInt
          showJoinPartQuit = s.get(Settings.SHOW_JOIN_PART_QUIT)
          MessageAdapter.showTimestamp = s.get(Settings.SHOW_TIMESTAMP)
        }
      }
  }

  def activity = _activity.get getOrElse { throw new IllegalStateException }
  // would be nice to move this into the companion
  lazy val font =
    Typeface.createFromAsset(activity.getAssets(), "DejaVuSansMono.ttf")

  def clear() {
    messages.clear()
    notifyDataSetChanged()
  }

  private def ensureSize() {
    while (messages.size > _maximumSize && !messages.isEmpty)
      messages.dequeue()
  }

  protected[model] def add(item: MessageLike) {
    messages += item
    ensureSize()
    if (_activity != null && isMainThread)
      _activity.get.foreach { _ => notifyDataSetChanged() }
  }

  def filteredMessages = {
    if (showJoinPartQuit)
      messages
    else
      messages.filter {
        case Join(_,_)   => false
        case Part(_,_,_) => false
        case Quit(_,_,_) => false
        case _           => true
      }
  }

  override def getItemId(pos: Int) : Long = pos
  override def getItem(pos: Int) : MessageLike = filteredMessages(pos)
  override def getCount() : Int = filteredMessages.size

  override def getView(pos: Int, convertView: View, container: ViewGroup) =
    createViewFromResource(pos, convertView, container)
  private def createViewFromResource(
      pos: Int, convertView: View, container: ViewGroup): View = {
    var view: TextView = convertView.asInstanceOf[TextView]
    if (view == null) {
      view = inflater.inflate(R.layout.message_item, container, false)
        .asInstanceOf[TextView]
      if (!icsAndNewer)
        view.setTypeface(font)
    }

    view.setText(formatText(getItem(pos)))
    view
  }

  private def formatText(msg: MessageLike) =
    MessageAdapter.formatText(activity, msg)
}
