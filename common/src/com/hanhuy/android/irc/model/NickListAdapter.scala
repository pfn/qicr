package com.hanhuy.android.irc.model

import com.hanhuy.android.irc.UiBus
import com.hanhuy.android.irc.MainActivity
import com.hanhuy.android.irc.IrcService
import com.hanhuy.android.irc.R

import android.view.LayoutInflater
import android.content.Context
import android.widget.BaseAdapter
import android.widget.TextView
import android.view.{View, ViewGroup}

import scala.collection.JavaConversions._

import com.sorcix.sirc.{Channel => SircChannel}

class NickListAdapter(activity: MainActivity, channel: Channel)
extends BaseAdapter {
    var c: SircChannel = _
    def getc(s: IrcService) {
        s.channels.get(channel).foreach(c = _)
        notifyDataSetChanged()
    }
    if (activity.service == null)
        UiBus += { case BusEvent.ServiceConnected(s) => getc(s) }
    else
        getc(activity.service)

    val inflater = activity.getSystemService(Context.LAYOUT_INFLATER_SERVICE)
            .asInstanceOf[LayoutInflater]

    var nicks: List[String] = _
    override def notifyDataSetChanged() {
        if (c == null) return
        // oy, this is super-slow when called a bunch of times rapidly
        // try to prevent CME by copying
        nicks = List(c.getUsers().toSeq: _*).map { u =>
            (if (u.hasOperator) "@" else if (u.hasVoice) "+" else "") +
                    u.getNick
        }.toList.filter {
            _ != "***" // znc playback user
        } sortWith { (a, b) =>
            val (x,y) = (a.charAt(0), b.charAt(0))
            if      (x == '@' && y != '@')             true
            else if (x != '@' && y == '@')             false
            else if (x == '+' && y != '@' && y != '+') true
            else if (y == '+' && x != '@' && x != '+') false
            else a.toLowerCase.compareTo(b.toLowerCase) < 0
        }

        super.notifyDataSetChanged()
    }
    override def getItemId(pos: Int) : Long = pos
    override def getItem(pos: Int) : String = nicks(pos)
    override def getCount() : Int = if (nicks != null) nicks.size else 0
    override def getView(pos: Int, convertView: View, container: ViewGroup) :
            View = createViewFromResource(pos, convertView, container)

    private def createViewFromResource(
            pos: Int, convertView: View, container: ViewGroup): View = {
        var view: TextView = convertView.asInstanceOf[TextView]
        if (view == null) {
            view = inflater.inflate(R.layout.nicklist_item,
                    container, false).asInstanceOf[TextView]
        }

        view.setText(getItem(pos))
        view
    }
}
