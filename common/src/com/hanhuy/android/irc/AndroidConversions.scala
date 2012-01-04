package com.hanhuy.android.irc

import android.app.Activity
import android.content.Intent
import android.content.Context
import android.content.BroadcastReceiver
import android.os.AsyncTask
import android.os.Build
import android.os.Looper
import android.view.View
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.AdapterView
import android.widget.TextView
import android.widget.CheckBox
import android.content.DialogInterface

object AndroidConversions {
    val icsAndNewer =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH
    val honeycombAndNewer =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB
    val gingerbreadAndNewer =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD

    if (gingerbreadAndNewer) GingerbreadSupport.init()
    implicit def toBroadcastReceiver[A](f: (Context, Intent) => A) =
            new BroadcastReceiver() {
        def onReceive(c: Context, i: Intent) = f(c, i)
    }

    implicit def toViewOnClickListener1[A](f: () => A) =
            new View.OnClickListener() { def onClick(v: View) = f() }
    implicit def toViewOnClickListener[A](f: View => A) =
            new View.OnClickListener() { def onClick(v: View) = f(v) }

    implicit def toDialogInterfaceOnClickListener[A](
            f: (DialogInterface, Int) => A) = {
        new DialogInterface.OnClickListener() {
            def onClick(d: DialogInterface, id: Int) = f(d, id)
        }
    }
    implicit def toDialogInterfaceOnClickListener1[A](f: () => A) = {
        new DialogInterface.OnClickListener() {
            def onClick(d: DialogInterface, id: Int) = f()
        }
    }

    implicit def toDialogInterfaceOnShowListener[A](f: () => A) = {
        new DialogInterface.OnShowListener() {
            def onShow(d: DialogInterface) = f()
        }
    }

    implicit def toAdapterViewOnItemClickListener[A](
            f: (AdapterView[_], View, Int, Long) => A) =
                    new AdapterView.OnItemClickListener() {
                def onItemClick(
                        av: AdapterView[_], v: View, pos: Int, id: Long) =
                            f(av, v, pos, id)
    }

    implicit def toViewOnKeyListener(f: (View, Int, KeyEvent) => Boolean) =
                    new View.OnKeyListener() {
                def onKey(v: View, key: Int, e: KeyEvent) = f(v, key, e)
    }
    implicit def toViewOnTouchListener(f: (View, MotionEvent) => Boolean) =
                    new View.OnTouchListener() {
                def onTouch(v: View, e: MotionEvent) = f(v, e)
    }

    implicit def toTextViewOnEditorAction(f: (View, Int, KeyEvent) => Boolean) =
            new TextView.OnEditorActionListener() {
        def onEditorAction(v: TextView, action: Int, e: KeyEvent) =
            f(v, action, e)
    }

    implicit def toRunnable[A](f: () => A) = new Runnable() {
        def run() = f()
    }

    def async(task: AsyncTask[_,_,_]) {
        if (honeycombAndNewer)
            task.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR)
        else
            task.execute()
    }
    // ok, param: => T can only be used if called directly, no implicits
    def async(f: => Unit): Unit = async(toAsyncTask(f))
    private def toAsyncTask(f: => Unit): AsyncTask[Object,Object,Unit] = {
        object Task extends AsyncTask[Object,Object,Unit] {
            override def doInBackground(args: Object*): Unit = f
        }
        Task
    }

    implicit def toUncaughtExceptionHandler[A](f: (Thread, Throwable) => A) =
            new Thread.UncaughtExceptionHandler {
        override def uncaughtException(t: Thread, e: Throwable) = f(t, e)
    }

    implicit def toString(c: CharSequence) =
            if (c == null) null else c.toString()
    implicit def toString(t: TextView): String = t.getText()
    implicit def toInt(t: TextView) = {
        val s: String = t.getText()
        if (s == null || s == "") -1
        else Integer.parseInt(s)
    }
    implicit def toBoolean(c: CheckBox) = c.isChecked()

    implicit def toRichView(v: View) = new RichView(v)
    implicit def toRichActivity(a: Activity) = new RichActivity(a)

    def isMainThread = Looper.getMainLooper.getThread == currentThread
}

class RichView(view: View) {
    def findView[T](id: Int): T = view.findViewById(id).asInstanceOf[T]
}
class RichActivity(activity: Activity) {
    def findView[T](id: Int): T = activity.findViewById(id).asInstanceOf[T]
}
