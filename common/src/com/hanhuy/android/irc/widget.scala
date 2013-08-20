package com.hanhuy.android.irc

import AndroidConversions._

import android.appwidget.{AppWidgetManager, AppWidgetProvider}
import android.widget.{Toast, RemoteViews, RemoteViewsService}
import android.content.{DialogInterface, Context, BroadcastReceiver, Intent}
import android.app.{AlertDialog, Activity, PendingIntent}
import android.view.View
import com.hanhuy.android.irc.model.BusEvent._
import com.hanhuy.android.irc.model.{MessageAppender, MessageAdapter, ChannelLike, Server}
import android.widget.RemoteViewsService.RemoteViewsFactory
import android.os.{Handler, Bundle}
import android.speech.RecognizerIntent

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
  val PID_CHAT = 6

  private var settings: Settings = null

  ServiceBus += {
    case ChannelStatusChanged(_)   => updateStatusWidget()
    case ServerMessage(server, _)  => updateMessageWidget(server)
    case ChannelMessage(chan, _)   => updateMessageWidget(chan)
    case PrivateMessage(chan, _)   =>
      updateMessageWidget(chan)
      updateStatusWidget()
    case ChannelAdded(_) =>
      updateStatusWidget()
    case ServerStateChanged(_, _) =>
      if (IrcService._running) updateStatusWidget()
    case ServiceRunning(running) =>
      val c = IrcService.instance.get
      val awm = AppWidgetManager.getInstance(c)
      if (!running) {
        setInitialView(c, awm, ids)
      } else {
        ids foreach { id =>
          setStatusView(IrcService.instance.get, id)
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
    setMessageView(c, id, toString(m))
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
    val chatIntent = new Intent(c, classOf[WidgetChatActivity])
    chatIntent.putExtra(IrcService.EXTRA_SUBJECT, subject)
    chatIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
      Intent.FLAG_ACTIVITY_MULTIPLE_TASK |
      Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
    views.setOnClickPendingIntent(R.id.widget_input, PendingIntent.getActivity(
      c, pid(id, PID_CHAT+10), chatIntent, PendingIntent.FLAG_UPDATE_CURRENT))

    val service = new Intent(c, classOf[WidgetMessageService])
    service.setAction(Widgets.ACTION_SUBJECT_PREFIX + subject.hashCode)
    service.putExtra(IrcService.EXTRA_SUBJECT, subject)
    service.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)

    views.setRemoteAdapter(R.id.message_list, service)
    views.setTextViewText(R.id.title, title)

    val awm = AppWidgetManager.getInstance(c)
    views.setScrollPosition(R.id.message_list, 1000)
    awm.updateAppWidget(id, views)
    assignMessageView(id, subject)
    awm.notifyAppWidgetViewDataChanged(id, R.id.message_list)
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
    awm.notifyAppWidgetViewDataChanged(id, R.id.status_list)
    unassignMessageView(id)
  }
  def setInitialView(c: Context, awm: AppWidgetManager, ids: Array[Int]) {
    val views = new RemoteViews(c.getPackageName, R.layout.widget_not_running)
    views.setOnClickPendingIntent(R.id.launch,
      PendingIntent.getBroadcast(c, R.id.launch, new Intent(ACTION_LAUNCH),
        PendingIntent.FLAG_UPDATE_CURRENT))
    awm.updateAppWidget(ids, views)
  }

  /** generate a pending intent ID */
  def pid(id: Int, id2: Int) = id * 100 + id2

  private var messageViews: Map[Int,String] = Map.empty

  def assignMessageView(id: Int, subj: String) = messageViews += id -> subj
  def unassignMessageView(id: Int) = messageViews -= id

  def toString(m: MessageAppender) = m match {
    case c: ChannelLike => c.server.name + IrcService.EXTRA_SPLITTER + c.name
    case s: Server      => s.name + IrcService.EXTRA_SPLITTER
  }

  def updateMessageWidget(m: MessageAppender) = {
    val subject = toString(m)
    messageViews.toList filter { case (id, subj) => subject == subj } map {
      case (id,_) =>
      val service = IrcService.instance.get
      val awm = AppWidgetManager.getInstance(service)
      awm.notifyAppWidgetViewDataChanged(id, R.id.message_list)
      id
    }
  }
  private val handler = new Handler
  private var lastStatusUpdate = 0l
  def updateStatusWidget() {
    handler.removeCallbacks(updateStatusRunnable)
    handler.postDelayed(updateStatusRunnable, 250)
  }

  private val updateStatusRunnable: Runnable = () => {
    val awm = AppWidgetManager.getInstance(IrcService.instance.get)
    ids filterNot messageViews.keySet foreach {
      awm.notifyAppWidgetViewDataChanged(_, R.id.status_list)
    }
  }

  def appenderForSubject(subject: String) = {
    val service = IrcService.instance.get
    if (subject == null) None else
      subject.split(IrcService.EXTRA_SPLITTER) match {
        case Array(serverName) =>
          service.getServers.find(_.name == serverName)
        case Array(serverName,channelName) =>
          service.channels.keys.find(c =>
            c.server.name == serverName && c.name == channelName)
        case null => None
      }
  }
}

class WidgetProvider extends AppWidgetProvider {

  override def onUpdate(c: Context, wm: AppWidgetManager, ids: Array[Int]) {
    Widgets(c).ids = (Widgets(c).ids.toSet ++ ids).toArray
    if (IrcService.instance.isEmpty)
      Widgets.setInitialView(c, wm, ids)
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

        if (IrcService.instance.isDefined) {
          Widgets.ids foreach {
            Widgets.setStatusView(c, _)
          }
        } else {

          val awm = AppWidgetManager.getInstance(c)
          val views = new RemoteViews(
            c.getPackageName, R.layout.widget_not_running)

          views.setViewVisibility(R.id.launch, View.INVISIBLE)
          views.setViewVisibility(R.id.progress, View.VISIBLE)
          views.setTextViewText(R.id.not_running,
            c.getString(R.string.launching))
          awm.updateAppWidget(Widgets(c).ids, views)
          val intent = new Intent(c, classOf[IrcService])
          intent.putExtra(IrcService.EXTRA_HEADLESS, true)
          c.startService(intent)
        }
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
    val m = Widgets.appenderForSubject(subject).get
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
        Widgets(this).ids)
      return null
    }
    val subject = intent.getStringExtra(IrcService.EXTRA_SUBJECT)
    Widgets.appenderForSubject(subject) map {
      new WidgetMessageViewsFactory(_)
    } getOrElse {
      Widgets.setInitialView(this, AppWidgetManager.getInstance(this),
        Widgets(this).ids)
      new WidgetEmptyViewsFactory
    }
  }
}

class WidgetStatusService extends RemoteViewsService {
  def onGetViewFactory(intent: Intent) = {
    IrcService.instance map { _ => new WidgetStatusViewsFactory } getOrElse {
      Widgets.setInitialView(this, AppWidgetManager.getInstance(this),
        Widgets(this).ids)
      null
    }
  }
}

class WidgetEmptyViewsFactory extends BroadcastReceiver
with RemoteViewsService.RemoteViewsFactory {
  def onDestroy() {}
  def onReceive(p1: Context, p2: Intent) {}
  def onDataSetChanged() {}
  def onCreate() {}
  def hasStableIds = false
  def getViewTypeCount = 0
  def getViewAt(p1: Int) = null
  def getLoadingView = null
  def getItemId(p1: Int) = 0L
  def getCount = 0
}

class WidgetStatusViewsFactory extends BroadcastReceiver
with RemoteViewsService.RemoteViewsFactory with EventBus.RefOwner {
  val service = IrcService.instance.get
  val serverView = new RemoteViews(
    service.getPackageName, R.layout.widget_server_item)
  val channelView = new RemoteViews(
    service.getPackageName, R.layout.widget_channel_item)

  ServiceBus += {
    case PrivateMessage(chan, _)   => _all = null
    case ChannelAdded(_)           => _all = null
    case ServerStateChanged(_, _)  => _all = null
    case ChannelStatusChanged(_)   => _all = null
  }

  def getViewAt(pos: Int) = {
    val intent = new Intent
    all(pos) match {
      case s: Server =>
        serverView.setTextViewText(android.R.id.text1, s.name)
        val intent = new Intent(Widgets.ACTION_STATUS_CLICK)
        intent.putExtra(IrcService.EXTRA_SUBJECT, Widgets.toString(s))
        serverView.setOnClickFillInIntent(android.R.id.text1, intent)
        serverView
      case c: ChannelLike =>
        channelView.setTextViewText(android.R.id.text1, c.name)
        val color = if (c.newMentions)
          0xffff0000 else if (c.newMessages) 0xff00afaf else 0xffbebebe
        channelView.setTextColor(android.R.id.text1, color)
        channelView.setOnClickFillInIntent(android.R.id.text1, intent)
        intent.putExtra(IrcService.EXTRA_SUBJECT, Widgets.toString(c))
        channelView
    }
  }

  private var _all: Seq[MessageAppender] = null
  def all = {
    if (_all == null) {
      _all = service.channels.keys.toList.sortWith(_<_).groupBy(_.server)
          .toList.sortWith(_._1<_._1) flatMap { case (k,v) => k :: v }
    }
    _all
  }

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

  private val MAX_LINES = 32
  val views = new RemoteViews(c.getPackageName, R.layout.widget_message_item)

  def getViewAt(pos: Int) = {
    views.setTextViewText(android.R.id.text1,
      MessageAdapter.formatText(c,
        messages.filteredMessages.takeRight(MAX_LINES)(pos))(channel))
    views
  }

  def getCount = math.min(MAX_LINES, messages.getCount)
  def getViewTypeCount = 1
  def getItemId(pos: Int) = messages.getItemId(pos)

  def getLoadingView = null
  def onDataSetChanged() {}
  def hasStableIds = false
  def onDestroy() {}
  def onCreate() {}
  def onReceive(c: Context, intent: Intent) {}
}

// TODO refactor and cleanup, so ugly, copy/paste from MainActivity
class WidgetChatActivity extends Activity {
  import collection.JavaConversions._
  val REQUEST_SPEECH_RECOGNITION = 1
  lazy val x: RichActivity = this
  import x._
  lazy val input = findView(TR.input)
  lazy val list = findView(TR.message_list)
  lazy val settings = IrcService.instance.get.settings
  private var proc: InputProcessor = _

  override def onCreate(savedInstanceState: Bundle) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.widget_chat)
    val m = Widgets.appenderForSubject(
      getIntent.getStringExtra(IrcService.EXTRA_SUBJECT)).get
    val (a,title) = m match {
      case s: Server      => (s.messages,s.name)
      case c: ChannelLike =>
        c.newMessages = false
        c.newMentions = false
        c.messages.channel = c
        (c.messages,c.name)
    }
    a.context = this
    list.setAdapter(a)
    findView(TR.title).setText(title)
    UiBus.post { list.setSelection(list.getAdapter.getCount - 1) }
    proc = new SimpleInputProcessor(this, m)
    input.addTextChangedListener(proc.TextListener)
    input.setOnEditorActionListener(proc.onEditorActionListener _)
    val complete = findView(TR.btn_nick_complete)
    complete onClick proc.nickComplete(input)
    val speechrec = findView(TR.btn_speech_rec)
    speechrec onClick {
      val intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
      intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
      intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
      try {
        startActivityForResult(intent, REQUEST_SPEECH_RECOGNITION)
      } catch {
        case e: Exception => {
          Toast.makeText(this, R.string.speech_unsupported,
            Toast.LENGTH_SHORT).show()
        }
      }
    }
    if (!settings.get(Settings.SHOW_NICK_COMPLETE))
      complete.setVisibility(View.GONE)

    if (!settings.get(Settings.SHOW_SPEECH_REC))
      speechrec.setVisibility(View.GONE)
  }

  // TODO refactor my ass with MainActivity's
  override def onActivityResult(req: Int, res: Int, i: Intent) {
    if (req != REQUEST_SPEECH_RECOGNITION ||
      res == Activity.RESULT_CANCELED) return
    if (res != Activity.RESULT_OK) {
      Toast.makeText(this, R.string.speech_failed, Toast.LENGTH_SHORT).show()
      return
    }
    val results = i.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)

    if (results.size == 0) {
      Toast.makeText(this, R.string.speech_failed, Toast.LENGTH_SHORT).show()
      return
    }

    val eol = settings.get(Settings.SPEECH_REC_EOL)
    val clearLine = settings.get(Settings.SPEECH_REC_CLEAR_LINE)

    results find { r => r == eol || r == clearLine } match {
      case Some(c) =>
        if (c == eol) {
          proc.handleLine(input.getText)
          InputProcessor.clear(input)
        } else if (c == clearLine) {
          InputProcessor.clear(input)
        }
      case None =>
        val builder = new AlertDialog.Builder(this)
        builder.setTitle(R.string.speech_select)
        builder.setItems(results.toArray(
          new Array[CharSequence](results.size)),
          (d: DialogInterface, which: Int) => {
            input.getText.append(results(which) + " ")

            val rec = results(which).toLowerCase
            if (rec.endsWith(" " + eol) || rec == eol) {
              val t = input.getText
              val line = t.substring(0, t.length() - eol.length() - 1)
              proc.handleLine(line)
              InputProcessor.clear(input)
            } else if (rec == clearLine) {
              InputProcessor.clear(input)
            }
          })
        builder.setNegativeButton(R.string.speech_cancel, null)
        builder.create().show()
    }
  }
  override def onSearchRequested() = {
    proc.nickComplete(input)
    true // prevent KEYCODE_SEARCH being sent to onKey
  }
}