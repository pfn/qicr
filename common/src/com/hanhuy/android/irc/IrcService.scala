package com.hanhuy.android.irc

import com.hanhuy.android.irc.model.Query
import com.hanhuy.android.irc.model.Channel
import com.hanhuy.android.irc.model.ChannelLike
import com.hanhuy.android.irc.model.Server
import com.hanhuy.android.irc.model.MessageAdapter
import com.hanhuy.android.irc.model.MessageLike
import com.hanhuy.android.irc.model.MessageLike.ServerInfo
import com.hanhuy.android.irc.model.MessageLike.Privmsg
import com.hanhuy.android.irc.model.MessageLike.CtcpAction
import com.hanhuy.android.irc.model.MessageLike.Notice

import scala.collection.mutable.HashMap
import scala.collection.mutable.HashSet
import scala.ref.WeakReference

import android.app.Service
import android.app.NotificationManager
import android.content.Intent
import android.content.Context
import android.os.{Binder, IBinder}
import android.os.AsyncTask
import android.app.Notification
import android.app.PendingIntent
import android.widget.Toast
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

    val EXTRA_SUBJECT = "com.hanhuy.android.irc.extra.subject"
    val EXTRA_SPLITTER = "::qicr-splitter-boundary::"

    // notification IDs
    val RUNNING_ID = 1
    val DISCON_ID  = 2
    val PRIVMSG_ID = 3
    val MENTION_ID = 4
}
class IrcService extends Service {
    var startId: Int = -1
    var messagesId = 0
    var _running = false
    var _showing = false
    def showing = _showing
    def showing_=(s: Boolean) = {
        if (!s) {
            if (_running) {
                val notif = new Notification(R.drawable.ic_notify_mono,
                        null, System.currentTimeMillis())
                val pending = PendingIntent.getActivity(this, 0,
                        new Intent(this, classOf[MainActivity]), 0)
                notif.setLatestEventInfo(getApplicationContext(),
                        getString(R.string.notif_title),
                        getString(R.string.notif_running), pending)
                startForeground(RUNNING_ID, notif)
            } else {
                stopForeground(true)
            }
        } else {
            stopForeground(true)
        }
        _showing = s
    }
    var config: Config = _
    val connections  = new HashMap[Server,IrcConnection]
    val _connections = new HashMap[IrcConnection,Server]

    val channels  = new HashMap[ChannelLike,SircChannel]
    val _channels = new HashMap[SircChannel,ChannelLike]
    val queries   = new HashMap[(Server,String),Query]

    val serverAddedListeners   = new HashSet[Server => Any]
    val serverRemovedListeners = new HashSet[Server => Any]
    val serverChangedListeners = new HashSet[Server => Any]

    // TODO find a way to automatically(?) purge the adapters
    // worst-case: leak memory on the int, but not the adapter
    val messages = new HashMap[Int,MessageAdapter]
    val chans    = new HashMap[Int,ChannelLike]

    def newMessagesId(): Int = {
        messagesId += 1
        messagesId
    }

    var _activity: WeakReference[MainActivity] = _
    def activity = if (_activity == null) None else _activity.get

    def removeConnection(server: Server) {
        connections.get(server).foreach(c => {
            connections -= server
            _connections -= c
        })
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
    def bind(main: MainActivity) = {
        _activity = new WeakReference(main)
        if (!running) {
            _servers.foreach(s => if (s.autoconnect) connect(s))
        }
    }

    def unbind() {
        _activity = null
        // hopefully only UI uses these listeners
        serverAddedListeners.clear()
        serverChangedListeners.clear()
        serverRemovedListeners.clear()
    }

    override def onBind(intent: Intent) : IBinder = {
        new LocalService()
    }

    override def onCreate() {
        super.onCreate()
        //Thread.setDefaultUncaughtExceptionHandler(uncaughtExceptionHandler _)
        config = new Config(this)
    }
    override def onDestroy() {
        _servers.foreach(_.stateChangedListeners -= serverStateChanged)
        super.onDestroy()
    }

    def running = _running
    var disconnectCount = 0

    def quit[A](message: Option[String] = None, cb: Option[() => A] = None) {
        val count = connections.keys.size
        disconnectCount = 0
        if (!cb.isEmpty) {
            new Thread(() => {
                synchronized { // TODO wait for quit to actually complete?
                    while (disconnectCount < count)
                        wait()
                }
                stopSelf()
                runOnUI(cb.get)
            }, "Quit disconnect waiter").start()
        }
        { Seq.empty ++ connections.keys }.foreach(disconnect(_))
        if (startId != -1) {
            stopForeground(true)
            _running = false
            stopSelfResult(startId)
            startId = -1
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE)
                .asInstanceOf[NotificationManager]
        nm.cancel(DISCON_ID)
        nm.cancel(MENTION_ID)
        nm.cancel(PRIVMSG_ID)
    }
    def disconnect(server: Server, message: Option[String] = None,
            disconnected: Boolean = false) {
        connections.get(server).foreach(c => {
            // TODO throw in an asynctask for some thread pooling maybe?
            new Thread(() => {
                try {
                    val m = message match {
                    case Some(msg) => msg
                    case None => getString(R.string.quit_msg_default)
                    }
                    c.disconnect(m)
                } catch {
                    case e: Exception => {
                        Log.w(TAG, "Disconnect failed", e)
                        c.setConnected(false)
                        c.disconnect()
                    }
                }
                synchronized {
                    disconnectCount += 1
                    notify()
                }
            }, server.name + ": Disconnect thread").start()
        })
        server.state = Server.State.DISCONNECTED
        removeConnection(server)
        // handled by onDisconnect
        server.add(ServerInfo(getString(R.string.server_disconnected)))

        if (disconnected && server.autoconnect) // not requested?  auto-connect
            connect(server)

        if (connections.size == 0) {
            // do not stop service if onDisconnect unless showing
            if (!disconnected || showing) {
                stopForeground(true)
                _running = false
                if (startId != -1) {
                    stopSelfResult(startId)
                    startId = -1
                }
            }
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
        }
    }

    lazy val _servers = {
        val servers = config.getServers()
        servers.foreach(_.stateChangedListeners += serverStateChanged)
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

    def notifyNickListChanged(c: ChannelLike) {
        if (!showing) return

        val channel = c.asInstanceOf[Channel]
        activity.get.adapter.updateNickList(channel)
    }
    def channelMessagesListener(c: ChannelLike, m: MessageLike) {
        if (!showing) return
        activity.get.adapter.refreshTabTitle(c)
    }

    def addQuery(c: IrcConnection, _nick: String, msg: String,
            sending: Boolean = false, action: Boolean = false,
            notice: Boolean = false) {
        val server = _connections(c)

        val query = queries.get((server, _nick.toLowerCase())) match {
            case Some(q) => q
            case None => {
                val q = new Query(server, _nick)
                queries += (((server, _nick.toLowerCase()),q))
                q.channelMessagesListeners += channelMessagesListener
                q
            }
        }
        channels += ((query,null))

        val nick = if (sending) server.currentNick else _nick

        runOnUI(() => {
            if (showing)
                activity.get.adapter.addChannel(query)

            val m = if (notice) Notice(nick, msg)
            else if (action) CtcpAction(nick, msg)
            else Privmsg(nick, msg)

            query.add(m)
            if (!showing)
                showNotification(PRIVMSG_ID, R.drawable.ic_notify_mono_star,
                        m.toString(), server.name + EXTRA_SPLITTER + query.name)
        })
    }

    def addChannel(c: IrcConnection, ch: SircChannel) {
        val server = _connections(c)
        var channel: ChannelLike = new Channel(server, ch.getName())
        channels.keys.find(_ == channel) foreach { _c =>
            channel    = _c
            val _ch    = channels(channel)
            channels  -= channel
            _channels -= _ch
        }
        channels  += ((channel,ch))
        _channels += ((ch,channel))
        channel.channelMessagesListeners += channelMessagesListener

        runOnUI(() => {
            if (showing)
                activity.get.adapter.addChannel(channel)
            channel match {
                case c: Channel => c.state = Channel.State.JOINED
                case _ => Unit
            }
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

    // run on ui thread if an activity is visible, otherwise directly
    def runOnUI[A](f: () => A) {
        // don't also check showing, !showing is possible, e.g. speech rec
        if (!activity.isEmpty)
            activity.get.runOnUiThread(f)
        else
            f() // no UI, just run it
    }

    def uncaughtExceptionHandler(t: Thread, e: Throwable) {
        Log.e(TAG, "Uncaught exception in thread: " + t, e)
        Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show()
        if (!t.getName().startsWith("sIRC-")) throw e
    }

    def serverDisconnected(server: Server) {
        runOnUI(() => disconnect(server, disconnected = true))
        if (!showing)
            showNotification(DISCON_ID, R.drawable.ic_notify_mono_bang,
                    getString(R.string.notif_server_disconnected, server.name),
                    "")
    }

    def addChannelMention(c: ChannelLike, m: MessageLike) {
        if (!showing)
            showNotification(MENTION_ID, R.drawable.ic_notify_mono_star,
                    getString(R.string.notif_mention_template,
                            c.name, m.toString()),
                    c.server.name + EXTRA_SPLITTER + c.name)
    }
    def showNotification(id: Int, res: Int, text: String, extra: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE)
                .asInstanceOf[NotificationManager]
        val notif = new Notification(res, text, System.currentTimeMillis())
        val intent = new Intent(this, classOf[MainActivity])
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.putExtra(EXTRA_SUBJECT, extra)
        val pending = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT)
        notif.setLatestEventInfo(getApplicationContext(),
                getString(R.string.notif_title), text, pending)
        notif.flags |= Notification.FLAG_AUTO_CANCEL
        nm.notify(id, notif)
    }
}

// Object due to java<->scala varargs interop bug
// https://issues.scala-lang.org/browse/SI-1459
class ConnectTask(server: Server, service: IrcService)
extends AsyncTask[Object, Object, Server.State.State] {
    IrcConnection.ABOUT = service.getString(R.string.version, "0.1alpha")
    //IrcDebug.setEnabled(true)
    protected override def doInBackground(args: Object*) :
            Server.State.State = {
        val ircserver = new IrcServer(server.hostname, server.port,
                server.password, server.ssl)
        val connection = new IrcConnection
        connection.setServer(ircserver)
        connection.setUsername(server.username, server.realname)
        connection.setNick(server.nickname)

        var state = server.state
        publishProgress(service.getString(R.string.server_connecting))
        service.addConnection(server, connection)
        val sslctx = SSLManager.configureSSL(service, server)
        val listener = new IrcListeners(service)
        connection.setAdvancedListener(listener)
        connection.addServerListener(listener)
        connection.addModeListener(listener)
        connection.addMessageListener(listener)
        try {
            server.currentNick = server.nickname
            connection.connect(sslctx)
            state = Server.State.CONNECTED
        } catch {
            case e: NickNameException => {
                connection.setNick(server.altnick)
                server.currentNick = server.altnick
                publishProgress(service.getString(R.string.server_nick_retry))
                try {
                    connection.connect(sslctx)
                    state = Server.State.CONNECTED
                } catch {
                    case n: NickNameException => {
                        publishProgress(service.getString(
                                R.string.server_nick_error))
                        state = Server.State.DISCONNECTED
                        connection.disconnect()
                        publishProgress(service.getString(
                                R.string.server_disconnected))
                        service.removeConnection(server)
                    }
                }
            }
            case e: Exception => {
                state = Server.State.DISCONNECTED
                service.removeConnection(server)
                Log.w(TAG, "Unable to connect", e)
                publishProgress(e.getMessage())
                try {
                    connection.disconnect()
                } catch {
                    case ex: Exception => {
                        Log.w(TAG, "Exception cleanup failed", ex)
                        connection.setConnected(false)
                        connection.disconnect()
                        state = Server.State.DISCONNECTED
                    }
                }
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
            server.add(ServerInfo(progress(0).toString()))
    }
}
