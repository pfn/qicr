package com.hanhuy.android.irc

import android.content.Intent
import android.content.Context
import android.content.BroadcastReceiver
import android.view.View
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

}
