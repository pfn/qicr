package com.hanhuy.android.irc.model

import com.hanhuy.android.irc.EventBus
import com.hanhuy.android.irc.UiBus
import com.hanhuy.android.irc.MainActivity
import com.hanhuy.android.irc.TR
import com.hanhuy.android.irc.TypedResource._
import com.hanhuy.android.irc.AndroidConversions._
import com.hanhuy.android.irc.SpannedGenerator.textColor

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

case class NickAndMode(mode: Char, nick: String)

// must reference activity for resources
class NickListAdapter(activity: WeakReference[MainActivity], channel: Channel)
extends BaseAdapter with EventBus.RefOwner {
    var c: SircChannel = _
    activity.get map { _.service.channels.get(channel).foreach(c = _) }
    notifyDataSetChanged()

    def inflater = activity().systemService[LayoutInflater]

    var nicks: List[android.text.Spanned] = _
    override def notifyDataSetChanged() {
      if (c == null) return
      nicks = c.getUsers.toList.map { u =>
        val prefix = if (u.hasOperator) '@' else if (u.hasVoice) '+' else ' '
        NickAndMode(prefix, u.getNick)
      }.filterNot {
        _.nick == "***" // znc playback user
      }.sortWith { (a, b) =>
        (a.mode, b.mode) match {
          case ('@', y) if y != '@'             => true
          case (x, '@') if x != '@'             => false
          case ('+', y) if y != '@' && y != '+' => true
          case (x, '+') if x != '@' && x != '+' => false
          case (_,_) => a.nick.compareToIgnoreCase(b.nick) < 0
        }
      }.map { n =>
        "%1%2" formatSpans (String.valueOf(n.mode),
          textColor(MessageAdapter.nickColor(n.nick), n.nick))
      }
      super.notifyDataSetChanged()
    }

    override def getItemId(pos: Int) : Long = pos
    override def getItem(pos: Int) = nicks(pos)
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
