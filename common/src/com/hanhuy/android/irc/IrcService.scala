package com.hanhuy.android.irc

import com.hanhuy.android.irc.config.Config
import com.hanhuy.android.irc.config.Server
import com.hanhuy.android.irc.config.WeakHashSet

import scala.collection.mutable.HashSet

import android.app.Service
import android.content.Intent
import android.os.{Binder, IBinder}

class IrcService extends Service {
    var _running = false
    var config: Config = _

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
        config = new Config(this)
    }
    override def onDestroy() {
        super.onDestroy()
        _servers.foreach(_.stateChangedListeners -= serverStateChanged)
    }

    def running = _running

    def connect() {
    }

    lazy val _servers = {
        val servers = config.getServers()
        for (server <- servers) {
            server.stateChangedListeners += serverStateChanged
        }
        servers
    }

    private def serverStateChanged(server: Server, state: Server.State.State) =
            serverChangedListeners.foreach(_(server))

    def getServers() = _servers
    def addServer(server: Server) {
        config.addServer(server)
        _servers += server
        serverAddedListeners.foreach(_(server))
    }
    def updateServer(server: Server) = {
        config.updateServer(server)
        serverChangedListeners.foreach(_(server))
    }
    def deleteServer(server: Server) {
        config.deleteServer(server)
        _servers -= server
        serverRemovedListeners.foreach(_(server))
    }

    val serverAddedListeners   = new HashSet[Server => Any]
    val serverRemovedListeners = new HashSet[Server => Any]
    val serverChangedListeners = new HashSet[Server => Any]
}
