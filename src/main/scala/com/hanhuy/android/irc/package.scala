package com.hanhuy.android

import android.annotation.TargetApi
import android.app.Activity
import android.view.View.OnAttachStateChangeListener
import android.view.{View, ViewTreeObserver}
import android.view.ViewTreeObserver.OnPreDrawListener
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.AbsListView.OnScrollListener
import iota.IO

/**
  * @author pfnguyen
  */
package object irc {
  implicit class WeakReferenceEmptyOp(val wrt: ref.WeakReference.type) extends AnyVal {
    @inline def empty[T <: AnyRef] = scala.ref.WeakReference[T](null.asInstanceOf[T])
  }

  implicit class ViewIOLiftOp[T <: View](val view: T) extends AnyVal {
    @inline def ! = iota.IO[T](view)
  }

  @inline def hideIME()(implicit c: Activity): Unit = {
    import iota.std.Contexts._
    systemService[InputMethodManager].hideSoftInputFromWindow(
      c.getWindow.getDecorView.getWindowToken, 0)
  }

  implicit class ListViewScrollOps(val list: AbsListView) extends AnyVal {
    @inline
    def scrollStateChanged[A](f: (AbsListView, Int) => A) = list.setOnScrollListener(new OnScrollListener {
      override def onScrollStateChanged(view: AbsListView, scrollState: Int) = f(view, scrollState)

      override def onScroll(view: AbsListView, firstVisibleItem: Int, visibleItemCount: Int, totalItemCount: Int) = ()
    })

    @inline
    def scrolled[A](f: (AbsListView, Int, Int, Int) => A) = list.setOnScrollListener(new OnScrollListener {
      override def onScrollStateChanged(view: AbsListView, scrollState: Int) = ()

      override def onScroll(view: AbsListView, firstVisibleItem: Int, visibleItemCount: Int, totalItemCount: Int) =
        f(view, firstVisibleItem, visibleItemCount, totalItemCount)
    })
  }

  implicit class ViewTreeObserverOps(val view: View) extends AnyVal {
    @inline
    def onPreDraw(f: ViewTreeObserver.OnPreDrawListener => Boolean) =
      view.getViewTreeObserver.addOnPreDrawListener(new OnPreDrawListener {
        // pass this to allow listener to remove self
        override def onPreDraw() = f(this)
      })
  }

  implicit class ViewAttachStateChangeListener(val view: View) extends AnyVal {
    @TargetApi(12)
    @inline def onDetachedFromWindow[A](f: => A) =
      view.addOnAttachStateChangeListener(new OnAttachStateChangeListener {
        override def onViewDetachedFromWindow(v: View) = {
          view.removeOnAttachStateChangeListener(this)
          f
        }
        override def onViewAttachedToWindow(v: View) = ()
      })
  }
}
