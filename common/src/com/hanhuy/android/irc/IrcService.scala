package com.hanhuy.android.irc

import android.app.Service
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;

class IrcService extends Service {
    var _running = false
    class LocalService extends Binder {
        def getService() : IrcService = {
            IrcService.this
        }
    }
    override def onBind(intent: Intent) : IBinder = {
        new LocalService()
    }
    override def onCreate() {
        super.onCreate()
    }
    override def onDestroy() {
        super.onDestroy()
    }

    def running = _running

    def connect() {
    }
}
