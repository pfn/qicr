package com.hanhuy.android.irc

import com.hanhuy.android.irc.model.Query
import com.hanhuy.android.irc.model.Channel
import com.hanhuy.android.irc.model.ChannelLike
import com.hanhuy.android.irc.model.Server
import com.hanhuy.android.irc.model.MessageAdapter
import com.hanhuy.android.irc.model.BusEvent
import com.hanhuy.android.irc.model.MessageLike
import com.hanhuy.android.irc.model.MessageLike.ServerInfo
import com.hanhuy.android.irc.model.MessageLike.Privmsg
import com.hanhuy.android.irc.model.MessageLike.CtcpAction
import com.hanhuy.android.irc.model.MessageLike.Notice

import scala.collection.mutable.{HashMap, HashSet}
import scala.ref.WeakReference

import android.app.Service
import android.app.NotificationManager
import android.content.Intent
import android.content.Context
import android.os.{Binder, IBinder}
import android.os.AsyncTask
import android.os.{Handler, HandlerThread}
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
class LocalIrcBinder extends Binder {
    var ref: WeakReference[IrcService] = _
    // can't use default constructor as it will save a strong-ref
    def this(s: IrcService) = {
        this()
        ref = new WeakReference(s)
    }
    def service: IrcService = ref.get.get
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
class IrcService extends Service with EventBus.RefOwner {
    ServiceBus.clear // just in case
    val _richcontext: RichContext = this; import _richcontext._
    var recreateActivity: Option[Int] = None // int = page to flip to
    var startId: Int = -1
    var messagesId = 0
    def connected = connections.size > 0
    var _running = false

    var _showing = false
    def showing = _showing
    def showing_=(s: Boolean) = {
        // would be nicer to show notif on activity.isEmpty
        // but speech rec -> home will not trigger this?
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
    lazy val handlerThread = {
        val t = new HandlerThread("IrcServiceHandler")
        t.start();
        t
    }
    lazy val handler = new Handler(handlerThread.getLooper)

    lazy val config   = new Config(this)
    lazy val settings = new Settings(this)
    ServiceBus += {
    case BusEvent.PreferenceChanged(k) =>
        if (k == getString(R.string.pref_irc_debug)) {
            val debug = settings.getBoolean(R.string.pref_irc_debug)
            if (debug)
                IrcDebug.setLogStream(PrintStream)
            IrcDebug.setEnabled(debug)
        }
    }

    val connections   = new HashMap[Server,IrcConnection]
    val _connections  = new HashMap[IrcConnection,Server]

    val channels      = new HashMap[ChannelLike,SircChannel]
    val _channels     = new HashMap[SircChannel,ChannelLike]
    val queries       = new HashMap[(Server,String),Query]

    // TODO find a way to automatically(?) purge the adapters
    // worst-case: leak memory on the int, but not the adapter
    val messages = new HashMap[Int,MessageAdapter]
    val chans    = new HashMap[Int,ChannelLike]
    val servs    = new HashMap[Int,Server]

    def newMessagesId(): Int = {
        messagesId += 1
        messagesId
    }

    private var _activity: WeakReference[MainActivity] = _
    def activity = if (_activity == null) None else _activity.get

    def removeConnection(server: Server) {
        //Log.d(TAG, "Unregistering connection: " + server, new StackTrace)
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
    def bind(main: MainActivity) {
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

    def unbind() {
        _activity = null
        recreateActivity foreach { page =>
            recreateActivity = None
            val intent = new Intent(this, classOf[MainActivity])
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.putExtra(EXTRA_PAGE, page)
            startActivity(intent)
        }
    }

    def queueCreateActivity(page: Int) = recreateActivity = Some(page)

    override def onBind(intent: Intent) : IBinder = new LocalIrcBinder(this)

    def running = _running

    var disconnectCount = 0
    def quit[A](message: Option[String] = None, cb: Option[() => A] = None) {
        val count = connections.keys.size
        _running = false
        disconnectCount = 0
        async {
            synchronized {
                // TODO wait for quit to actually complete?
                while (disconnectCount < count) {
                    Log.d(TAG, format(
                            "Waiting for disconnect: %d/%d",
                            disconnectCount, count))
                    wait()
                }
            }
            Log.d(TAG, "All disconnects completed, running callback: " + cb)
            cb.foreach { callback => UiBus.run { callback() } }
            stopSelf()
            if (startId != -1) {
                stopForeground(true)
                stopSelfResult(startId)
                startId = -1
            }
            val nm = systemService[NotificationManager]
            nm.cancel(DISCON_ID)
            nm.cancel(MENTION_ID)
            nm.cancel(PRIVMSG_ID)
        }
        connections.keys.foreach(disconnect(_, message, false, true))
        handlerThread.quit()
    }
    override def onDestroy() {
        super.onDestroy()
        val nm = systemService[NotificationManager]
        nm.cancel(DISCON_ID)
        nm.cancel(MENTION_ID)
        nm.cancel(PRIVMSG_ID)
    }
    def disconnect(server: Server, message: Option[String] = None,
            disconnected: Boolean = false, quitting: Boolean = false) {
        connections.get(server).foreach { c =>
            async {
                try {
                    val m = message getOrElse {
                        settings.getString(R.string.pref_quit_message,
                                R.string.pref_quit_message_default)
                    }
                    c.disconnect(m)
                } catch {
                    case e: Exception => {
                        Log.e(TAG, "Disconnect failed", e)
                        c.setConnected(false)
                        c.disconnect()
                    }
                }
                synchronized {
                    disconnectCount += 1
                    Log.d(TAG, "disconnectCount: " + disconnectCount)
                    notify()
                }
            }
        }
        removeConnection(server) // gotta go after the foreach above
        server.state = Server.State.DISCONNECTED
        // handled by onDisconnect
        server.add(ServerInfo(getString(R.string.server_disconnected)))

        //if (disconnected && server.autoconnect) // not requested?  auto-connect
        //    connect(server)

        if (connections.size == 0) {
            // do not stop service if onDisconnect unless showing
            // do not stop service if quitting, quit() will do it
            if ((!disconnected || showing) && !quitting) {
                Log.i(TAG, "Stopping service because all connections closed")
                stopForeground(true)
                _running = false
                if (startId != -1) {
                    stopSelfResult(startId)
                    startId = -1
                }
            }
        }
    }
    override def onCreate() {
        super.onCreate()
        val ircdebug = settings.getBoolean(R.string.pref_irc_debug)
        if (ircdebug)
            IrcDebug.setLogStream(PrintStream)
        IrcDebug.setEnabled(ircdebug)
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
        async(new ConnectTask(server, this))
        if (!_running) {
            _running = true
            startService(new Intent(this, classOf[IrcService]))
        }
    }

    lazy val _servers = config.getServers()

    def getServers() = _servers
    def addServer(server: Server) {
        config.addServer(server)
        _servers += server
        UiBus.send(BusEvent.ServerAdded(server))
    }

    def addQuery(c: IrcConnection, _nick: String, msg: String,
            sending: Boolean = false, action: Boolean = false,
            notice: Boolean = false) {
        val server = _connections.get(c) getOrElse { return }

        val query = queries.get((server, _nick.toLowerCase())) getOrElse {
            val q = new Query(server, _nick)
            queries += (((server, _nick.toLowerCase()),q))
            q
        }
        channels += ((query,null))

        val nick = if (sending) server.currentNick else _nick

        UiBus.run {
            val m = if (notice) Notice(nick, msg)
            else if (action) CtcpAction(nick, msg)
            else Privmsg(nick, msg)
            UiBus.send(BusEvent.PrivateMessage(query, m))

            query.add(m)
            if (activity.isEmpty)
                showNotification(PRIVMSG_ID, R.drawable.ic_notify_mono_star,
                        m.toString(), server.name + EXTRA_SPLITTER + query.name)
        }
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

        UiBus.run {
            val chan = channel.asInstanceOf[Channel]
            UiBus.send(BusEvent.ChannelAdded(chan))
            chan.state = Channel.State.JOINED
        }
    }

    def removeChannel(ch: Channel) {
        val sircchannel = channels(ch)
        channels  -= ch
        _channels -= sircchannel
    }
    def updateServer(server: Server) = {
        config.updateServer(server)
        UiBus.send(BusEvent.ServerChanged(server))
    }
    def deleteServer(server: Server) {
        config.deleteServer(server)
        _servers -= server
        UiBus.send(BusEvent.ServerRemoved(server))
    }

    // currently unused
    def uncaughtExceptionHandler(t: Thread, e: Throwable) {
        Log.e(TAG, "Uncaught exception in thread: " + t, e)
        Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show()
        if (!t.getName().startsWith("sIRC-")) throw e
    }

    // TODO decouple
    def serverDisconnected(server: Server) {
        UiBus.run { disconnect(server, disconnected = true) }
        if (activity.isEmpty)
            showNotification(DISCON_ID, R.drawable.ic_notify_mono_bang,
                    getString(R.string.notif_server_disconnected, server.name),
                    "")
    }

    // TODO decouple
    def addChannelMention(c: ChannelLike, m: MessageLike) {
        if (activity.isEmpty)
            showNotification(MENTION_ID, R.drawable.ic_notify_mono_star,
                    getString(R.string.notif_mention_template,
                            c.name, m.toString()),
                    c.server.name + EXTRA_SPLITTER + c.name)
    }

    def showNotification(id: Int, res: Int, text: String, extra: String) {
        val nm = systemService[NotificationManager]
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

    def ping(c: IrcConnection, server: Server) {
        val now = System.currentTimeMillis
        server.currentPing = Some(now)
        c.sendRaw(format("PING %d", now))
    }
}

// TODO when cancelled, disconnect if still in process
// TODO implement connection timeouts
// use Object due to java<->scala varargs interop bug
// https://issues.scala-lang.org/browse/SI-1459
class ConnectTask(server: Server, service: IrcService)
extends AsyncTask[Object, Object, Server.State] {
    IrcConnection.ABOUT = service.getString(R.string.version, "0.1alpha")
    //IrcDebug.setEnabled(true)
    protected override def doInBackground(args: Object*): Server.State = {
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
                Log.e(TAG, "Unable to connect", e)
                publishProgress(e.getMessage())
                try {
                    connection.disconnect()
                } catch {
                    case ex: Exception => {
                        Log.e(TAG, "Exception cleanup failed", ex)
                        connection.setConnected(false)
                        connection.disconnect()
                        state = Server.State.DISCONNECTED
                    }
                }
                publishProgress(service.getString(
                        R.string.server_disconnected))
            }
            if (service.connections.size == 0) service._running = false
        }
        if (state == Server.State.CONNECTED)
            service.ping(connection, server)

        state
    }

    protected override def onPostExecute(state: Server.State) {
        server.state = state
    }

    protected override def onProgressUpdate(progress: Object*) {
        if (progress.length > 0)
            server.add(ServerInfo(
                    if (progress(0) == null) "" else progress(0).toString()))
    }
}

object PrintStream
extends java.io.PrintStream(new java.io.ByteArrayOutputStream) {
    val TAG = "sIRC"
    override def println(line: String) = Log.d(TAG, line)
    override def flush() = ()
}

class StackTrace extends Exception
