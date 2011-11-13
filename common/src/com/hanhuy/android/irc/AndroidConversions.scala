package com.hanhuy.android.irc

import android.content.Intent
import android.content.Context
import android.content.BroadcastReceiver
import android.view.View
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.AdapterView
import android.content.DialogInterface

object AndroidConversions {
    implicit def toBroadcastReceiver[A](f: (Context, Intent) => A) :
        BroadcastReceiver = new BroadcastReceiver() {
        def onReceive(c: Context, i: Intent) = f(c, i)
    }

    implicit def toViewOnClickListener[A](f: View => A) : View.OnClickListener =
            new View.OnClickListener() { def onClick(v: View) = f(v) }

    implicit def toDialogInterfaceOnClickListener[A](
            f: (DialogInterface, Int) => A) :
                DialogInterface.OnClickListener = {
        new DialogInterface.OnClickListener() {
            def onClick(d: DialogInterface, id: Int) = f(d, id)
        }
    }

    implicit def toAdapterViewOnItemClickListener[A](
            f: (AdapterView[_], View, Int, Long) => A) :
            AdapterView.OnItemClickListener =
                    new AdapterView.OnItemClickListener() {
                def onItemClick(
                        av: AdapterView[_], v: View, pos: Int, id: Long) =
                            f(av, v, pos, id)
    }

    implicit def toViewOnKeyListener(
            f: (View, Int, KeyEvent) => Boolean) : View.OnKeyListener =
                    new View.OnKeyListener() {
                def onKey(v: View, key: Int, e: KeyEvent) = f(v, key, e)
    }
    implicit def toViewOnTouchListener(
            f: (View, MotionEvent) => Boolean) : View.OnTouchListener =
                    new View.OnTouchListener() {
                def onTouch(v: View, e: MotionEvent) = f(v, e)
    }

    implicit def toRunnable[A](f: () => A) : Runnable = new Runnable() {
        def run() = f()
    }
}
