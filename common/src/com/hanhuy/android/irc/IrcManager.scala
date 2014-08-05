package com.hanhuy.android.irc

import javax.net.ssl.SSLContext

import android.text.TextUtils
import com.hanhuy.android.irc.model._

import android.app.NotificationManager
import android.content.{IntentFilter, Context, BroadcastReceiver, Intent}
import android.os.{Handler, HandlerThread}
import android.app.Notification
import android.app.PendingIntent
import android.widget.Toast
import android.support.v4.app.NotificationCompat

import com.sorcix.sirc.IrcDebug
import com.sorcix.sirc.IrcServer
import com.sorcix.sirc.IrcConnection
import com.sorcix.sirc.NickNameException
import com.sorcix.sirc.{Channel => SircChannel}

import com.hanhuy.android.common._
import AndroidConversions._
import RichLogger._
import IrcManager._
import android.net.ConnectivityManager
import com.hanhuy.android.irc.model.MessageLike.Privmsg
import com.hanhuy.android.irc.model.BusEvent.{IrcManagerStop, IrcManagerStart, ChannelStatusChanged}
import com.hanhuy.android.irc.model.MessageLike.CtcpAction
import com.hanhuy.android.irc.model.MessageLike.ServerInfo
import com.hanhuy.android.irc.model.MessageLike.Notice
import com.hanhuy.android.irc.model.BusEvent
import org.acra.ACRA

object IrcManager {
  implicit val TAG = LogcatTag("IrcManager")

  // notification IDs
  val RUNNING_ID = 1
  val DISCON_ID  = 2
  val PRIVMSG_ID = 3
  val MENTION_ID = 4

  val EXTRA_PAGE     = "com.hanhuy.android.irc.extra.page"
  val EXTRA_SUBJECT  = "com.hanhuy.android.irc.extra.subject"
  val EXTRA_SPLITTER = "::qicr-splitter-boundary::"

  val ACTION_NEXT_CHANNEL = "com.hanhuy.android.irc.action.NOTIF_NEXT"
  val ACTION_PREV_CHANNEL = "com.hanhuy.android.irc.action.NOTIF_PREV"
  val ACTION_CANCEL_MENTION = "com.hanhuy.android.irc.action.CANCEL_MENTION"
  val ACTION_QUICK_CHAT = "com.hanhuy.android.irc.action.QUICK_CHAT"

  var instance: Option[IrcManager] = None

  def start() = {
    instance getOrElse {
      val m = new IrcManager()
      m.start()
      m
    }
  }

  def stop[A](message: Option[String] = None, cb: Option[() => A] = None) {
    instance foreach { _.quit(message, cb) }
  }

  def running = instance exists (_.running)
}
class IrcManager extends EventBus.RefOwner {
  val version =
    Application.context.getPackageManager.getPackageInfo(
      Application.context.getPackageName, 0).versionName
  IrcConnection.ABOUT = getString(R.string.version, version)
  v("Creating service")
  Widgets(Application.context) // load widgets listeners
  val ircdebug = settings.get(Settings.IRC_DEBUG)
  if (ircdebug)
    IrcDebug.setLogStream(PrintStream)
  IrcDebug.setEnabled(ircdebug)
  val filter = new IntentFilter

  filter.addAction(ACTION_NEXT_CHANNEL)
  filter.addAction(ACTION_PREV_CHANNEL)
  filter.addAction(ACTION_CANCEL_MENTION)

  def getString(s: Int, args: Any*) = Application.context.getString(s,
    args map { _.asInstanceOf[Object] }: _*)

  private var _running = false
  private var _showing = false
  def showing = _showing
  val nm = Application.context.systemService[NotificationManager]

  ServiceBus += {
    case BusEvent.MainActivityStart => _showing = true
    case BusEvent.MainActivityStop  => _showing = false
      recreateActivity foreach { page =>
        recreateActivity = None
        val intent = new Intent(Application.context, classOf[MainActivity])
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.putExtra(EXTRA_PAGE, page)
        Application.context.startActivity(intent)
      }
    case BusEvent.PreferenceChanged(_, k) =>
      if (k == Settings.IRC_DEBUG) {
        val debug = settings.get(Settings.IRC_DEBUG)
        if (debug)
          IrcDebug.setLogStream(PrintStream)
        IrcDebug.setEnabled(debug)
      }
    case BusEvent.ChannelMessage(ch, msg) => lastChannel foreach { c =>
      if (!showing && c == ch) {
        val text = getString(R.string.notif_connected_servers, connections.size)

        val n = runningNotification(text)
        nm.notify(RUNNING_ID, n)
      }
    }
  }

  instance = Some(this)
  var recreateActivity: Option[Int] = None // int = page to flip to
  var messagesId = 0
  def connected = connections.size > 0

  lazy val handlerThread = {
    val t = new HandlerThread("IrcManagerHandler")
    t.start()
    t
  }
  // used to schedule an irc ping every 30 seconds
  lazy val handler = new Handler(handlerThread.getLooper)

  lazy val config   = new Config(Application.context)
  lazy val settings = Settings(Application.context)

  def connections  = mconnections
  def _connections = m_connections
  private var mconnections   = Map.empty[Server,IrcConnection]
  private var m_connections  = Map.empty[IrcConnection,Server]

  var lastChannel: Option[ChannelLike] = None

  def channels = mchannels
  def _channels = m_channels
  private var mchannels  = Map.empty[ChannelLike,SircChannel]
  private var m_channels = Map.empty[SircChannel,ChannelLike]
  private var queries    = Map.empty[(Server,String),Query]

  def remove(id: Int) {
    _messages -= id
    _chans -= id
    _servs -= id
  }

  def remove(c: ChannelLike) {
    c match {
      case ch: Channel =>
        mchannels -= ch
      case qu: Query =>
        mchannels -= qu
        queries -= ((qu.server, qu.name.toLowerCase))
    }
  }

  // TODO find a way to automatically(?) purge the adapters
  // worst-case: leak memory on the int, but not the adapter
  def messages = _messages
  def chans    = _chans
  def servs    = _servs
  private var _messages = Map.empty[Int,MessageAdapter]
  private var _chans    = Map.empty[Int,ChannelLike]
  private var _servs    = Map.empty[Int,Server]

  def add(id: Int, s: Server) {
    _servs += ((id, s))
  }
  def add(id: Int, ch: ChannelLike) {
    _chans += ((id, ch))
  }

  def add(idx: Int, adapter: MessageAdapter) {
    _messages += ((idx, adapter))
  }

  def newMessagesId(): Int = {
    messagesId += 1
    messagesId
  }

  def removeConnection(server: Server) {
    //Log.d(TAG, "Unregistering connection: " + server, new StackTrace)
    connections.get(server).foreach(c => {
      mconnections -= server
      m_connections -= c
    })
  }

  def addConnection(server: Server, connection: IrcConnection) {
    i("Registering connection: " + server + " => " + connection)
    mconnections += ((server, connection))
    m_connections += ((connection, server))

    if (!showing && _running) {
      nm.notify(RUNNING_ID, runningNotification(runningString))
    }
  }

  private def start() {
    if (!running) {
      Application.context.registerReceiver(receiver, filter)

      val connFilter = new IntentFilter
      connFilter.addAction(ConnectivityManager.CONNECTIVITY_ACTION)
      Application.context.registerReceiver(connReceiver, connFilter)

      Application.context.startService(
        new Intent(Application.context, classOf[LifecycleService]))

      v("Launching autoconnect servers")
      config.servers.foreach { s =>
        if (s.autoconnect) connect(s)
        s.messages.maximumSize = settings.get(Settings.MESSAGE_LINES).toInt
      }
      ServiceBus.send(IrcManagerStart)
    }
  }


  def queueCreateActivity(page: Int) = recreateActivity = Some(page)

  def running = _running

  var disconnectCount = 0
  def quit[A](message: Option[String] = None, cb: Option[() => A] = None) {
    instance = None
    Application.context.unregisterReceiver(receiver)
    Application.context.unregisterReceiver(connReceiver)
    nm.cancel(DISCON_ID)
    nm.cancel(MENTION_ID)
    nm.cancel(PRIVMSG_ID)
    ServiceBus.send(BusEvent.ExitApplication)

    val count = connections.keys.size
    _running = false
    disconnectCount = 0
    async {
      synchronized {
        // TODO wait for quit to actually complete?
        while (disconnectCount < count) {
          d("Waiting for disconnect: %d/%d" format (
            disconnectCount, count))
          wait()
        }
      }
      d("All disconnects completed, running callback: " + cb)
      cb.foreach { callback => UiBus.run { callback() } }
      nm.cancel(DISCON_ID)
      nm.cancel(MENTION_ID)
      nm.cancel(PRIVMSG_ID)
    }
    connections.keys.foreach(disconnect(_, message, false, true))
    handlerThread.quit()
    ServiceBus.send(IrcManagerStop)
  }

  def disconnect(server: Server, message: Option[String] = None,
                 disconnected: Boolean = false, quitting: Boolean = false) {
    connections.get(server).foreach { c =>
      async {
        try {
          val m = message getOrElse {
            settings.get(Settings.QUIT_MESSAGE)
          }
          c.disconnect(m)
        } catch {
          case e: Exception =>
            RichLogger.e("Disconnect failed", e)
            c.setConnected(false)
            c.disconnect()
        }
        synchronized {
          disconnectCount += 1
          d("disconnectCount: " + disconnectCount)
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
      // do not stop context if onDisconnect unless showing
      // do not stop context if quitting, quit() will do it
      if ((!disconnected || showing) && !quitting) {
        i("Stopping context because all connections closed")
        _running = false
      }
    }
  }

  def connect(server: Server) {
    v("Connecting server: %s", server)
    if (server.state == Server.State.CONNECTING ||
      server.state == Server.State.CONNECTED) {
      return
    }

    server.state = Server.State.CONNECTING
    async(connectServerTask(server))
    _running = true
  }

  def getServers = config.servers

  def addServer(server: Server) {
    config.addServer(server)
    UiBus.send(BusEvent.ServerAdded(server))
  }

  def startQuery(server: Server, nick: String) {
    val query = queries.getOrElse((server, nick.toLowerCase), {
      val q = Query(server, nick)
      q add MessageLike.Query
      queries += (((server, nick.toLowerCase),q))
      q
    })
    UiBus.send(BusEvent.StartQuery(query))
  }

  def addQuery(c: IrcConnection, _nick: String, msg: String,
               sending: Boolean = false, action: Boolean = false,
               notice: Boolean = false) {
    val server = _connections.getOrElse(c, { return })

    val query = queries.getOrElse((server, _nick.toLowerCase), {
      val q = Query(server, _nick)
      queries += (((server, _nick.toLowerCase),q))
      q
    })
    mchannels += ((query,null))

    val nick = if (sending) server.currentNick else _nick

    UiBus.run {
      val m = if (notice) Notice(nick, msg)
      else if (action) CtcpAction(nick, msg)
      else Privmsg(nick, msg)
      UiBus.send(BusEvent.PrivateMessage(query, m))
      ServiceBus.send(BusEvent.PrivateMessage(query, m))

      query.add(m)
      if (!showing)
        showNotification(PRIVMSG_ID, R.drawable.ic_notify_mono_star,
          m.toString, Some(query))
    }
  }

  def addChannel(c: IrcConnection, ch: SircChannel) {
    val server = _connections(c)
    var channel: ChannelLike = Channel(server, ch.getName)
    channels.keys.find(_ == channel) foreach { _c =>
      channel    = _c
      val _ch    = channels(channel)
      mchannels  -= channel
      m_channels -= _ch
    }
    mchannels  += ((channel,ch))
    m_channels += ((ch,channel))

    UiBus.run {
      val chan = channel.asInstanceOf[Channel]
      UiBus.send(BusEvent.ChannelAdded(chan))
      ServiceBus.send(BusEvent.ChannelAdded(chan))
      chan.state = Channel.State.JOINED
    }
  }

  def removeChannel(ch: Channel) {
    val sircchannel = channels(ch)
    mchannels  -= ch
    m_channels -= sircchannel
  }

  def updateServer(server: Server) = {
    config.updateServer(server)
    UiBus.send(BusEvent.ServerChanged(server))
  }

  def deleteServer(server: Server) {
    config.deleteServer(server)
    UiBus.send(BusEvent.ServerRemoved(server))
  }


  // TODO decouple
  def serverDisconnected(server: Server) {
    UiBus.run {
      disconnect(server, disconnected = true)
      if (_running) {
        if (connections.isEmpty) {
          lastChannel = None
          nm.notify(RUNNING_ID, runningNotification(
            getString(R.string.server_disconnected)))
        } else {
          nm.notify(RUNNING_ID, runningNotification(runningString))
        }
      }
    }
    if (!showing && _running)
      showNotification(DISCON_ID, R.drawable.ic_notify_mono_bang,
        getString(R.string.notif_server_disconnected, server.name))
  }

  // TODO decouple
  def addChannelMention(c: ChannelLike, m: MessageLike) {
    if (!showing)
      showNotification(MENTION_ID, R.drawable.ic_notify_mono_star,
        getString(R.string.notif_mention_template, c.name, m.toString), Some(c))
  }

  private def showNotification(id: Int, icon: Int, text: String,
                       channel: Option[ChannelLike] = None) {
    val intent = new Intent(Application.context, classOf[MainActivity])
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    channel foreach { c =>
      intent.putExtra(EXTRA_SUBJECT, Widgets.toString(c))
    }

    val pending = PendingIntent.getActivity(Application.context, id, intent,
      PendingIntent.FLAG_UPDATE_CURRENT)
    val notif = new NotificationCompat.Builder(Application.context)
      .setSmallIcon(icon)
      .setWhen(System.currentTimeMillis())
      .setContentIntent(pending)
      .setContentText(text)
      .setStyle(new NotificationCompat.BigTextStyle()
      .bigText(text).setBigContentTitle(getString(R.string.notif_title)))
      .setContentTitle(getString(R.string.notif_title))
      .build
    notif.flags |= Notification.FLAG_AUTO_CANCEL
    channel foreach { c =>
      val cancel = new Intent(ACTION_CANCEL_MENTION)
      cancel.putExtra(EXTRA_SUBJECT, Widgets.toString(c))
      notif.deleteIntent = PendingIntent.getBroadcast(Application.context,
        ACTION_CANCEL_MENTION.hashCode, cancel,
        PendingIntent.FLAG_UPDATE_CURRENT)
    }
    nm.notify(id, notif)
  }

  def ping(c: IrcConnection, server: Server) {
    val now = System.currentTimeMillis
    server.currentPing = Some(now)
    c.sendRaw("PING %d" format now)
  }

  val connReceiver = new BroadcastReceiver {
    def onReceive(c: Context, intent: Intent) {
      intent.getAction match {
        case ConnectivityManager.CONNECTIVITY_ACTION =>
          if (_running) {
            getServers foreach (disconnect(_, None, true))
            if (!intent.hasExtra(ConnectivityManager.EXTRA_NO_CONNECTIVITY) ||
              !intent.getBooleanExtra(
                ConnectivityManager.EXTRA_NO_CONNECTIVITY, false)) {
              getServers filter { _.autoconnect } foreach connect
              nm.cancel(DISCON_ID)
            }
          }
      }
    }
  }
  val receiver = new BroadcastReceiver {
    def onReceive(c: Context, intent: Intent) {
      val chans = channels.keys.toList.sortWith(_ < _)
      val idx = chans.size + (lastChannel.map { c =>
        chans.indexOf(c)
      } getOrElse 0)
      val tgt = if (chans.size == 0) 0 else intent.getAction match {
        case ACTION_NEXT_CHANNEL => (idx + 1) % chans.size
        case ACTION_PREV_CHANNEL => (idx - 1) % chans.size
        case ACTION_CANCEL_MENTION =>
          val subject = intent.getStringExtra(EXTRA_SUBJECT)
          Widgets.appenderForSubject(subject) match {
            case Some(c: ChannelLike) =>
              c.newMentions = false
              c.newMessages = false
              ServiceBus.send(ChannelStatusChanged(c))
            case _ =>
          }
          idx % chans.size // FIXME refactor the above
        case _ => idx
      }
      lastChannel = Option(chans(tgt))
      nm.notify(RUNNING_ID, runningNotification(runningString))
    }
  }

  def serverMessage(message: String, server: Server) {
    UiBus.run {
      server.add(ServerInfo(message))
    }
  }

  def connectServerTask(server: Server) {
    val ircserver = new IrcServer(server.hostname, server.port,
      server.password, server.ssl)
    val connection = new IrcConnection2
    i("Connecting to server: " +
      (server.hostname, server.port, server.ssl))
    connection.setServer(ircserver)
    connection.setUsername(server.username, server.realname)
    connection.setNick(server.nickname)

    var state = server.state
    serverMessage(getString(R.string.server_connecting), server)
    addConnection(server, connection)
    val sslctx = SSLManager.configureSSL(server)
    val listener = new IrcListeners(this)
    connection.setAdvancedListener(listener)
    connection.addServerListener(listener)
    connection.addModeListener(listener)
    connection.addMessageListener(listener)

    try {
      server.currentNick = server.nickname
      connection.connect(sslctx)
      state = Server.State.CONNECTED
    } catch {
      case e: NickNameException =>
        connection.setNick(server.altnick)
        server.currentNick = server.altnick
        serverMessage(getString(R.string.server_nick_retry), server)
        try {
          connection.connect(sslctx)
          state = Server.State.CONNECTED
        } catch {
          case e: Exception =>
            RichLogger.w("Failed to connect, nick exception?", e)
            serverMessage(getString(R.string.server_nick_error), server)
            state = Server.State.DISCONNECTED
            connection.disconnect()
            serverMessage(getString(R.string.server_disconnected), server)
            removeConnection(server)
        }
      case e: Exception =>
        state = Server.State.DISCONNECTED
        removeConnection(server)
        RichLogger.e("Unable to connect", e)
        serverMessage(e.getMessage, server)
        try {
          connection.disconnect()
        } catch {
          case ex: Exception =>
            RichLogger.e("Exception cleanup failed", ex)
            connection.setConnected(false)
            connection.disconnect()
            state = Server.State.DISCONNECTED
        }
        serverMessage(getString(R.string.server_disconnected), server)
    }

    if (state == Server.State.CONNECTED)
      ping(connection, server)

    UiBus.run { server.state = state }
  }
  def runningString = getString(R.string.notif_connected_servers,
    connections.size: java.lang.Integer)

  def runningNotification(text: CharSequence): Notification = {
    val intent = new Intent(Application.context, classOf[MainActivity])
    lastChannel foreach { c =>
      intent.putExtra(EXTRA_SUBJECT, c.server.name + EXTRA_SPLITTER + c.name)
    }
    val pending = PendingIntent.getActivity(Application.context, RUNNING_ID,
      intent, PendingIntent.FLAG_UPDATE_CURRENT)

    val builder = new NotificationCompat.Builder(Application.context)
      .setSmallIcon(R.drawable.ic_notify_mono)
      .setWhen(System.currentTimeMillis())
      .setContentIntent(pending)
      .setContentText(text)
      .setContentTitle(getString(R.string.notif_title))


    lastChannel map { c =>
      val title = c.name
      val msgs = if (c.messages.filteredMessages.size > 0) {
        TextUtils.concat(
          c.messages.filteredMessages.takeRight(5).map { m =>
            MessageAdapter.formatText(Application.context, m)(c)
          }.flatMap (m => Seq(m, "\n")).init:_*)
      } else {
        getString(R.string.no_messages)
      }

      val chatIntent = new Intent(Application.context, classOf[WidgetChatActivity])
      chatIntent.putExtra(IrcManager.EXTRA_SUBJECT, Widgets.toString(c))
      chatIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
        Intent.FLAG_ACTIVITY_MULTIPLE_TASK |
        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)

      val n = builder
        .setStyle(new NotificationCompat.BigTextStyle()
        .setBigContentTitle(title)
        .setSummaryText(text)
        .bigText(msgs))
        .setContentIntent(PendingIntent.getActivity(Application.context,
        ACTION_QUICK_CHAT.hashCode, chatIntent,
        PendingIntent.FLAG_UPDATE_CURRENT))
        .addAction(android.R.drawable.ic_media_previous, getString(R.string.prev),
          PendingIntent.getBroadcast(Application.context,
            ACTION_PREV_CHANNEL.hashCode,
            new Intent(ACTION_PREV_CHANNEL),
            PendingIntent.FLAG_UPDATE_CURRENT))
        .addAction(android.R.drawable.sym_action_chat,
          getString(R.string.open), pending)
        .addAction(android.R.drawable.ic_media_next, getString(R.string.next),
          PendingIntent.getBroadcast(Application.context,
            ACTION_NEXT_CHANNEL.hashCode,
            new Intent(ACTION_NEXT_CHANNEL),
            PendingIntent.FLAG_UPDATE_CURRENT))
        .build
      n.priority = Notification.PRIORITY_HIGH
      n
    } getOrElse builder.build
  }
}

class IrcConnection2 extends IrcConnection {
  override def connect(sslctx: SSLContext) = {
    super.connect(sslctx)
    val thread = getOutput: Thread
    thread.setUncaughtExceptionHandler(uncaughtExceptionHandler _)
  }
  // currently unused
  def uncaughtExceptionHandler(t: Thread, e: Throwable) {
    RichLogger.e("Uncaught exception in IRC thread: " + t, e)
    ACRA.getErrorReporter.handleSilentException(e)
    disconnect()
  }
}

object PrintStream
  extends java.io.PrintStream(new java.io.ByteArrayOutputStream) {
  implicit val TAG = LogcatTag("sIRC")
  override def println(line: String) = d(line)
  override def flush() = ()
}

class StackTrace extends Exception
