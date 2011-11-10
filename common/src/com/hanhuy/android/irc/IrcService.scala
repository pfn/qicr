package com.hanhuy.android.irc

import com.hanhuy.android.irc.model.Channel
import com.hanhuy.android.irc.model.Server
import com.hanhuy.android.irc.model.QueueAdapter

import scala.collection.mutable.HashMap
import scala.collection.mutable.HashSet
import scala.ref.WeakReference

import android.app.Service
import android.content.Intent
import android.os.{Binder, IBinder}
import android.os.AsyncTask
import android.app.Notification
import android.app.PendingIntent
import android.util.Log

import com.sorcix.sirc.IrcDebug
import com.sorcix.sirc.IrcServer
import com.sorcix.sirc.IrcConnection
import com.sorcix.sirc.NickNameException
import com.sorcix.sirc.{Channel => SircChannel}
import com.sorcix.sirc.{User => SircUser}

import AndroidConversions._
import IrcService._

object IrcService {
    val TAG = "IrcService"
    val RUNNING_ID = 1
}
class IrcService extends Service {
    var startId: Int = -1
    var _running = false
    var showing = false
    var config: Config = _
    val connections  = new HashMap[Server,IrcConnection]
    val _connections = new HashMap[IrcConnection,Server]

    val channels  = new HashMap[Channel,SircChannel]
    val _channels = new HashMap[SircChannel,Channel]

    // TODO find a way to automatically(?) purge the adapters
    // worst-case: leak memory on the string, but not the adapter
    val messages = new HashMap[String,QueueAdapter[_<:Object]]

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
        if (!running) {
            for (server <- _servers)
                if (server.autoconnect) connect(server)
        }
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

    def quit() {
        for (server <- { Seq.empty ++ connections.keys }) {
            disconnect(server)
        }
        stopSelf()
    }
    def disconnect(server: Server) {
        connections.get(server) match {
            case Some(c) => {
                new Thread(() =>
                    c.disconnect("qicr for android: faster and better")).start()
            }
            case None => Unit
        }
        server.state = Server.State.DISCONNECTED
        removeConnection(server)
        // handled by onDisconnect
        server.messages.add(getString(R.string.server_disconnected))
        if (connections.size == 0) {
            stopForeground(true)
            _running = false
            stopSelfResult(startId)
            startId = -1
        }
    }
    override def onStartCommand(i: Intent, flags: Int, id: Int): Int = {
        startId = id
        return Service.START_STICKY
    }
    def connect(server: Server) {
        if (server.state == Server.State.CONNECTING ||
                server.state == Server.State.CONNECTED) {
            return
        }
        server.state = Server.State.CONNECTING
        new ConnectTask(server, this).execute()
        if (!_running) {
            _running = true
            startService(new Intent(this, classOf[IrcService]))
            val notif = new Notification(R.drawable.ic_notify,
                    null, System.currentTimeMillis())
            val pending = PendingIntent.getActivity(this, 0,
                    new Intent(this, classOf[MainActivity]), 0)
            notif.setLatestEventInfo(getApplicationContext(),
                    "qicr", "running...", pending)
            startForeground(RUNNING_ID, notif)
        }
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

    def addChannel(c: IrcConnection, ch: SircChannel) {
        val server = _connections(c)
        var channel    = new Channel(server, ch.getName())
        channels.keys.find(_ == channel) match {
            case Some(_c) => {
                channel    = _c
                val _ch    = channels(channel)
                channels  -= channel
                _channels -= _ch
            }
            case None    => Unit
        }
        channels  += ((channel,ch))
        _channels += ((ch,channel))
        runOnUI(() => {
            if (showing)
                activity.adapter.addChannel(channel)
            channel.state = Channel.State.JOINED
        })
    }
    def removeChannel(ch: Channel) {
        val sircchannel = channels(ch)
        channels  -= ch
        _channels -= sircchannel
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
    IrcConnection.ABOUT = "qicr for android: faster and better!"
    IrcDebug.setEnabled(true)
    protected override def doInBackground(args: Object*) :
            Server.State.State = {
        val ircserver = new IrcServer(server.hostname, server.port,
                server.password, server.ssl)
        val connection = new IrcConnection
        connection.setServer(ircserver)
        connection.setUsername(server.username, server.realname)
        connection.setNick(server.nickname)

        var state = server.state
        //server.messages.context = service
        publishProgress(service.getString(R.string.server_connecting))
        service.addConnection(server, connection)
        val sslctx = SSLManager.configureSSL(service, server.messages)
        val listener = new IrcListeners(service)
        connection.setAdvancedListener(listener)
        connection.addServerListener(listener)
        connection.addModeListener(listener)
        connection.addMessageListener(listener)
        try {
            connection.connect(sslctx)
            state = Server.State.CONNECTED
        } catch {
            case e: NickNameException => {
                connection.setNick(server.altnick)
                publishProgress(service.getString(R.string.server_nick_retry))
                try {
                    connection.connect(sslctx)
                    state = Server.State.CONNECTED
                } catch {
                    case n: NickNameException => {
                        service.removeConnection(server)
                        publishProgress(service.getString(
                                R.string.server_nick_error))
                        state = Server.State.DISCONNECTED
                        connection.disconnect()
                        publishProgress(service.getString(
                                R.string.server_disconnected))
                    }
                }
            }
            case e: Exception => {
                service.removeConnection(server)
                publishProgress(e.getMessage())
                state = Server.State.DISCONNECTED
                connection.disconnect()
                publishProgress(service.getString(
                        R.string.server_disconnected))
            }
        }
        state
    }

    protected override def onPostExecute(state: Server.State.State) {
        server.state = state
    }

    protected override def onProgressUpdate(progress: Object*) {
        if (progress.length > 0)
            server.messages.add(progress(0).toString())
    }
}
