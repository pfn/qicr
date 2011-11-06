package com.hanhuy.android.irc

import com.hanhuy.android.irc.model.Server

import scala.collection.mutable.HashMap
import scala.collection.mutable.HashSet
import scala.ref.WeakReference

import android.util.Log
import android.app.Service
import android.content.Intent
import android.os.{Binder, IBinder}
import android.os.AsyncTask

import com.sorcix.sirc.IrcServer
import com.sorcix.sirc.IrcConnection
import com.sorcix.sirc.NickNameException

import AndroidConversions._
import IrcService._

object IrcService {
    val TAG = "IrcService"
}
class IrcService extends Service {
    var _running = false
    var config: Config = _
    private var connections  = new HashMap[Server,IrcConnection]
    private var _connections = new HashMap[IrcConnection,Server]

    var _activity: WeakReference[MainActivity] = _
    def activity = _activity.get match {
        case Some(a) => a
        case None    => null
    }

    def removeConnection(server: Server) {
        val c = connections(server)

        connections  -= server
        _connections -= c
    }

    def addConnection(server: Server, connection: IrcConnection) {
        connections  += ((server, connection))
        _connections += ((connection, server))
    }
    class LocalService extends Binder {
        def getService() : IrcService = {
            IrcService.this
        }
    }
    def bind(main: MainActivity) = _activity = new WeakReference(main)

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

    def disconnect(server: Server) {
        val c = connections(server)
        if (c != null)
            c.disconnect()
        server.state = Server.State.DISCONNECTED
        removeConnection(server)
    }
    def connect(server: Server) {
        if (server.state == Server.State.CONNECTING ||
                server.state == Server.State.CONNECTED) {
            return
        }
        server.state = Server.State.CONNECTING
        new ConnectTask(server, this).execute()
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
        server.stateChangedListeners += serverStateChanged
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

    def runOnUI[A](f: () => A) {
        val a = activity
        if (a != null)
            a.runOnUiThread(f)
    }

    val serverAddedListeners   = new HashSet[Server => Any]
    val serverRemovedListeners = new HashSet[Server => Any]
    val serverChangedListeners = new HashSet[Server => Any]
}

// Object due to java<->scala varargs interop bug
// https://issues.scala-lang.org/browse/SI-1459
class ConnectTask(server: Server, service: IrcService)
extends AsyncTask[Object, Object, Server.State.State] {
    protected override def doInBackground(args: Object*) :
            Server.State.State = {
        val ircserver = new IrcServer(server.hostname, server.port,
                server.password, server.ssl)
        val connection = new IrcConnection
        connection.setServer(ircserver)
        connection.setNick(server.nickname)
        var state = server.state
        //server.messages.context = service
        publishProgress(service.getString(R.string.server_connecting))
        service.addConnection(server, connection)
        try {
            connection.connect()
            state = Server.State.CONNECTED
            publishProgress(service.getString(R.string.server_connected))
        } catch {
            case e: NickNameException => {
                connection.setNick(server.altnick)
                publishProgress(service.getString(R.string.server_nick_retry))
                try {
                    connection.connect()
                    state = Server.State.CONNECTED
                    publishProgress(service.getString(
                            R.string.server_connected))
                } catch {
                    case n: NickNameException => {
                        service.removeConnection(server)
                        publishProgress(service.getString(
                                R.string.server_nick_error))
                        state = Server.State.DISCONNECTED
                        connection.disconnect()
                    }
                }
            }
            case e: Exception => {
                service.removeConnection(server)
                publishProgress(e.getMessage())
                state = Server.State.DISCONNECTED
                connection.disconnect()
            }
        }
        state
    }

    protected override def onPostExecute(state: Server.State.State) {
        server.state = state
    }

    protected override def onProgressUpdate(progress: Object*) {
        Log.i(TAG, "progress update: " + progress)
        if (progress.length > 0) {
            server.messages.add(progress(0).toString())
        }
    }
}
