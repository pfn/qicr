package com.hanhuy.android.irc.model

import android.graphics.Color
import com.hanhuy.android.irc.TR
import com.hanhuy.android.irc.TypedResource
import com.hanhuy.android.irc._
import com.hanhuy.android.common.{R => _, _}
import com.hanhuy.android.irc.model.BusEvent.NickListChanged
import SpannedGenerator._

import android.view.{Gravity, LayoutInflater, View, ViewGroup}
import android.widget.{AbsListView, BaseAdapter, TextView}

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
  import ViewGroup.LayoutParams._
  import Tweaks._
  import macroid._
  import macroid.FullDsl._
  val manager = IrcManager.start()
  var c: SircChannel = _
  manager.channels.get(channel).foreach(c = _)
  notifyDataSetChanged()

  implicit val ctx = ActivityContext(activity())
  implicit val app = AppContext(Application.context)

  val layout = w[TextView] <~ id(android.R.id.text1) <~
    bg(activity().getResources.getDrawable(R.drawable.selector_background)) <~
    lp[AbsListView](MATCH_PARENT, WRAP_CONTENT) <~ tweak { tv: TextView =>
      tv.setTextAppearance(activity(), android.R.attr.textAppearanceSmall)
      tv.setGravity(Gravity.CENTER_VERTICAL)
      tv.setMinHeight(36 dp)
      tv.setShadowLayer(1.2f, 0, 0, Color.parseColor("#ff333333"))
    } <~ padding(left = 6 dp)

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
    val view = if (c != null) c else getUi(layout)

    view.setText(getItem(pos))
    view
  }

  UiBus += {
    case NickListChanged(ch) => if (ch == channel) notifyDataSetChanged()
  }
}
