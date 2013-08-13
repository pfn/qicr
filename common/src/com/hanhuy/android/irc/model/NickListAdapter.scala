package com.hanhuy.android.irc.model

import com.hanhuy.android.irc.EventBus
import com.hanhuy.android.irc.UiBus
import com.hanhuy.android.irc.MainActivity
import com.hanhuy.android.irc.R
import com.hanhuy.android.irc.AndroidConversions._

import android.view.LayoutInflater
import android.widget.BaseAdapter
import android.widget.TextView
import android.view.{View, ViewGroup}

import scala.collection.JavaConversions._

import com.sorcix.sirc.{Channel => SircChannel}
import com.hanhuy.android.irc.model.BusEvent.NickListChanged
import scala.collection

object NickListAdapter {
  val adapters = new collection.mutable.WeakHashMap[
    MainActivity,collection.mutable.HashMap[Channel,NickListAdapter]]()
  def apply(activity: MainActivity, channel: Channel) = {
    val m = adapters.get(activity) getOrElse {
      adapters += ((activity, new collection.mutable.HashMap()))
      adapters(activity)
    }

    m.get(channel) getOrElse {
      m += ((channel,new NickListAdapter(activity, channel)))
      m(channel)
    }
  }
}

// must reference activity for resources
class NickListAdapter(activity: MainActivity, channel: Channel)
extends BaseAdapter with EventBus.RefOwner {
    var c: SircChannel = _
    activity.service.channels.get(channel).foreach(c = _)
    notifyDataSetChanged()

    val inflater = activity.systemService[LayoutInflater]

    var nicks: List[String] = _
    override def notifyDataSetChanged() {
        if (c == null) return
        // oy, this is super-slow when called a bunch of times rapidly
        // try to prevent CME by copying
        nicks = List(c.getUsers.toSeq: _*).map { u =>
            (if (u.hasOperator) "@" else if (u.hasVoice) "+" else "") +
                    u.getNick
        }.toList.filter {
            _ != "***" // znc playback user
        } sortWith { (a, b) =>
            (a.charAt(0), b.charAt(0)) match {
            case ('@', y) if y != '@'             => true
            case (x, '@') if x != '@'             => false
            case ('+', y) if y != '@' && y != '+' => true
            case (x, '+') if x != '@' && x != '+' => false
            case (_,_) => a.compareToIgnoreCase(b) < 0
            }
        }

        super.notifyDataSetChanged()
    }
    override def getItemId(pos: Int) : Long = pos
    override def getItem(pos: Int) : String = nicks(pos)
    override def getCount : Int = if (nicks != null) nicks.size else 0
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

  UiBus += {
    case NickListChanged(ch) => if (ch == channel) notifyDataSetChanged()
  }
}
