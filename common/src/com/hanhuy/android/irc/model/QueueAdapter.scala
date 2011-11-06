package com.hanhuy.android.irc.model

import com.hanhuy.android.irc.R

import scala.collection.mutable.Queue
import scala.ref.WeakReference

import android.view.LayoutInflater
import android.content.Context
import android.widget.BaseAdapter
import android.widget.TextView
import android.view.{View, ViewGroup}

class QueueAdapter[T<:Object] extends BaseAdapter {
    var _maximumSize = 128
    def maximumSize = _maximumSize
    def maximumSize_= (size: Int) = {
        _maximumSize = size
        ensureSize()
    }
    var items = new Queue[T]

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

    def clear() {
        items.clear()
        notifyDataSetChanged()
    }
    private def ensureSize() {
        while (items.size > _maximumSize && !items.isEmpty)
            items.dequeue()
    }

    def add(item: T) {
        items += item
        ensureSize()
        notifyDataSetChanged()
    }

    override def getItemId(pos: Int) : Long = pos
    override def getItem(pos: Int) : T = items(pos)
    override def getCount() : Int = items.size
    override def getView(pos: Int, convertView: View, container: ViewGroup) :
            View = createViewFromResource(pos, convertView, container)
    private def createViewFromResource(
            pos: Int, convertView: View, container: ViewGroup): View = {
        var view = convertView
        if (view == null)
            view = inflater.inflate(R.layout.message_item, container, false)

        view.asInstanceOf[TextView].setText(items(pos).toString())
        view
    }
}
