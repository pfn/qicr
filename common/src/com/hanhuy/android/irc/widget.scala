package com.hanhuy.android.irc

import android.appwidget.{AppWidgetManager, AppWidgetProvider}
import android.widget.{RemoteViews, RemoteViewsService}
import android.content.{Context, BroadcastReceiver, Intent}
import android.app.PendingIntent
import android.view.View
import com.hanhuy.android.irc.model.BusEvent._
import com.hanhuy.android.irc.model.{MessageAppender, MessageAdapter, ChannelLike, Server}
import android.widget.RemoteViewsService.RemoteViewsFactory

object Widgets extends EventBus.RefOwner {
  val ACTION_LAUNCH = "com.hanhuy.android.irc.action.LAUNCH"
  val ACTION_BACK = "com.hanhuy.android.irc.action.BACK"
  val ACTION_NEXT = "com.hanhuy.android.irc.action.NEXT"
  val ACTION_PREV = "com.hanhuy.android.irc.action.PREV"
  val ACTION_STATUS_CLICK = "com.hanhuy.android.irc.action.STATUS_CLICK"
  val ACTION_SUBJECT_PREFIX = "com.hanhuy.android.irc.action.SUBJECT-"

  val PID_STATUS_ITEM = 1
  val PID_OPEN_CHANNEL = 2
  val PID_GO_BACK = 3
  val PID_GO_NEXT = 4
  val PID_GO_PREV = 5

  private var settings: Settings = null

  ServiceBus += {
    case ChannelAdded(chan) =>
      UiBus.post {
        val awm = AppWidgetManager.getInstance(IrcService.instance.get)
        awm.notifyAppWidgetViewDataChanged(ids, R.id.status_list)
      }
    case ServerStateChanged(server, oldState) =>
      if (IrcService._running) {
        UiBus.post {
            val awm = AppWidgetManager.getInstance(IrcService.instance.get)
            awm.notifyAppWidgetViewDataChanged(ids, R.id.status_list)
        }
      }

    case ServiceRunning(running) =>
      val c = IrcService.instance.get
      val awm = AppWidgetManager.getInstance(c)
      if (!running) {
        setInitialView(c, awm, Right(ids))
      } else {
        UiBus.post {
          ids foreach { id =>
            setStatusView(IrcService.instance.get, id)
          }
        }
      }
  }

  def apply(c: Context) = {
    if (settings == null)
      settings = Settings(c)
    this
  }

  def ids: Array[Int] = {
    val _ids = settings.get(Settings.WIDGET_IDS)

    if (_ids.length == 0) Array.empty else _ids.split(",") map (_.toInt)
  }
  def ids_= (ids: Array[Int]) = settings.set(
    Settings.WIDGET_IDS, ids mkString ",")

  def setMessageView(c: Context, id: Int, m: MessageAppender) {
    val subject = m match {
      case c: ChannelLike =>
        c.server.name + IrcService.EXTRA_SPLITTER + c.name
      case s: Server =>
        s.name + IrcService.EXTRA_SPLITTER
    }
    setMessageView(c, id, subject)
  }
  def setMessageView(c: Context, id: Int, subject: String) {
    val views = new RemoteViews(c.getPackageName, R.layout.widget_content)
    views.setViewVisibility(R.id.back_button, View.VISIBLE)
    views.setViewVisibility(R.id.widget_control, View.VISIBLE)
    views.setViewVisibility(R.id.status_list, View.GONE)
    views.setViewVisibility(R.id.message_list, View.VISIBLE)
    views.setEmptyView(R.id.message_list, R.id.empty_list)
    views.setTextViewText(R.id.empty_list,
      c.getString(R.string.no_messages))

    val info = subject.split(IrcService.EXTRA_SPLITTER)
    val title = info match {
      case Array(serverName)             => serverName
      case Array(serverName,channelName) => serverName + "/" + channelName
    }

    val goBackIntent = new Intent(ACTION_BACK)
    goBackIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
    views.setOnClickPendingIntent(R.id.back_button, PendingIntent.getBroadcast(
      c, pid(id, PID_GO_BACK), goBackIntent, PendingIntent.FLAG_UPDATE_CURRENT))
    val launchIntent = new Intent(c, classOf[MainActivity])
    launchIntent.putExtra(IrcService.EXTRA_SUBJECT, subject)
    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    views.setOnClickPendingIntent(R.id.widget_app_icon,
      PendingIntent.getActivity(c, pid(id, PID_OPEN_CHANNEL),
        launchIntent, PendingIntent.FLAG_UPDATE_CURRENT))
    val nextIntent = new Intent(ACTION_NEXT)
    nextIntent.putExtra(IrcService.EXTRA_SUBJECT, subject)
    nextIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
    val prevIntent = new Intent(ACTION_PREV)
    prevIntent.putExtra(IrcService.EXTRA_SUBJECT, subject)
    prevIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
    views.setOnClickPendingIntent(R.id.go_next, PendingIntent.getBroadcast(
      c, pid(id, PID_GO_NEXT), nextIntent, PendingIntent.FLAG_UPDATE_CURRENT))
    views.setOnClickPendingIntent(R.id.go_prev, PendingIntent.getBroadcast(
      c, pid(id, PID_GO_PREV), prevIntent, PendingIntent.FLAG_UPDATE_CURRENT))

    val service = new Intent(c, classOf[WidgetMessageService])
    service.setAction(Widgets.ACTION_SUBJECT_PREFIX + subject)
    service.putExtra(IrcService.EXTRA_SUBJECT, subject)
    service.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)

    views.setRemoteAdapter(R.id.message_list, service)
    views.setTextViewText(R.id.title, title)

    val awm = AppWidgetManager.getInstance(c)
    views.setScrollPosition(R.id.message_list, 1000)
    awm.updateAppWidget(id, views)
  }

  def setStatusView(context: Context, id: Int) {
    val views = new RemoteViews(context.getPackageName, R.layout.widget_content)
    views.setViewVisibility(R.id.back_button, View.GONE)
    views.setViewVisibility(R.id.widget_control, View.GONE)
    views.setViewVisibility(R.id.status_list, View.VISIBLE)
    views.setViewVisibility(R.id.message_list, View.GONE)
    views.setEmptyView(R.id.status_list, R.id.empty_list)
    views.setTextViewText(R.id.empty_list,
      context.getString(R.string.server_disconnected))
    val adapterIntent = new Intent(context, classOf[WidgetStatusService])
    adapterIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
    views.setRemoteAdapter(R.id.status_list, adapterIntent)
    views.setTextViewText(R.id.title, context.getString(R.string.status))
    val launchIntent = new Intent(context, classOf[MainActivity])
    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    views.setOnClickPendingIntent(R.id.widget_app_icon,
      PendingIntent.getActivity(context, pid(id, PID_OPEN_CHANNEL),
        launchIntent, PendingIntent.FLAG_UPDATE_CURRENT))
    val intent = new Intent(Widgets.ACTION_STATUS_CLICK)
    intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
    views.setPendingIntentTemplate(R.id.status_list, PendingIntent.getBroadcast(
      context, pid(id, PID_STATUS_ITEM), intent,
      PendingIntent.FLAG_UPDATE_CURRENT))

    val awm = AppWidgetManager.getInstance(context)
    awm.updateAppWidget(id, views)
  }
  def setInitialView(c: Context, awm: AppWidgetManager,
                 ids: Either[Int,Array[Int]]) {
    val views = new RemoteViews(c.getPackageName, R.layout.widget_not_running)
    views.setOnClickPendingIntent(R.id.launch,
      PendingIntent.getBroadcast(c, R.id.launch, new Intent(ACTION_LAUNCH),
        PendingIntent.FLAG_UPDATE_CURRENT))
    ids match {
      case Left(x)  => awm.updateAppWidget(x, views)
      case Right(x) => awm.updateAppWidget(x, views)
    }
  }

  /** generate a pending intent ID */
  def pid(id: Int, id2: Int) = id * 100 + id2
}

class WidgetProvider extends AppWidgetProvider {

  override def onUpdate(c: Context, wm: AppWidgetManager, ids: Array[Int]) {
    Widgets(c).ids = (Widgets(c).ids.toSet ++ ids).toArray
    if (IrcService.instance.isEmpty)
      Widgets.setInitialView(c, wm, Right(ids))
    else
      Widgets(c).ids foreach { id =>
        Widgets.setStatusView(IrcService.instance.get, id) }
  }

  override def onDisabled(context: Context) {
    Widgets(context).ids = Array.empty[Int]
  }

  override def onEnabled(context: Context) {
    Widgets(context).ids = Array.empty[Int]
  }

  override def onReceive(c: Context, intent: Intent) {
    super.onReceive(c, intent)
    intent.getAction match {
      case Widgets.ACTION_LAUNCH =>
        val awm = AppWidgetManager.getInstance(c)
        val views = new RemoteViews(
          c.getPackageName, R.layout.widget_not_running)

        views.setViewVisibility(R.id.launch, View.INVISIBLE)
        views.setViewVisibility(R.id.progress, View.VISIBLE)
        views.setTextViewText(R.id.not_running, c.getString(R.string.launching))
        awm.updateAppWidget(Widgets(c).ids, views)
        val intent = new Intent(c, classOf[IrcService])
        intent.putExtra(IrcService.EXTRA_HEADLESS, true)
        c.startService(intent)
      case Widgets.ACTION_STATUS_CLICK =>
        Widgets.setMessageView(c, intent.getIntExtra(
          AppWidgetManager.EXTRA_APPWIDGET_ID, 0),
          intent.getStringExtra(IrcService.EXTRA_SUBJECT))
      case Widgets.ACTION_BACK =>
        Widgets.setStatusView(c,
          intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0))
      case Widgets.ACTION_NEXT =>
        nextPrevMessages(c, intent, 1)
      case Widgets.ACTION_PREV =>
        nextPrevMessages(c, intent, -1)
      case _ =>
    }
  }

  def nextPrevMessages(c: Context, intent: Intent, direction: Int) {
    val service = IrcService.instance.get
    def all = service.channels.keys.toList.sortWith(_<_).groupBy(_.server)
      .toList.sortWith(_._1<_._1) flatMap { case (k,v) => k :: v }

    val subject = intent.getStringExtra(IrcService.EXTRA_SUBJECT)
    val m = subject.split(IrcService.EXTRA_SPLITTER) match {
      case Array(serverName) =>
        service.getServers.find(_.name == serverName).get
      case Array(serverName,channelName) =>
        service.channels.keys.find(c =>
          c.server.name == serverName && c.name == channelName).get
    }
    val idx = all.indexOf(m)
    val tgt = (all.size + idx + direction) % all.size
    Widgets.setMessageView(c,
      intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0), all(tgt))
  }
}

class WidgetMessageService extends RemoteViewsService {

  def onGetViewFactory(intent: Intent): RemoteViewsFactory = {
    if (IrcService.instance.isEmpty) {
      Widgets.setInitialView(this, AppWidgetManager.getInstance(this),
        Left(intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0)))
      return null
    }
    val service = IrcService.instance.get
    val subject = intent.getStringExtra(IrcService.EXTRA_SUBJECT)
    val m = subject.split(IrcService.EXTRA_SPLITTER) match {
      case Array(serverName) =>
        service.getServers.find(_.name == serverName).get
      case Array(serverName,channelName) =>
        service.channels.keys.find(c =>
          c.server.name == serverName && c.name == channelName).get
    }
    new WidgetMessageViewsFactory(m)
  }
}

class WidgetStatusService extends RemoteViewsService {
  def onGetViewFactory(intent: Intent) = {
    IrcService.instance map { _ => new WidgetStatusViewsFactory } getOrElse {
      Widgets.setInitialView(this, AppWidgetManager.getInstance(this),
        Left(intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0)))
      null
    }
  }
}

class WidgetStatusViewsFactory extends BroadcastReceiver
with RemoteViewsService.RemoteViewsFactory {
  val service = IrcService.instance.get
  val serverView = new RemoteViews(
    service.getPackageName, R.layout.widget_server_item)
  val channelView = new RemoteViews(
    service.getPackageName, R.layout.widget_channel_item)

  def getViewAt(pos: Int) = {
    val intent = new Intent
    all(pos) match {
      case s: Server =>
        serverView.setTextViewText(android.R.id.text1, s.name)
        val intent = new Intent(Widgets.ACTION_STATUS_CLICK)
        intent.putExtra(IrcService.EXTRA_SUBJECT,
          s.name + IrcService.EXTRA_SPLITTER)
        serverView.setOnClickFillInIntent(android.R.id.text1, intent)
        serverView
      case c: ChannelLike =>
        channelView.setTextViewText(android.R.id.text1, c.name)
        channelView.setOnClickFillInIntent(android.R.id.text1, intent)
        intent.putExtra(IrcService.EXTRA_SUBJECT,
          c.server.name + IrcService.EXTRA_SPLITTER + c.name)
        channelView
    }
  }

  def all = service.channels.keys.toList.sortWith(_<_).groupBy(_.server)
    .toList.sortWith(_._1<_._1) flatMap { case (k,v) => k :: v }

  def getViewTypeCount = 2
  def getCount = all.size

  def getLoadingView = null
  def onDataSetChanged() {}
  def hasStableIds = false
  def getItemId(pos: Int) = 0L
  def onCreate() {}
  def onDestroy() {}
  def onReceive(c: Context, intent: Intent) {}
}

class WidgetMessageViewsFactory(m: MessageAppender) extends BroadcastReceiver
with RemoteViewsService.RemoteViewsFactory {
  val c = IrcService.instance.get
  val (channel,messages) = m match {
    case c: ChannelLike => (c,c.messages)
    case s: Server => (null,s.messages)
  }

  val views = new RemoteViews(c.getPackageName, R.layout.widget_message_item)

  def getViewAt(pos: Int) = {
    views.setTextViewText(android.R.id.text1,
      MessageAdapter.formatText(c,
        messages.filteredMessages.takeRight(32)(pos))(channel))
    views
  }

  def getCount = math.min(32, messages.getCount)
  def getViewTypeCount = 1
  def getItemId(pos: Int) = messages.getItemId(pos)

  def getLoadingView = null
  def onDataSetChanged() {}
  def hasStableIds = false
  def onDestroy() {}
  def onCreate() {}
  def onReceive(c: Context, intent: Intent) {}
}
