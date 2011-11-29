package com.hanhuy.android.irc.model

import MessageLike._

import com.hanhuy.android.irc.R

import scala.collection.mutable.Queue
import scala.ref.WeakReference

import android.graphics.Typeface
import android.view.LayoutInflater
import android.content.Context
import android.widget.BaseAdapter
import android.widget.TextView
import android.view.{View, ViewGroup}

class MessageAdapter extends BaseAdapter {
    var channel: Option[ChannelLike] = None
    var _maximumSize = 128
    def maximumSize = _maximumSize
    def maximumSize_= (size: Int) = {
        _maximumSize = size
        ensureSize()
    }
    var items = new Queue[MessageLike]

    var _inflater: WeakReference[LayoutInflater] = _
    def inflater = _inflater.get match {
        case Some(i) => i
        case None    => null
    }
    var _context: WeakReference[Context] = _
    def context_= (c: Context) = {
        _context = new WeakReference(c)
        _inflater = new WeakReference(c.getSystemService(
                Context.LAYOUT_INFLATER_SERVICE).asInstanceOf[LayoutInflater])
    }
    def context = _context.get match {
        case Some(c) => c
        case None    => null
    }
    lazy val font = {
        Typeface.createFromAsset(context.getAssets(), "DejaVuSansMono.ttf")
    }

    def clear() {
        items.clear()
        notifyDataSetChanged()
    }
    private def ensureSize() {
        while (items.size > _maximumSize && !items.isEmpty)
            items.dequeue()
    }

    protected[model] def add(item: MessageLike) {
        items += item
        ensureSize()
        notifyDataSetChanged()
    }

    override def getItemId(pos: Int) : Long = pos
    override def getItem(pos: Int) : MessageLike = items(pos)
    override def getCount() : Int = items.size
    override def getView(pos: Int, convertView: View, container: ViewGroup) :
            View = createViewFromResource(pos, convertView, container)
    private def createViewFromResource(
            pos: Int, convertView: View, container: ViewGroup): View = {
        var view: TextView = convertView.asInstanceOf[TextView]
        if (view == null) {
            view = inflater.inflate(R.layout.message_item, container, false)
                    .asInstanceOf[TextView]
            view.setTypeface(font)
        }

        val m = items(pos) match {
            case Privmsg(s, m)    => context.getString(
                    R.string.message_template, s, m)
            case Notice(s, m)     => context.getString(
                    R.string.notice_template, s, m)
            case CtcpAction(s, m) => context.getString(
                    R.string.action_template, s, m)
            case Topic(chan, src, t)    => {
                src match {
                case Some(s) =>
                        context.getString(R.string.topic_template_2, s, chan, t)
                case None =>
                        context.getString(R.string.topic_template_1, chan, t)
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
}
