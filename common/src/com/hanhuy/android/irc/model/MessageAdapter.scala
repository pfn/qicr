package com.hanhuy.android.irc.model

import com.hanhuy.android.irc.IrcListeners
import com.hanhuy.android.irc.IrcService
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
import android.util.Log
import android.content.Context
import android.text.Html
import android.widget.BaseAdapter
import android.widget.TextView
import android.view.{View, ViewGroup}

import java.text.SimpleDateFormat

import MessageAdapter._

object MessageAdapter {
    val TAG = "MessageAdapter"
    val DEFAULT_MAXIMUM_SIZE = 256
}

class MessageAdapter extends BaseAdapter with EventBus.RefOwner {
    var channel: ChannelLike = _
    var showJoinPartQuit = false
    var showTimestamp = false
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
        if (k == c.getString(R.string.pref_message_lines)) {
            val max = s.getString(R.string.pref_message_lines,
                    MessageAdapter.DEFAULT_MAXIMUM_SIZE.toString).toInt
            maximumSize = max
        } else if (k == c.getString(R.string.pref_show_join_part_quit)) {
            showJoinPartQuit = s.getBoolean(
                    R.string.pref_show_join_part_quit)
            notifyDataSetChanged()
        } else if (k == c.getString(R.string.pref_show_timestamp)) {
            showTimestamp = s.getBoolean(R.string.pref_show_timestamp)
            notifyDataSetChanged()
        }
    }

    lazy val sdf = new SimpleDateFormat("HH:mm ")

    var _inflater: WeakReference[LayoutInflater] = _
    def inflater = _inflater.get getOrElse { throw new IllegalStateException }
    var _activity: WeakReference[MainActivity] = _
    // can't make this IrcService due to resource changes on recreation
    def activity_= (c: MainActivity) = {
        if (c != null) {
            _activity = new WeakReference(c)
            _inflater = new WeakReference(c.systemService[LayoutInflater])
            val s = c.service.settings
            // It'd be nice to register a ServiceBus listener, but no way
            // to clean up when this adapter goes away?
            // add it to UiBus here maybe?
            maximumSize = s.getString(R.string.pref_message_lines,
                    DEFAULT_MAXIMUM_SIZE.toString).toInt
            showJoinPartQuit = s.getBoolean(R.string.pref_show_join_part_quit)
            showTimestamp = s.getBoolean(R.string.pref_show_timestamp)
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
    private def gets(res: Int, m: MessageLike, src: String, _msg: String) = {
        val server = channel.server
        // escape HTML
        val msg = _msg.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")

        if (channel.isInstanceOf[Query]) {
            if (server.currentNick.toLowerCase() == src.toLowerCase())
                Html.fromHtml(formatText(m, res,
                        "<font color=#00ffff>" + src + "</font>", msg))
            else
                Html.fromHtml(formatText(m, res,
                        "<font color=#ff0000>" + src + "</font>", msg))
        } else if (server.currentNick.toLowerCase() == src.toLowerCase()) {
            Html.fromHtml(formatText(m, res,
                    "<b>" + src + "</b>", msg))
        } else if (IrcListeners.matchesNick(server, msg) &&
                server.currentNick.toLowerCase() != src.toLowerCase()) {
            Html.fromHtml(formatText(m, res,
                    "<font color=#00ff00>" + src + "</font>", msg))
        } else
            Html.fromHtml(formatText(m, res, src, msg))
    }
    private def getString(res: Int, args: String*): String = {
        res match {
        case -1 => args(0)
        case _ => activity.getString(res, args: _*)
        }
    }
    private def formatText(msg: MessageLike, res: Int, args: String*):
            String = {
        (if (showTimestamp) sdf.format(msg.ts) else "") +
                getString(res, args: _*)
    }
    private def formatText(msg: MessageLike): CharSequence = {
        msg match {
            case Privmsg(s, m, o, v) => gets(R.string.message_template, msg,
                        {if (o) "@" else if (v) "+" else ""} + s, m)
            case Notice(s, m) => gets(R.string.notice_template, msg, s, m)
            case CtcpAction(s, m) => gets(R.string.action_template, msg, s, m)
            case Topic(src, t) => src map {
                    formatText(msg, R.string.topic_template_2,
                            _, channel.name, t)
                } getOrElse {
                    formatText(msg, R.string.topic_template_1, channel.name, t)
                }
            case NickChange(o, n) =>
                formatText(msg, R.string.nick_change_template, o, n)
            case Join(n, u)    => formatText(msg, R.string.join_template, n, u)
            case Part(n, u, m) => formatText(msg, R.string.part_template, n, u,
                    if (m == null) "" else m)
            case Quit(n, u, m) => formatText(msg, R.string.quit_template,
                    n, u, m)
            case Kick(o, n, m) => formatText(msg, R.string.kick_template, o, n,
                    if (m == null) "" else m)

            case CommandError(m)  => formatText(msg, -1, m)
            case ServerInfo(m)    => formatText(msg, -1, m)
            case Motd(m)          => formatText(msg, -1, m)
            case SslInfo(m)       => formatText(msg, -1, m)
            case SslError(m)      => formatText(msg, -1, m)
        }
    }
}
