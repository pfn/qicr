package com.hanhuy.android.irc.model

import android.graphics.Color
import android.text.style.StrikethroughSpan
import com.hanhuy.android.common._
import com.hanhuy.android.irc._
import com.hanhuy.android.common._
import SpannedGenerator._

import android.view.{Gravity, LayoutInflater, View, ViewGroup}
import android.widget.{AbsListView, BaseAdapter, TextView}

import scala.collection.JavaConversions._

import com.sorcix.sirc.{Channel => SircChannel}
import com.hanhuy.android.irc.model.BusEvent.{IgnoreListChanged, NickListChanged}
import scala.ref.WeakReference
import iota._

object NickListAdapter {
  val adapters = new collection.mutable.WeakHashMap[
    MainActivity,Map[Option[Channel],NickListAdapter]]()
  def apply(activity: MainActivity, channel: Option[Channel]) = {
    val m = adapters.getOrElse(activity, {
      adapters += ((activity, Map.empty))
      adapters(activity)
    })

    m.getOrElse(channel, {
      adapters += ((activity,
        m + ((channel, new NickListAdapter(
          new WeakReference(activity), channel.get)))))
      adapters(activity)(channel)
    })
  }
}

case class NickAndMode(mode: Char, nick: String)

// must reference activity for resources
class NickListAdapter(activity: WeakReference[MainActivity], channel: Channel)
extends BaseAdapter with EventBus.RefOwner with HasContext {
  import ViewGroup.LayoutParams._
  import Tweaks._
  val manager = IrcManager.start()
  var c: SircChannel = _
  manager.channels.get(channel).foreach(c = _)
  notifyDataSetChanged()

  override def context = activity()

  val layout = iota.std.Views.c[AbsListView](w[TextView] >>= id(android.R.id.text1) >>=
    backgroundResource(R.drawable.selector_background) >>=
    lp(MATCH_PARENT, WRAP_CONTENT) >>= kestrel { tv: TextView =>
      tv.setTextAppearance(activity(), android.R.style.TextAppearance_Small)
      tv.setGravity(Gravity.CENTER_VERTICAL)
      tv.setMinHeight(36 dp)
      tv.setShadowLayer(1.2f, 0, 0, Color.parseColor("#ff333333"))
    } >>= padding(left = 6 dp))

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
      val colored = textColor(MessageAdapter.nickColor(n.nick), n.nick)
      val s = if (Config.Ignores(n.nick))
        span(new StrikethroughSpan, colored) else colored

      "%1%2" formatSpans (String.valueOf(n.mode), s)
    }
    super.notifyDataSetChanged()
  }

  override def getItemId(pos: Int) = pos
  override def getItem(pos: Int) = nicks(pos)
  override def getCount : Int = if (nicks != null) nicks.size else 0
  override def getView(pos: Int, convertView: View, container: ViewGroup) = {
    val c = convertView.asInstanceOf[TextView]
    val view = if (c != null) c else layout.perform()

    view.setText(getItem(pos))
    view
  }

  UiBus += {
    case NickListChanged(ch) => if (ch == channel) notifyDataSetChanged()
    case IgnoreListChanged => notifyDataSetChanged()
  }
}
