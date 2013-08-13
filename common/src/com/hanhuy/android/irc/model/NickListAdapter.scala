package com.hanhuy.android.irc.model

import com.hanhuy.android.irc.EventBus
import com.hanhuy.android.irc.UiBus
import com.hanhuy.android.irc.MainActivity
import com.hanhuy.android.irc.TR
import com.hanhuy.android.irc.TypedResource._
import com.hanhuy.android.irc.AndroidConversions._

import android.view.LayoutInflater
import android.widget.BaseAdapter
import android.widget.TextView
import android.view.{View, ViewGroup}

import scala.collection.JavaConversions._

import com.sorcix.sirc.{Channel => SircChannel}
import com.hanhuy.android.irc.model.BusEvent.NickListChanged
import scala.collection
import scala.ref.WeakReference

object NickListAdapter {
  val adapters = new collection.mutable.WeakHashMap[
    MainActivity,Map[Channel,NickListAdapter]]()
  def apply(activity: MainActivity, channel: Channel) = {
    val m = adapters.get(activity) getOrElse {
      adapters += ((activity, Map.empty))
      adapters(activity)
    }

    m.get(channel) getOrElse {
      adapters += ((activity,
        m + ((channel, new NickListAdapter(
          new WeakReference(activity), channel)))))
      adapters(activity)(channel)
    }
  }
}

// must reference activity for resources
class NickListAdapter(activity: WeakReference[MainActivity], channel: Channel)
extends BaseAdapter with EventBus.RefOwner {
    var c: SircChannel = _
    activity.get map { _.service.channels.get(channel).foreach(c = _) }
    notifyDataSetChanged()

    def inflater = activity().systemService[LayoutInflater]

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
      val c = convertView.asInstanceOf[TextView]
      val view = if (c != null) c else
        inflater.inflate(TR.layout.nicklist_item, container, false)

      view.setText(getItem(pos))
      view
    }

  UiBus += {
    case NickListChanged(ch) => if (ch == channel) notifyDataSetChanged()
  }
}
