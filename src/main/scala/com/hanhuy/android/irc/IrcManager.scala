package com.hanhuy.android.irc

import java.lang.Thread.UncaughtExceptionHandler
import java.nio.charset.Charset
import java.util.Date
import javax.net.ssl.SSLContext

import android.annotation.TargetApi
import com.hanhuy.android.irc.model._

import android.app.RemoteInput
import android.content.{IntentFilter, Context, BroadcastReceiver, Intent}
import android.os.{PowerManager, Handler, HandlerThread}
import android.widget.Toast

import com.sorcix.sirc.{Channel => SircChannel, _}
import com.sorcix.sirc.cap.{CapNegotiator, CompoundNegotiator, ServerTimeNegotiator}

import com.hanhuy.android.common._
import Futures._
import IrcManager._
import android.net.ConnectivityManager
import com.hanhuy.android.irc.model.MessageLike.{Query => _, _}
import com.hanhuy.android.irc.model.BusEvent.{IrcManagerStop, IrcManagerStart, ChannelStatusChanged}
import com.hanhuy.android.irc.model.BusEvent
import org.acra.ACRA

import scala.concurrent.Future

object IrcManager {
  val log = Logcat("IrcManager")

  // notification IDs
  val DISCON_ID  = 2
  val PRIVMSG_ID = 3
  val MENTION_ID = 4

  val EXTRA_PAGE     = "com.hanhuy.android.irc.extra.page"
  val EXTRA_SPLITTER = "::qicr-splitter-boundary::"


  var instance: Option[IrcManager] = None

  def init() = {
    instance getOrElse new IrcManager
  }

  def stop[A](message: Option[String] = None, cb: Option[() => A] = None) {
    instance foreach { _.quit(message, cb) }
  }

  def running = instance exists (_.running)
}
class IrcManager extends EventBus.RefOwner {
  Notifications.cancelAll()
  val version =
    Application.context.getPackageManager.getPackageInfo(
      Application.context.getPackageName, 0).versionName
  IrcConnection.ABOUT = getString(R.string.version, version)
  log.v("Creating service")
  Widgets(Application.context) // load widgets listeners
  val ircdebug = Settings.get(Settings.IRC_DEBUG)
  if (ircdebug)
    IrcDebug.setLogStream(PrintStream)
  IrcDebug.setEnabled(ircdebug)
  val filter = new IntentFilter

  filter.addAction(Notifications.ACTION_SERVER_RECONNECT)
  filter.addAction(Notifications.ACTION_QUICK_SEND)
  filter.addAction(Notifications.ACTION_NEXT_CHANNEL)
  filter.addAction(Notifications.ACTION_PREV_CHANNEL)
  filter.addAction(Notifications.ACTION_CANCEL_MENTION)

  private var channelHolder = Map.empty[String,MessageAppender]
  def getChannel[A](id: String): Option[A] = {
    val c = channelHolder.get(id)
    if (c.isDefined) {
      channelHolder -= id
      c.asInstanceOf[Option[A]]
    } else None
  }

  def saveChannel(id: String, c: MessageAppender): Unit = {
    channelHolder += id -> c
  }

  def getString(s: Int, args: Any*) = Application.context.getString(s,
    args map { _.asInstanceOf[Object] }: _*)

  private var lastRunning = 0l
  private var _running = false
  private var _showing = false
  def showing = _showing

  ServiceBus += {
    case BusEvent.MainActivityStart => _showing = true
    case BusEvent.MainActivityStop  => _showing = false
    case BusEvent.MainActivityDestroy =>
      recreateActivity foreach { page =>
        recreateActivity = None
        val intent = new Intent(Application.context, classOf[MainActivity])
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.putExtra(EXTRA_PAGE, page)
        UiBus.post { Application.context.startActivity(intent) }
      }
    case BusEvent.PreferenceChanged(_, k) =>
      if (k == Settings.IRC_DEBUG) {
        val debug = Settings.get(Settings.IRC_DEBUG)
        if (debug)
          IrcDebug.setLogStream(PrintStream)
        IrcDebug.setEnabled(debug)
      }
    case BusEvent.ChannelMessage(ch, msg) =>
      val first = channels.keySet.toSeq sortWith { (a,b) =>
        ChannelLikeComparator.compare(a,b) < 0} headOption

      lastChannel orElse first foreach { c =>
      if (!showing && c == ch) {
        val text = getString(R.string.notif_connected_servers, connections.size)

        Notifications.running(text, firstChannel, lastChannel)
      }
    }
  }

  instance = Some(this)
  var recreateActivity: Option[Int] = None // int = page to flip to
  def connected = connections.nonEmpty

  def connections  = mconnections
  def _connections = m_connections
  private var mconnections   = Map.empty[Server,IrcConnection]
  private var m_connections  = Map.empty[IrcConnection,Server]

  lazy val handlerThread = {
    val t = new HandlerThread("IrcManagerHandler")
    t.start()
    t
  }
  {
    // schedule pings every 30 seconds
    val h = new Handler(handlerThread.getLooper)
    def pingLoop(): Unit = {
      import scala.concurrent.duration._
      h.postDelayed(() => pingLoop(), 30.seconds.toMillis)
      Config.servers.now.filter(_.state.now == Server.CONNECTED).filterNot(mconnections.keySet) foreach { s =>
        s += ServerInfo("Fixing state of orphaned server")
        val cn = m_connections.find { case (_, srv) => srv == s }
        disconnect(s, None, true).onComplete { _ => connect(s) }
        cn.foreach { case (conn, srv) =>
          conn.disconnect()
          m_connections -= conn
        }
      }
      mconnections.filterKeys(!Config.servers.now.filter(
        _.state.now == Server.CONNECTED).toSet(_)).keys foreach { s =>
        disconnect(s, None, true)
      }
      mconnections.foreach { case (server,c) =>
        if (server.state.now == Server.CONNECTED || server.state.now == Server.CONNECTING) {

          val now = System.currentTimeMillis
          val pingTime = server.currentPing getOrElse now
          val delta = now - pingTime
          if (delta == 0 || delta > 60.seconds.toMillis) {
            // re-send previous PING if it exceeds 1 minute
            ping(c, server, server.currentPing)
          }
          if (delta > 2.minutes.toMillis) {
            // automatically reconnect if ping gets over 120s
            server += ServerInfo(s"Disconnecting server due to ping timeout (${delta.millis.toSeconds}s)")
            disconnect(server, None, true).onComplete { case _ =>
              connect(server)
            }
          } else if (delta > server.currentLag.now)
            server.currentLag() = delta.toInt
        }
      }
    }
    h.post(() => pingLoop())
  }

  var lastChannel: Option[ChannelLike] = None
  def firstChannel = channels.keySet.toSeq sortWith { (a,b) =>
    ChannelLikeComparator.compare(a,b) < 0} headOption

  def channels = mchannels
  def _channels = m_channels
  private var mchannels  = Map.empty[ChannelLike,SircChannel]
  private var m_channels = Map.empty[SircChannel,ChannelLike]
  private var queries    = Map.empty[(Server,String),Query]

  def remove(id: Int) {
    _messages -= id
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
  def servs    = _servs
  private var _messages = Map.empty[Int,MessageAdapter]
  private var _servs    = Map.empty[Int,Server]

  def add(idx: Int, adapter: MessageAdapter) {
    _messages += ((idx, adapter))
  }

  def removeConnection(server: Server) = synchronized {
//    log.d("Unregistering connection: " + server, new StackTrace)
    connections.get(server).foreach(c => {
      mconnections -= server
      m_connections -= c
    })
  }

  def addConnection(server: Server, connection: IrcConnection) = synchronized {
    log.i("Registering connection: " + server + " => " + connection)
    mconnections += ((server, connection))
    m_connections += ((connection, server))

    if (!showing && _running) {
      Notifications.running(runningString, firstChannel, lastChannel)
    }
  }

  def start() {
    if (!running) {
      Application.context.registerReceiver(receiver, filter)
      Application.context.registerReceiver(dozeReceiver, PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)

      val connFilter = new IntentFilter
      connFilter.addAction(ConnectivityManager.CONNECTIVITY_ACTION)
      Application.context.registerReceiver(connReceiver, connFilter)

      Application.context.startService(
        new Intent(Application.context, classOf[LifecycleService]))

      log.v("Launching autoconnect servers")
      Config.servers.now.foreach { s =>
        if (s.autoconnect) connect(s)
      }
      ServiceBus.send(IrcManagerStart)
    }
  }


  def queueCreateActivity(page: Int) = recreateActivity = Some(page)

  def running = _running

  def quit[A](message: Option[String] = None, cb: Option[() => A] = None) {
    instance = None
    // ignore errors from unregisteringreceivers
    util.Try(Application.context.unregisterReceiver(receiver))
    util.Try(Application.context.unregisterReceiver(dozeReceiver))
    util.Try(Application.context.unregisterReceiver(connReceiver))
    Notifications.cancelAll()
    Notifications.exit()
    ServiceBus.send(BusEvent.ExitApplication)

    _running = false
    val fs = Future.sequence(connections.keys.map(disconnect(_, message, false, true)))
    fs.onComplete {
      case _ =>
        log.d("All disconnects completed, running callback: " + cb)
        cb.foreach { callback => UiBus.run { callback() } }
        Notifications.cancelAll()
        UiBus.post(System.exit(0))
    }
    handlerThread.quit()
    ServiceBus.send(IrcManagerStop)
  }

  def disconnect(server: Server, message: Option[String] = None,
                 disconnected: Boolean = false, quitting: Boolean = false) = {
    val f = connections.get(server).fold(Future.successful(())) { c =>
      Future {
        try {
          val m = message getOrElse {
            Settings.get(Settings.QUIT_MESSAGE)
          }
          c.disconnect(m)
        } catch {
          case e: Exception =>
            log.e("Disconnect failed", e)
            c.setConnected(false)
            c.disconnect()
        }
      }
    }
    removeConnection(server) // gotta go after the foreach above
    server.state() = Server.DISCONNECTED
    server.currentPing = None
    // handled by onDisconnect
    server += ServerInfo(getString(R.string.server_disconnected))

    //if (disconnected && server.autoconnect) // not requested?  auto-connect
    //    connect(server)

    if (connections.isEmpty) {
      // do not stop context if onDisconnect unless showing
      // do not stop context if quitting, quit() will do it
      if ((!disconnected || showing) && !quitting) {
        log.i("Stopping context because all connections closed")
        _running = false
        lastRunning = System.currentTimeMillis
      }
    }
    f
  }

  def connect(server: Server) {
    log.v("Connecting server: %s", server)
    server.currentPing = None // have to reset the last ping or else it'll get lost
    if (server.state.now == Server.CONNECTING || server.state.now == Server.CONNECTED) {
    } else {
      server.state() = Server.CONNECTING
      Future(connectServerTask(server))
    }
    _running = true
  }

  def getServers = Config.servers.now

  def addServer(server: Server.ServerData) {
    val s = Config.addServer(server)
    UiBus.send(BusEvent.ServerAdded(s))
  }

  def startQuery(server: Server, nick: String) {
    val query = queries.getOrElse((server, nick.toLowerCase), {
      val q = Query(server, nick)
      q += MessageLike.Query()
      queries += (((server, nick.toLowerCase),q))
      q
    })
    UiBus.send(BusEvent.StartQuery(query))
  }

  // TODO figure out how to get rid of null value for mchannels
  def addQuery(q: Query): Unit = {
    queries += (((q.server, q.name), q))
    mchannels += ((q,null))
  }
  def addQuery(c: IrcConnection, _nick: String, msg: String,
               sending: Boolean = false, action: Boolean = false,
               notice: Boolean = false, ts: Date = new Date) {
    _connections.get(c).orElse {
      c.disconnect()
      None
    }.foreach { server =>
      val nick = if (sending) server.currentNick else _nick

      if (!Config.Ignores(nick)) {
        val query = queries.getOrElse((server, _nick.toLowerCase), {
          val q = Query(server, _nick)
          queries += (((server, _nick.toLowerCase), q))
          q
        })
        // TODO figure out how to get rid of null value for mchannels
        mchannels += ((query, null))


        UiBus.run {
          val m = if (notice) Notice(nick, msg, ts)
          else if (action) CtcpAction(nick, msg, ts)
          else Privmsg(nick, msg, ts = ts)
          UiBus.send(BusEvent.PrivateMessage(query, m))
          ServiceBus.send(BusEvent.PrivateMessage(query, m))

          query += m
          val fmt = m match {
            case CtcpAction(_, _, _) => R.string.action_template
            case Notice(_, _, _) => R.string.notice_template
            case _ => R.string.message_template
          }
          val msg2 = Application.context.getString(fmt) formatSpans(MessageAdapter.colorNick(nick), msg)
          NotificationCenter += UserMessageNotification(m.ts, server.name, nick, msg2)
          if (!showing)
            Notifications.pm(query, m)
          else {
            markCurrentRead()
          }
        }
      }
    }
  }

  def addChannel(c: IrcConnection, ch: SircChannel) {
    val server = _connections(c)
    val chan: ChannelLike = Channel(server, ch.getName)
    val channel = channels.keys.find(_ == chan).map { _c =>
      val _ch    = channels(_c)
      mchannels  -= _c
      m_channels -= _ch
      _c
    }.getOrElse(chan)
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

  def updateServer(data: Server.ServerData) = {
    val server = Config.updateServer(data)
    UiBus.send(BusEvent.ServerChanged(server))
    mconnections.get(server) foreach { c =>
      mconnections += (server -> c)
      m_connections += (c -> server)
    }
  }

  def deleteServer(server: Server) {
    Config.deleteServer(server)
    UiBus.send(BusEvent.ServerRemoved(server))
  }


  // TODO decouple
  def serverDisconnected(server: Server) {
    UiBus.run {
      disconnect(server, disconnected = true)
      if (_running) {
        if (connections.isEmpty) {
          lastChannel = None
          Notifications.running(getString(R.string.server_disconnected), firstChannel, lastChannel)
        } else {
          Notifications.running(runningString, firstChannel, lastChannel)
        }
      }
    }
    if (!showing && _running)
      Notifications.disconnected(server)
  }

  // TODO decouple
  def addChannelMention(c: ChannelLike, m: MessageLike) {
    val fmt = m match {
      case CtcpAction(_, _, _) => R.string.action_template
      case Notice(_, _, _) => R.string.notice_template
      case _ => R.string.message_template
    }
    val (sender, message) = m match {
      case msg: ChatMessage => (msg.sender, msg.message)
    }
    val msg = Application.context.getString(fmt) formatSpans(MessageAdapter.colorNick(sender), message)
    c match {
      case c: model.Channel =>
        NotificationCenter += ChannelMessageNotification(m.ts, c.server.name, c.name, sender, msg)
    }
    if (!showing && c.isNew(m)) {
      Notifications.mention(c, m)
    } else if (!c.isNew(m)) {
      markCurrentRead()
    }
  }

  private def markCurrentRead(): Unit = {
    for {
      activity <- MainActivity.instance
      channel <- activity.adapter.currentTab.channel
    } {
      NotificationCenter.markRead(channel.name, channel.server.name)
      Notifications.markRead(channel)
    }
  }

  def ping(c: IrcConnection, server: Server, pingTime: Option[Long] = None) {
    val p = pingTime getOrElse System.currentTimeMillis
    server.currentPing = p.?
    try {
      c.sendRaw("PING " + p)
    } catch {
      case n: NullPointerException => // prevent crash, let ping fail
    }
  }

  val connReceiver = new BroadcastReceiver {

    // convoluted because this broadcast gets spammed  :-/
    // only take a real action on connectivity if net info has changed
    case class NetInfo(typ: String, name: String)
    val EmptyNetInfo = NetInfo("", "")

    // this broadcast is sticky...
    var hasRunOnce = false
    var lastConnectedInfo = EmptyNetInfo

    var disconnections: Future[_] = Future.successful(())

    def onReceive(c: Context, intent: Intent) {
      intent.getAction match {
        case ConnectivityManager.CONNECTIVITY_ACTION =>
          val cmm = c.systemService[ConnectivityManager]
          val info = cmm.getActiveNetworkInfo.?
          val inf = info.fold(EmptyNetInfo)(inf =>
            NetInfo(inf.getTypeName, inf.getExtraInfo))
          val connectivity = !intent.getBooleanExtra(
            ConnectivityManager.EXTRA_NO_CONNECTIVITY, false) &&
            info.exists(_.isConnected)
          if (hasRunOnce && (_running || (System.currentTimeMillis - lastRunning) < 15000)) {
            if (!connectivity || lastConnectedInfo != inf) {
              getServers filter (_.state.now == Server.CONNECTED) foreach (
                _ += ServerInfo("Disconnecting due to loss of connectivity"))
              val fs = Future.sequence(getServers map (disconnect(_, None, true)))
              // type inference error requires intermediate assignment
              disconnections = fs
            }
            if (connectivity && lastConnectedInfo != inf) {
              disconnections.onComplete { case _ =>
                getServers filter (_.autoconnect) foreach { s =>
                  s += ServerInfo("Connectivity restored, reconnecting...")
                  Notifications.cancel(Notifications.ServerDisconnected(s))
                  connect(s)
                }
                disconnections = Future.successful(())
              }
            }
            lastConnectedInfo = if (connectivity) inf else EmptyNetInfo
          }
          hasRunOnce = true
      }
    }
  }
  val receiver = new BroadcastReceiver {
    @TargetApi(24)
    def onReceive(c: Context, intent: Intent) {
      val chans = channels.keys.toList.sortWith(_ < _)
      val idx = chans.size + (lastChannel.map { c =>
        chans.indexOf(c)
      } getOrElse 0)
      val tgt = if (chans.isEmpty) 0 else intent.getAction match {
        case Notifications.ACTION_SERVER_RECONNECT =>
          val subject = intent.getStringExtra(Notifications.EXTRA_SUBJECT)
          getServers.filter(_.name == subject).foreach { s =>
              Notifications.cancel(Notifications.ServerDisconnected(s))
              if (s.state.now == Server.DISCONNECTED)
                connect(s)
            }
          idx % chans.size
        case Notifications.ACTION_QUICK_SEND =>
          val result = RemoteInput.getResultsFromIntent(intent).?
          for {
            r <- result
            s <- r.getString(Notifications.EXTRA_MESSAGE).?
          } {
            val proc = CommandProcessor(c)
            proc.channel = lastChannel
            proc.server = lastChannel.map(_.server)
            proc.executeLine(s)
          }

          idx % chans.size
        case Notifications.ACTION_NEXT_CHANNEL => (idx + 1) % chans.size
        case Notifications.ACTION_PREV_CHANNEL => (idx - 1) % chans.size
        case Notifications.ACTION_CANCEL_MENTION =>
          val subject = intent.getStringExtra(Notifications.EXTRA_SUBJECT)
          Widgets.appenderForSubject(subject) match {
            case Some(c: ChannelLike) =>
              c.newMentions = false
              c.newMessages = false
              Notifications.markRead(c)
              NotificationCenter.markRead(c.name, c.server.name)
              ServiceBus.send(ChannelStatusChanged(c))
            case _ =>
          }
          idx % chans.size // FIXME refactor the above
        case _ => idx % chans.size
      }
      lastChannel = if (chans.size > tgt) chans(tgt).? else None
      Notifications.running(runningString, firstChannel, lastChannel)
    }
  }

  def serverMessage(message: String, server: Server) {
    UiBus.run {
      server += ServerInfo(message)
    }
  }

  def connectServerTask(server: Server) {
    var state = server.state.now
    val ircserver = new IrcServer(server.hostname, server.port,
      if (server.sasl) null else server.password.orNull, server.ssl)
    val connection = IrcConnection2(server.name)
    val negotiator = new CompoundNegotiator(new ServerTimeNegotiator)
    if (server.sasl)
      negotiator.addListener(SaslNegotiator(server.username, server.password.orNull,
        result => {
          if (!result) {
            connection.disconnect()
            removeConnection(server)
            state = Server.DISCONNECTED
            UiBus.run {
              Toast.makeText(Application.context,
                s"SASL authentication for $server failed",
                Toast.LENGTH_SHORT).show()
            }
          }
      }))
    connection.setCapNegotiatorListener(negotiator)
    connection.setCharset(Charset.forName(Settings.get(Settings.CHARSET)))
    log.i("Connecting to server: " +
      (server.hostname, server.port, server.ssl))
    connection.setServer(ircserver)
    connection.setUsername(server.username, server.realname)
    connection.setNick(server.nickname)

    serverMessage(getString(R.string.server_connecting), server)
    addConnection(server, connection)
    val sslctx = SSLManager.configureSSL(server)
    val listener = new IrcListeners(this)
    connection.setAdvancedListener(listener)
    connection.addModeListener(listener)
    connection.addServerEventListener(listener)
    connection.addMessageEventListener(listener)

    try {
      server.currentNick = server.nickname
      if (server.state.now == Server.CONNECTING) {
        connection.connect(sslctx)
        // sasl authentication failure callback will force a disconnect
        if (state != Server.DISCONNECTED)
          state = Server.CONNECTED
      }
    } catch {
      case e: NickNameException =>
        connection.setNick(server.altnick)
        server.currentNick = server.altnick
        serverMessage(getString(R.string.server_nick_retry), server)
        try {
          if (server.state.now == Server.CONNECTING) {
            connection.connect(sslctx)
            state = Server.CONNECTED
          }
        } catch {
          case e: Exception =>
            log.w("Failed to connect, nick exception?", e)
            serverMessage(getString(R.string.server_nick_error), server)
            state = Server.DISCONNECTED
            connection.disconnect()
            serverMessage(getString(R.string.server_disconnected), server)
            removeConnection(server)
        }
      case e: Exception =>
        state = Server.DISCONNECTED
        removeConnection(server)
        log.e("Unable to connect", e)
        serverMessage(e.getMessage, server)
        try {
          connection.disconnect()
        } catch {
          case ex: Exception =>
            log.e("Exception cleanup failed", ex)
            connection.setConnected(false)
            connection.disconnect()
            state = Server.DISCONNECTED
        }
        serverMessage(getString(R.string.server_disconnected), server)
    }

    if (server.state.now == Server.DISCONNECTED)
      connection.disconnect()

    UiBus.run {
      server.state() = state
      if (state == Server.CONNECTED)
        ping(connection, server)
    }
  }
  def runningString = getString(R.string.notif_connected_servers,
    connections.size: java.lang.Integer)

  val dozeReceiver: BroadcastReceiver = (c: Context, i: Intent) => {
    @TargetApi(23)
    def goToBack(): Unit = {
      if (Application.context.systemService[PowerManager].isDeviceIdleMode)
        MainActivity.instance.foreach(_.moveTaskToBack(true))
    }
    i.getAction.? collect {
      case PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED => goToBack()
    }
  }
}

case class SaslNegotiator[A](user: String, pass: String, result: Boolean => A)
extends CapNegotiator.Listener {
  override def onNegotiate(capNegotiator: CapNegotiator, packet: IrcPacket) = {
    if (packet.isNumeric) {
      packet.getNumericCommand match {
        case 904 =>
          result(false)
          false // failed: no method, no auth
        case 900 =>
          result(true)
          false // success: logged in
        case 903 =>
          result(true)
          false // success: sasl successful
        case _   => true
      }
    } else {
      if ("AUTHENTICATE +" == packet.getRaw) {
        val buf = ("%s\u0000%s\u0000%s" format (user, user, pass)).getBytes(
          Settings.get(Settings.CHARSET))
        import android.util.Base64
        val auth = Base64.encodeToString(buf, 0, buf.length, 0).trim
        capNegotiator.send("AUTHENTICATE " + auth)
      }
      true
    }
  }

  override def onNegotiateList(capNegotiator: CapNegotiator,
                               features: Array[String]) = {
    if (features.contains("sasl")) {
      capNegotiator.request("sasl")
      true
    } else false
  }

  override def onNegotiateMissing(capNegotiator: CapNegotiator,
                                  feature: String) = "sasl" != feature

  override def onNegotiateFeature(capNegotiator: CapNegotiator,
                                  feature: String) = {
    if ("sasl" == feature) {
      capNegotiator.send("AUTHENTICATE PLAIN")
    }
    true
  }
}
case class IrcConnection2(name: String) extends IrcConnection {
  override def connect(sslctx: SSLContext) = {
    super.connect(sslctx)
    val thread = getOutput: Thread
    thread.setUncaughtExceptionHandler(new UncaughtExceptionHandler {
      override def uncaughtException(thread: Thread, throwable: Throwable) =
        uncaughtExceptionHandler _
    })
  }
  // currently unused
  def uncaughtExceptionHandler(t: Thread, e: Throwable) {
    log.e("Uncaught exception in IRC thread: " + t, e)
    ACRA.getErrorReporter.handleSilentException(e)
    disconnect()
  }

  override def sendRaw(line: String) = {
    if (out != null)
      super.sendRaw(line)
  }
}

object PrintStream
  extends java.io.PrintStream(new java.io.ByteArrayOutputStream) {
  val log = Logcat("sIRC")
  override def println(line: String) = log.d(line)
  override def flush() = ()
}

class StackTrace extends Exception
