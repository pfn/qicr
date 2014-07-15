package com.hanhuy.android.irc.model

import com.hanhuy.android.irc.{IrcManager, TypedResource, MainActivity, TR}
import com.hanhuy.android.common._
import TypedResource._
import com.hanhuy.android.irc.model.BusEvent.NickListChanged
import SpannedGenerator._

import android.view.LayoutInflater
import android.widget.BaseAdapter
import android.widget.TextView
import android.view.{View, ViewGroup}

import scala.collection.JavaConversions._

import com.sorcix.sirc.{Channel => SircChannel}
import com.hanhuy.android.irc.model.BusEvent.NickListChanged
import scala.ref.WeakReference
import AndroidConversions._

object NickListAdapter {
  val adapters = new collection.mutable.WeakHashMap[
    MainActivity,Map[Channel,NickListAdapter]]()
  def apply(activity: MainActivity, channel: Channel) = {
    val m = adapters.getOrElse(activity, {
      adapters += ((activity, Map.empty))
      adapters(activity)
    })

    m.getOrElse(channel, {
      adapters += ((activity,
        m + ((channel, new NickListAdapter(
          new WeakReference(activity), channel)))))
      adapters(activity)(channel)
    })
  }
}

case class NickAndMode(mode: Char, nick: String)

// must reference activity for resources
class NickListAdapter(activity: WeakReference[MainActivity], channel: Channel)
extends BaseAdapter with EventBus.RefOwner {
  val manager = IrcManager.start()
    var c: SircChannel = _
    manager.channels.get(channel).foreach(c = _)
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
