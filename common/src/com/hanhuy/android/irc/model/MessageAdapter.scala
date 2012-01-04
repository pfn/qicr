package com.hanhuy.android.irc.model

import com.hanhuy.android.irc.IrcListeners
import com.hanhuy.android.irc.MainActivity
import com.hanhuy.android.irc.Settings
import com.hanhuy.android.irc.AndroidConversions

import MessageLike._

import com.hanhuy.android.irc.R

import scala.collection.mutable.Queue
import scala.ref.WeakReference

import android.os.Looper
import android.graphics.Typeface
import android.view.LayoutInflater
import android.util.Log
import android.content.Context
import android.text.Html
import android.widget.BaseAdapter
import android.widget.TextView
import android.view.{View, ViewGroup}

import MessageAdapter._

object MessageAdapter {
    val TAG = "MessageAdapter"
    val DEFAULT_MAXIMUM_SIZE = 256
}

class MessageAdapter extends BaseAdapter {
    var channel: ChannelLike = _
    var showJoinPartQuit = false
    val messages = new Queue[MessageLike]
    var _maximumSize = DEFAULT_MAXIMUM_SIZE
    def maximumSize = _maximumSize
    def maximumSize_=(s: Int) = {
        _maximumSize = s
        ensureSize()
    }

    var _inflater: WeakReference[LayoutInflater] = _
    def inflater = _inflater.get match {
        case Some(i) => i
        case None => throw new IllegalStateException
    }
    var _context: WeakReference[MainActivity] = _
    def context_= (c: MainActivity) = {
        if (c != null) {
            _context = new WeakReference(c)
            _inflater = new WeakReference(c.getSystemService(
                    Context.LAYOUT_INFLATER_SERVICE)
                            .asInstanceOf[LayoutInflater])
            val s = new Settings(c)
            maximumSize = s.getString(R.string.pref_message_lines,
                    DEFAULT_MAXIMUM_SIZE.toString).toInt
            showJoinPartQuit = s.getBoolean(R.string.pref_show_join_part_quit)
        }
    }
    def context = _context.get match {
        case Some(c) => c
        case None => throw new IllegalStateException
    }
    lazy val font =
            Typeface.createFromAsset(context.getAssets(), "DejaVuSansMono.ttf")

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
        if (_context != null && Looper.getMainLooper.getThread == currentThread)
            _context.get.foreach { _ => notifyDataSetChanged() }
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
            if (!AndroidConversions.icsAndNewer)
                view.setTypeface(font)
        }

        val m = getItem(pos) match {
            case Privmsg(s, m, o, v) => gets(R.string.message_template,
                        {if (o) "@" else if (v) "+" else ""} + s, m)
            case Notice(s, m)        => gets(R.string.notice_template, s, m)
            case CtcpAction(s, m)    => gets(R.string.action_template, s, m)
            case Topic(chan, src, t) => {
                src match {
                case Some(s) => getString(R.string.topic_template_2, s, chan, t)
                case None    => getString(R.string.topic_template_1, chan, t)
                }
            }
            case NickChange(o, n) =>
                getString(R.string.nick_change_template, o, n)
            case Join(n, u)    => getString(R.string.join_template, n, u)
            case Part(n, u, m) => getString(R.string.part_template, n, u,
                    if (m == null) "" else m)
            case Quit(n, u, m) => getString(R.string.quit_template, n, u, m)
            case Kick(o, n, m) => getString(R.string.kick_template, o, n,
                    if (m == null) "" else m)

            case CommandError(m)  => m
            case ServerInfo(m)    => m
            case Motd(m)          => m
            case SslInfo(m)       => m
            case SslError(m)      => m
        }
        view.setText(m)
        view
    }
    private def gets(res: Int, src: String, _msg: String) = {
        val server = channel.server
        // escape HTML
        val msg = _msg.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
        if (channel.isInstanceOf[Query]) {
            if (server.currentNick.toLowerCase() == src.toLowerCase())
                Html.fromHtml(getString(res,
                        "<font color=#00ffff>" + src + "</font>", msg))
            else
                Html.fromHtml(getString(res,
                        "<font color=#ff0000>" + src + "</font>", msg))
        } else if (server.currentNick.toLowerCase() == src.toLowerCase()) {
            Html.fromHtml(getString(res,
                    "<b>" + src + "</b>", msg))
        } else if (IrcListeners.matchesNick(server, msg) &&
                server.currentNick.toLowerCase() != src.toLowerCase()) {
            Html.fromHtml(getString(res,
                    "<font color=#00ff00>" + src + "</font>", msg))
        } else
            Html.fromHtml(getString(res, src, msg))
    }
    private def getString(res: Int, args: String*) =
            context.getString(res, args: _*)
}
