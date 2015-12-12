package com.hanhuy.android.irc

import android.view.{Gravity, ViewGroup, View}
import android.widget.{AbsListView, TextView, BaseAdapter}

/**
  * @author pfnguyen
  */
object NotificationCenter {

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
          lp(MATCH_PARENT, 128.dp)).perform()
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
