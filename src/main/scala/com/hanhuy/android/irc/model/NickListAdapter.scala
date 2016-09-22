package com.hanhuy.android.irc.model

import android.graphics.Color
import android.text.style.StrikethroughSpan
import com.hanhuy.android.irc._
import com.hanhuy.android.common._
import SpannedGenerator._

import android.view.{Gravity, View, ViewGroup}
import android.widget.{AbsListView, BaseAdapter, TextView}

import scala.collection.JavaConverters._

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

object NickAndMode {
  implicit val nickOrdering: Ordering[NickAndMode] = new Ordering[NickAndMode] {
    override def compare(x: NickAndMode, y: NickAndMode) = {
      val r = y.mode.order - x.mode.order
      if (r == 0) {
        x.nick.compareToIgnoreCase(y.nick)
      } else r
    }
  }
}
case class NickAndMode(mode: UserMode, nick: String)

// must reference activity for resources
class NickListAdapter private(activity: WeakReference[MainActivity], channel: Channel)
extends BaseAdapter with EventBus.RefOwner with HasContext {
  import ViewGroup.LayoutParams._
  val manager = IrcManager.init()
  val c = manager.channels.get(channel)
  var nicks: Vector[NickAndMode] = Vector.empty
  notifyDataSetChanged()

  override def context = activity()

  val layout = iota.std.Views.c[AbsListView](w[TextView] >>=
    k.backgroundResource(R.drawable.selector_background) >>=
    lp(MATCH_PARENT, WRAP_CONTENT) >>= kestrel { tv: TextView =>
      tv.setTextAppearance(activity(), android.R.style.TextAppearance_Small)
      tv.setGravity(Gravity.CENTER_VERTICAL)
      tv.setMinHeight(36 dp)
      tv.setShadowLayer(1.2f, 0, 0, Color.parseColor("#ff333333"))
    } >>= padding(left = 6 dp))

  override def notifyDataSetChanged() {
    c.foreach { c =>
      nicks = c.getUsers.?.fold(nicks)(_.asScala.toVector.map { u =>
        NickAndMode(usermode(u), u.getNick)
      }.filterNot {
        _.nick == "***" // znc playback user
      }.sorted)
      super.notifyDataSetChanged()
    }
  }

  override def getItemId(pos: Int) = pos
  override def getItem(pos: Int) = nicks(pos)
  override def getCount : Int = nicks.size
  override def getView(pos: Int, convertView: View, container: ViewGroup) = {
    val view = convertView.?.fold(layout.perform())(_.asInstanceOf[TextView])

    val n = getItem(pos)
    val colored = SpannedGenerator.textColor(MessageAdapter.nickColor(n.nick), n.nick)
    val s = if (Config.Ignores(n.nick))
      span(new StrikethroughSpan, colored)
    else colored

    val text = "%1%2" formatSpans(f"${n.mode.prefix}%1s", s)
    view.setText(text)
    view
  }

  UiBus += {
    case NickListChanged(ch) => if (ch == channel) notifyDataSetChanged()
    case IgnoreListChanged => notifyDataSetChanged()
  }
}
