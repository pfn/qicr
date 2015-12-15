package com.hanhuy.android.irc

import java.text.SimpleDateFormat
import java.util.Date

import android.content.Context
import android.support.v4.graphics.drawable.DrawableCompat
import android.view.{LayoutInflater, Gravity, ViewGroup, View}
import android.widget._
import com.hanhuy.android.common.UiBus
import com.hanhuy.android.irc.model.RingBuffer

import iota._

/**
  * @author pfnguyen
  */
object NotificationCenter extends TrayAdapter[NotificationMessage] {
  private val notifications = RingBuffer[NotificationMessage](256)

  override def itemId(position: Int) = notifications(position).hashCode

  override def size = notifications.size

  override def emptyItem = R.string.no_notifications

  type LP = RelativeLayout.LayoutParams
  import ViewGroup.LayoutParams._
  import RelativeLayout._
  def notificationLayout(implicit c: Context) = l[RelativeLayout](
    w[ImageView] >>= id(Id.notif_icon) >>=
      imageResource(R.drawable.ic_add_circle_white_24dp) >>=
      imageScale(ImageView.ScaleType.CENTER_INSIDE) >>=
      lpK(24.dp, 24.dp) { (p: LP) =>
        p.addRule(ALIGN_PARENT_LEFT, 1)
        p.addRule(BELOW, Id.channel_server)
        margins(all = 8.dp)(p)
    } >>= kestrel { iv =>
      DrawableCompat.setTint(iv.getDrawable, 0xffccaaff)
    },
    w[TextView] >>= id(Id.channel_server) >>=
      text("#channel / server") >>= lpK(WRAP_CONTENT, WRAP_CONTENT) { (p: LP) =>
      p.addRule(ALIGN_PARENT_LEFT, 1)
      p.addRule(RIGHT_OF, Id.notif_icon)
      p.addRule(ALIGN_PARENT_TOP, 1)
      margins(left = 8.dp)(p)
    },
    w[TextView] >>= id(Id.timestamp) >>= text("9:09pm") >>= lpK(WRAP_CONTENT, WRAP_CONTENT) { (p: LP) =>
      p.addRule(ALIGN_PARENT_RIGHT, 1)
      p.addRule(ALIGN_PARENT_TOP, 1)
      margins(right = 8.dp)(p)
    },
    w[TextView] >>= id(Id.text) >>= lpK(WRAP_CONTENT, WRAP_CONTENT) { (p: LP) =>
      p.addRule(ALIGN_TOP, Id.notif_icon)
      p.alignWithParent = true
      p.addRule(RIGHT_OF, Id.notif_icon)
      p.addRule(ALIGN_PARENT_RIGHT, 1)
    } >>= textGravity(Gravity.CENTER_VERTICAL)
  ) >>= padding(all = 8.dp)

  override def onGetView(position: Int, convertView: View, parent: ViewGroup) = {
    val view = if (convertView != null) {
      convertView.asInstanceOf[ViewGroup]
    } else {
      notificationLayout(parent.getContext).perform()
    }
    getItem(position) foreach { n =>
      view.findView(Id.text).setText(n.message)
      val sdf = new SimpleDateFormat("h:mma")
      view.findView(Id.timestamp).setText(sdf.format(n.ts).toLowerCase)
      val cs = view.findView(Id.channel_server)
      n match {
        case NotifyNotification(ts, server, sender, msg) =>
          cs.setText(server)
        case UserMessageNotification(ts, server, sender, msg, action) =>
          cs.setText(server)
        case ChannelMessageNotification(ts, server, chan, sender, msg, action) =>
          cs.setText(s"$chan / $server")
        case ServerNotification(icon, server, msg) =>
          cs.setText(server)
      }
    }
    view
  }

  override def getItem(position: Int): Option[NotificationMessage] =
    if (size == 0) None else Option(notifications(position))

  def hasImportantNotifications = notifications.exists(n => n.isNew && n.important)

  def +=(msg: NotificationMessage) = {
    notifications += msg
    UiBus.run(notifyDataSetChanged())
  }
}

sealed trait NotificationMessage {
  private[this] var newmessage = true
  def message: CharSequence
  def icon: Int
  val ts: Date
  def isNew = newmessage
  def important: Boolean
  def markRead() = newmessage = false
}

case class ServerNotification(icon: Int,
                              server: String,
                              message: CharSequence)
  extends NotificationMessage {
  val important = false
  val ts = new Date
}

case class NotifyNotification(ts: Date,
                              server: String,
                              sender: String,
                              message: CharSequence) extends NotificationMessage {
  val important = false
  val icon = 0
}

case class ChannelMessageNotification[A](ts: Date,
                                         server: String,
                                         channel: String,
                                         nick: String,
                                         message: CharSequence,
                                         action: () => A)
extends NotificationMessage {
  val important = true
  val icon = 0
}

case class UserMessageNotification[A](ts: Date,
                                      server: String,
                                      nick: String,
                                      message: CharSequence,
                                      action: () => A)
  extends NotificationMessage {
  val icon = 0
  val important = true
}

abstract class TrayAdapter[A] extends BaseAdapter {
  import iota._
  import ViewGroup.LayoutParams._
  final override def getView(position: Int, convertView: View, parent: ViewGroup) = {
    if (size == 0) {
      implicit val context = parent.getContext
      if (convertView == null) {
        // do nothing with onClick so that onItemClick doesn't execute
        c[AbsListView](w[TextView] >>= text(emptyItem) >>= textGravity(Gravity.CENTER) >>=
          hook0.onClick(IO(())) >>= lp(MATCH_PARENT, 128.dp)).perform()
      } else convertView
    } else {
      onGetView(position, convertView, parent)
    }
  }

  final override def getItemViewType(position: Int) = if (size == 0 && position == 0) 1 else 0
  final override def getViewTypeCount = 2
  final override def getCount = math.max(1, size)
  final override def getItemId(position: Int) = if (size == 0) -1 else itemId(position)
  final override def hasStableIds = false

  def itemId(position: Int): Int
  def onGetView(position: Int, convertView: View, parent: ViewGroup): View
  def size: Int
  def emptyItem: Int
}
