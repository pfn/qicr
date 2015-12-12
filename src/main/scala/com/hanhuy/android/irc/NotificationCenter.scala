package com.hanhuy.android.irc

import android.view.{LayoutInflater, Gravity, ViewGroup, View}
import android.widget.{AbsListView, TextView, BaseAdapter}
import com.hanhuy.android.irc.model.RingBuffer

/**
  * @author pfnguyen
  */
object NotificationCenter extends TrayAdapter[NotificationMessage[_]] {
  private val notifications = RingBuffer[NotificationMessage[_]](256)

  override def itemId(position: Int) = notifications(position).hashCode

  override def size = notifications.size

  override def emptyItem = R.string.no_notifications

  override def onGetView(position: Int, convertView: View, parent: ViewGroup) = {
    if (convertView != null) {
      val tv = convertView.asInstanceOf[TextView]
      tv.setText(notifications(position).message)
      tv
    } else {
      val view = LayoutInflater.from(parent.getContext).inflate(
        android.R.layout.simple_list_item_1, parent, false)
      view.asInstanceOf[TextView].setText(notifications(position).message)
      view
    }
  }

  override def getItem(position: Int): Option[NotificationMessage[_]] =
    if (size == 0) None else Option(notifications(position))
}

case class NotificationMessage[A](message: CharSequence, important: Boolean, action: Option[() => A] = None) {
  def isNew: Boolean = false
  val ts = System.currentTimeMillis
}

abstract class TrayAdapter[A] extends BaseAdapter {
  import iota._
  import ViewGroup.LayoutParams._
  final override def getView(position: Int, convertView: View, parent: ViewGroup) = {
    if (size == 0) {
      implicit val context = parent.getContext
      if (convertView == null) {
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
