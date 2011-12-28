package com.hanhuy.android.irc.model

import com.hanhuy.android.irc.IrcListeners
import com.hanhuy.android.irc.MainActivity
import com.hanhuy.android.irc.Settings
import com.hanhuy.android.irc.AndroidConversions

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

import MessageAdapter._

object MessageAdapter {
    val TAG = "MessageAdapter"
    val DEFAULT_MAXIMUM_SIZE = 256
}
class MessageAdapter extends BaseAdapter {
    var channel: ChannelLike = _
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
        }
    }
    def context = _context.get match {
        case Some(c) => c
        case None => throw new IllegalStateException
    }
    lazy val font = {
        Typeface.createFromAsset(context.getAssets(), "DejaVuSansMono.ttf")
    }

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
        if (_context != null)
            _context.get.foreach { _ => notifyDataSetChanged() }
    }

    override def getItemId(pos: Int) : Long = pos
    override def getItem(pos: Int) : MessageLike = messages(pos)
    override def getCount() : Int = messages.size
    override def getView(pos: Int, convertView: View, container: ViewGroup) :
            View = createViewFromResource(pos, convertView, container)
    private def createViewFromResource(
            pos: Int, convertView: View, container: ViewGroup): View = {
        var view: TextView = convertView.asInstanceOf[TextView]
        if (view == null) {
            view = inflater.inflate(R.layout.message_item, container, false)
                    .asInstanceOf[TextView]
            if (!AndroidConversions.icsAndNewer)
                view.setTypeface(font)
        }

        val m = messages(pos) match {
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
            case CommandError(m)  => m
            case ServerInfo(m)    => m
            case Motd(m)          => m
            case SslInfo(m)       => m
            case SslError(m)      => m
        }
        view.setText(m)
        view
    }
    private def gets(res: Int, src: String, msg: String) = {
        val server = channel.server
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
