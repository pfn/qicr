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

// practice doing this so as to prevent leaking the service instance
//     irrelevant in this app
class LocalIrcService extends Binder {
    var ref: WeakReference[IrcService] = _
    // can't use default constructor as it will save a strong-ref
    def this(s: IrcService) = {
        this()
        ref = new WeakReference(s)
    }
    def instance: IrcService = ref.get.get
}
object IrcService {
    val TAG = "IrcService"

    val EXTRA_PAGE    = "com.hanhuy.android.irc.extra.page"
    val EXTRA_SUBJECT = "com.hanhuy.android.irc.extra.subject"
    val EXTRA_SPLITTER = "::qicr-splitter-boundary::"

    // notification IDs
    val RUNNING_ID = 1
    val DISCON_ID  = 2
    val PRIVMSG_ID = 3
    val MENTION_ID = 4
}
class IrcService extends Service {
    var recreateActivity: Option[Int] = None
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
    lazy val config   = new Config(this)
    lazy val settings = {
        val s = new Settings(this)
        s.preferenceChangedListeners += { key =>
            if (key == getString(R.string.pref_message_lines)) {
                _servers.foreach { server =>
                    server.messages.maximumSize = s.getString(
                            R.string.pref_message_lines,
                            MessageAdapter.DEFAULT_MAXIMUM_SIZE.toString).toInt
                }
            }
        }
        s
    }
    val connections   = new HashMap[Server,IrcConnection]
    val _connections  = new HashMap[IrcConnection,Server]

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
        Log.i(TAG, "Unregistering connection: " + server, new StackTrace)
        connections.get(server).foreach(c => {
            connections -= server
            _connections -= c
        })
    }

    def addConnection(server: Server, connection: IrcConnection) {
        Log.i(TAG, "Registering connection: " + server + " => " + connection)
        connections  += ((server, connection))
        _connections += ((connection, server))
    }
    def bind(main: MainActivity) = {
        _activity = new WeakReference(main)
        if (!running) {
            _servers.foreach { s =>
                if (s.autoconnect) connect(s)
                s.messages.maximumSize = settings.getString(
                        R.string.pref_message_lines,
                        MessageAdapter.DEFAULT_MAXIMUM_SIZE.toString).toInt
            }
        }
    }

    // TODO identify if this is the cause of memory leaks
    // potentially putting stuff into the wrong activity's
    // fragment manager because it's removed onDestroy
    def unbind() {
        _activity = null
        // hopefully only UI uses these listeners
        serverAddedListeners.clear()
        serverChangedListeners.clear()
        serverRemovedListeners.clear()
        recreateActivity foreach { page =>
            recreateActivity = None
            val intent = new Intent(this, classOf[MainActivity])
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.putExtra(EXTRA_PAGE, page)
            startActivity(intent)
        }
    }

    def queueCreateActivity(page: Int) {
        recreateActivity = Some(page)
    }

    override def onBind(intent: Intent) : IBinder = {
        new LocalIrcService(this)
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
        cb.foreach { call =>
            (() => {
                IrcService.this.synchronized {
                    // TODO wait for quit to actually complete?
                    while (disconnectCount < count)
                        IrcService.this.wait()
                }
                runOnUI(call)
                stopSelf()
            }).execute()
        }
        connections.keys.foreach(disconnect(_))
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
            (() => {
                try {
                    val m = message match {
                    case Some(msg) => msg
                    case None => settings.getString(R.string.pref_quit_message,
                            R.string.pref_quit_message_default)
                    }
                    c.disconnect(m)
                } catch {
                    case e: Exception => {
                        Log.w(TAG, "Disconnect failed", e)
                        c.setConnected(false)
                        c.disconnect()
                    }
                }
                IrcService.this.synchronized {
                    disconnectCount += 1
                    IrcService.this.notify()
                }
            }).execute()
        })
        removeConnection(server) // gotta go after the foreach above
        server.state = Server.State.DISCONNECTED
        // handled by onDisconnect
        server.add(ServerInfo(getString(R.string.server_disconnected)))

        //if (disconnected && server.autoconnect) // not requested?  auto-connect
        //    connect(server)

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
        activity foreach { _.adapter.updateNickList(channel) }
    }
    def channelMessagesListener(c: ChannelLike, m: MessageLike) {
        if (!showing) return
        activity foreach { _.adapter.refreshTabTitle(c) }
    }

    def addQuery(c: IrcConnection, _nick: String, msg: String,
            sending: Boolean = false, action: Boolean = false,
            notice: Boolean = false) {
        val server = _connections.get(c) match {
        case Some(s) => s
        case None => return
        }

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
                activity foreach { _.adapter.addChannel(query) }

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
                activity foreach { _.adapter.addChannel(channel) }
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
        activity match {
        case Some(a) => a.runOnUiThread(f)
        case None => f()
        }
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

// TODO when cancelled, disconnect if still in process
// TODO implement connection timeouts
// use Object due to java<->scala varargs interop bug
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
        Log.i(TAG, "Connecting to server: " +
                (server.hostname, server.port, server.ssl))
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
            server.add(ServerInfo(
                    if (progress(0) == null) "" else progress(0).toString()))
    }
}

class StackTrace extends Exception
