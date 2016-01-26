package com.hanhuy.android

import android.app.Activity
import android.view.{View, ViewTreeObserver}
import android.view.ViewTreeObserver.OnPreDrawListener
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.AbsListView.OnScrollListener

/**
  * @author pfnguyen
  */
package object irc {
  implicit class AnyRefAsOptionExtension[T <: AnyRef](val any: T) extends AnyVal {
    def ? = Option(any)
  }

  def hideIME()(implicit c: Activity): Unit = {
    import iota.std.Contexts._
    systemService[InputMethodManager].hideSoftInputFromWindow(
      c.getWindow.getDecorView.getWindowToken, 0)
  }

  implicit class ListViewScrollOps(val list: AbsListView) extends AnyVal {
    def scrollStateChanged[A](f: (AbsListView, Int) => A) = list.setOnScrollListener(new OnScrollListener {
      override def onScrollStateChanged(view: AbsListView, scrollState: Int) = f(view, scrollState)

      override def onScroll(view: AbsListView, firstVisibleItem: Int, visibleItemCount: Int, totalItemCount: Int) = ()
    })

    def scrolled[A](f: (AbsListView, Int, Int, Int) => A) = list.setOnScrollListener(new OnScrollListener {
      override def onScrollStateChanged(view: AbsListView, scrollState: Int) = ()

      override def onScroll(view: AbsListView, firstVisibleItem: Int, visibleItemCount: Int, totalItemCount: Int) =
        f(view, firstVisibleItem, visibleItemCount, totalItemCount)
    })
  }

  implicit class ViewTreeObserverOps(val view: View) extends AnyVal {
    def onPreDraw(f: ViewTreeObserver.OnPreDrawListener => Boolean) =
      view.getViewTreeObserver.addOnPreDrawListener(new OnPreDrawListener {
        // pass this to allow listener to remove self
        override def onPreDraw() = f(this)
      })
  }
}
