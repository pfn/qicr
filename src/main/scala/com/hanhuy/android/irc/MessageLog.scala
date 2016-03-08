package com.hanhuy.android.irc

import java.io.Closeable

import java.util.Date

import android.app.{AlertDialog, Activity}
import android.content.{Intent, ContentValues, Context}
import android.database.Cursor
import android.database.sqlite.{SQLiteDatabase, SQLiteOpenHelper}
import android.graphics.{Typeface, Color}
import android.graphics.drawable.ColorDrawable
import android.os.{Bundle, Handler, HandlerThread}
import android.provider.BaseColumns
import MessageLog._
import android.support.v4.graphics.drawable.DrawableCompat
import android.support.v4.view.MenuItemCompat
import android.support.v4.widget.CursorAdapter
import android.support.v7.app.AppCompatActivity
import android.support.v7.widget.SearchView.{OnCloseListener, OnQueryTextListener}
import android.text.format.DateFormat
import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.view.ViewGroup.LayoutParams._
import android.view._
import android.widget.AbsListView.OnScrollListener
import android.widget.ExpandableListView.OnChildClickListener
import android.widget.{CursorAdapter => _,_}
import com.hanhuy.android.conversions._
import com.hanhuy.android.common._
import com.hanhuy.android.irc.Tweaks._
import com.hanhuy.android.irc.model.{Channel => ModelChannel, _}
import com.hanhuy.android.irc.model.MessageLike.{CtcpAction, Notice, Privmsg}
import iota._

import scala.concurrent.Future
import scala.util.Try

import Futures._

/**
 * @author pfnguyen
 */
object MessageLog {
  val logcat = Logcat("MessageLog")
  val DATABASE_NAME    = "logs"
  val DATABASE_VERSION = 3

  val TABLE_SERVERS  = "servers"
  val TABLE_CHANNELS = "channels"
  val TABLE_LOGS     = "logs"

  val FIELD_QUERY        = "query"
  val FIELD_NAME         = "name"
  val FIELD_SERVER       = "server_id"
  val FIELD_LAST_TS      = "last_ts"
  val TABLE_CHANNELS_DDL =
    s"""
       |CREATE TABLE $TABLE_CHANNELS (
       |  ${BaseColumns._ID} INTEGER PRIMARY KEY,
       |  $FIELD_NAME        TEXT NOT NULL,
       |  $FIELD_QUERY       BOOLEAN NOT NULL DEFAULT FALSE,
       |  $FIELD_SERVER      INTEGER NOT NULL,
       |  $FIELD_LAST_TS     DATETIME NOT NULL,
       |  CONSTRAINT unq_channel UNIQUE ($FIELD_SERVER, $FIELD_NAME),
       |  CONSTRAINT fk_ch_srv FOREIGN KEY($FIELD_SERVER)
       |   REFERENCES $TABLE_SERVERS(${BaseColumns._ID}) ON DELETE RESTRICT
       |);
       """.stripMargin

  val FIELD_CHANNEL   = "channel_id"
  val FIELD_ACTION    = "action"
  val FIELD_NOTICE    = "notice"
  val FIELD_MESSAGE   = "msg"
  val FIELD_SENDER    = "nick"
  val FIELD_TIMESTAMP = "ts"
  val TABLE_LOGS_DDL  =
    s"""
       |CREATE TABLE $TABLE_LOGS (
       |  ${BaseColumns._ID} INTEGER PRIMARY KEY,
       |  $FIELD_CHANNEL     INTEGER NOT NULL,
       |  $FIELD_MESSAGE     TEXT NOT NULL,
       |  $FIELD_SENDER      TEXT NOT NULL,
       |  $FIELD_ACTION      BOOLEAN NOT NULL DEFAULT FALSE,
       |  $FIELD_NOTICE      BOOLEAN NOT NULL DEFAULT FALSE,
       |  $FIELD_TIMESTAMP   DATETIME NOT NULL,
       |  CONSTRAINT fk_logs_ch FOREIGN KEY ($FIELD_CHANNEL)
       |   REFERENCES $TABLE_CHANNELS(${BaseColumns._ID}) ON DELETE CASCADE
       |);
     """.stripMargin

  val TABLE_LOGS_NICK_INDEX_DDL =
    s"""
       |CREATE INDEX idx_logs_nick ON
       | $TABLE_LOGS($FIELD_CHANNEL,$FIELD_SENDER);
     """.stripMargin

  val TABLE_LOGS_NICK_INDEX2_DDL =
    s"""
       |CREATE INDEX idx_logs_nick2 ON
       | $TABLE_LOGS($FIELD_CHANNEL,$FIELD_SENDER collate nocase);
     """.stripMargin

  val TABLE_LOGS_CHANNEL_INDEX_DDL =
    s"""
       |CREATE INDEX idx_logs_chan ON $TABLE_LOGS($FIELD_CHANNEL);
     """.stripMargin

  val TABLE_SERVERS_DDL =
    s"""
       |CREATE TABLE $TABLE_SERVERS (
       |  ${BaseColumns._ID} INTEGER PRIMARY KEY,
       |  $FIELD_NAME        TEXT NOT NULL
       |);
     """.stripMargin

  val UPGRADES: Map[Int,Seq[String]] = Map(
    2 -> Seq(
      "PRAGMA foreign_keys=OFF",
      s"""
         |CREATE TABLE new_$TABLE_SERVERS (
         |  ${BaseColumns._ID} INTEGER PRIMARY KEY,
         |  $FIELD_NAME        TEXT NOT NULL
         |);
       """.stripMargin,
      s"INSERT INTO new_$TABLE_SERVERS SELECT * FROM $TABLE_SERVERS",
      s"DROP TABLE $TABLE_SERVERS",
      s"ALTER TABLE new_$TABLE_SERVERS RENAME TO $TABLE_SERVERS"
    ),
    3 -> Seq(TABLE_LOGS_NICK_INDEX2_DDL)
  )

  case class Network(name: String, id: Long = -1) {
    def values: ContentValues = {
      val values = new ContentValues
      values.put(BaseColumns._ID, id: java.lang.Long)
      values.put(FIELD_NAME, name.toLowerCase)
      values
    }
  }
  case class Channel(network: Network, id: Long, name: String,
                     lastTs: Long,
                     query: Boolean = false) {
    def values: ContentValues = {
      val values = new ContentValues
      values.put(FIELD_LAST_TS, lastTs: java.lang.Long)
      values.put(FIELD_SERVER, network.id: java.lang.Long)
      values.put(FIELD_NAME, name.toLowerCase)
      values.put(FIELD_QUERY, query)
      values
    }
  }
  case class Entry(channel: Channel, sender: String, ts: Long, message: String,
                   action: Boolean = false, notice: Boolean = false) {
    def values: ContentValues = {
      val values = new ContentValues
      values.put(FIELD_CHANNEL, channel.id: java.lang.Long)
      values.put(FIELD_MESSAGE, message)
      values.put(FIELD_SENDER, sender)
      values.put(FIELD_TIMESTAMP, ts: java.lang.Long)
      values.put(FIELD_ACTION, action)
      values.put(FIELD_NOTICE, notice)
      values
    }
  }

  type LogAdapter = CursorAdapter with Closeable

  /**
   * the returned adapter must be closed when done
   */
  def get(c: Activity, serverId: Long, channel: String): LogAdapter =
    instance.get(c, serverId, channel)

  def get(c: Activity, serverId: Long, channel: String, query: String): LogAdapter =
    instance.get(c, serverId, channel, query)

  def log(m: MessageLike, c: ChannelLike) = if (Settings.get(Settings.IRC_LOGGING))
    handler.post { () => instance.log(m, c) }

  lazy val handlerThread = {
    val t = new HandlerThread("MessageLogHandler")
    t.start()
    t
  }
  lazy val handler = new Handler(handlerThread.getLooper)
  private val instance = new MessageLog(Application.context)

  def networks = instance.networks
  def channels = instance.channels

  def delete(nid: Long, channel: String) = instance.delete(nid, channel)
  def deleteAll() = instance.deleteAll()
}

class MessageLog private(context: Context)
  extends SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

  def networks = _networks getOrElse initialNetworks

  def networks_=(n: Map[Long,Network]) = _networks = Some(n)
  def channels_=(c: Map[(Long,String),Channel]) = _channels = Some(c)

 def channels = _channels getOrElse initialChannels

  private var _networks = Option.empty[Map[Long,Network]]
  private var _channels = Option.empty[Map[(Long,String),Channel]]

  lazy val initialNetworks = {
    val db = getReadableDatabase
    val c = db.query(TABLE_SERVERS, null, null, null, null, null, null)
    val idcol   = c.getColumnIndexOrThrow(BaseColumns._ID)
    val namecol = c.getColumnIndexOrThrow(FIELD_NAME)
    val networks = Iterator.continually(c.moveToNext()).takeWhile (x => x).map {
      _ =>
        val id = c.getLong(idcol)
        id -> Network(c.getString(namecol), id)
    }.toMap

    c.close()
    close(db)
    networks
  }

  lazy val initialChannels = {
    val db = getReadableDatabase
    val c = db.query(TABLE_CHANNELS, null, null, null, null, null, null)
    val idcol     = c.getColumnIndexOrThrow(BaseColumns._ID)
    val namecol   = c.getColumnIndexOrThrow(FIELD_NAME)
    val servercol = c.getColumnIndexOrThrow(FIELD_SERVER)
    val querycol  = c.getColumnIndexOrThrow(FIELD_QUERY)
    val lastcol   = c.getColumnIndexOrThrow(FIELD_LAST_TS)

    val channels = Iterator.continually(c.moveToNext()).takeWhile(x => x).map { _ =>
      val network = networks(c.getLong(servercol))
      val name = c.getString(namecol)
      (network.id, name.toLowerCase) -> Channel(network,
        c.getLong(idcol), name, c.getLong(lastcol), c.getInt(querycol) != 0)
    }.toMap
    c.close()
    close(db)
    channels
  }

  override def onCreate(db: SQLiteDatabase) {
    db.execSQL(TABLE_SERVERS_DDL)
    db.execSQL(TABLE_CHANNELS_DDL)
    db.execSQL(TABLE_LOGS_DDL)
    db.execSQL(TABLE_LOGS_NICK_INDEX_DDL)
    db.execSQL(TABLE_LOGS_CHANNEL_INDEX_DDL)
  }

  override def onUpgrade(db: SQLiteDatabase, prev: Int, ver: Int) = synchronized {
    if (prev < ver) {
      db.beginTransaction()
      Range(prev, ver + 1) foreach { v =>
        logcat.d("Upgrading to: " + v)
        UPGRADES.getOrElse(v, Seq.empty) foreach db.execSQL
      }

      db.setTransactionSuccessful()
      db.endTransaction()
    }
  }

  override def onOpen(db: SQLiteDatabase) = synchronized {
    super.onOpen(db)
    if (!db.isReadOnly)
      db.execSQL("PRAGMA foreign_keys=ON")
  }

  private def getChannel(channel: ChannelLike): Channel = synchronized {
    val network = networks.getOrElse(channel.server.id, {
      val net = Network(channel.server.name, channel.server.id)
      logcat.d(s"Inserting: $net, previous existing: $networks")
      val db = getWritableDatabase
      db.insertOrThrow(TABLE_SERVERS, null, net.values)
      close(db)
      networks += (net.id -> net)
      net
    })

    channels.getOrElse((network.id, channel.name.toLowerCase), {
      val query = channel match {
        case q: Query => true
        case c: ModelChannel => false
      }
      val ch = Channel(network, -1, channel.name.toLowerCase, 0, query)
      channels += (network.id, ch.name) -> ch
      val db = getWritableDatabase
      val id = db.insertOrThrow(TABLE_CHANNELS, null, ch.values)
      close(db)
      val ch2 = ch.copy(id = id)
      channels += (network.id, ch2.name.toLowerCase) -> ch2
      ch2
    })
  }

  def log(m: MessageLike, c: ChannelLike) = m match {
    case Privmsg(sender, message, _, _, ts) =>
      logEntry(Entry(getChannel(c), sender, ts.getTime, message))
    case Notice(sender, message, ts) =>
      logEntry(Entry(getChannel(c), sender, ts.getTime, message, notice = true))
    case CtcpAction(sender, message, ts) =>
      logEntry(Entry(getChannel(c), sender, ts.getTime, message, action = true))
    case _ =>
  }

  def logEntry(e: Entry) = synchronized {
    if (e.message != null) { // how can message possibly be null...
      val lastTs = e.channel.lastTs
      if (lastTs < e.ts) {
        val c = e.channel.copy(lastTs = e.ts)
        channels += (c.network.id, c.name) -> c
        val db = getWritableDatabase
        db.beginTransaction()
        db.update(TABLE_CHANNELS, c.values, s"${BaseColumns._ID}  = ?",
          Array(String.valueOf(c.id)))
        db.insertOrThrow(TABLE_LOGS, null, e.values)
        db.setTransactionSuccessful()
        db.endTransaction()
        close(db)
      }
    }
  }

  def delete(netId: Long, channel: String) = {
    val c = channels(netId -> channel)

    val db = getWritableDatabase
    db.beginTransaction()
    db.delete(TABLE_LOGS, s"$FIELD_CHANNEL = ?", Array(c.id.toString))
    db.delete(TABLE_CHANNELS, s"$FIELD_SERVER = ? AND $FIELD_NAME = ?",
      Array(netId.toString, channel))
    db.setTransactionSuccessful()
    db.endTransaction()
    db.execSQL("VACUUM")
    close(db)
    this.synchronized {
      channels -= (netId -> channel)
    }
  }

  def deleteAll() = {
    val db = getWritableDatabase
    db.beginTransaction()
    db.delete(TABLE_LOGS, null, null)
    db.delete(TABLE_CHANNELS, null, null)
    db.delete(TABLE_SERVERS, null, null)
    db.setTransactionSuccessful()
    db.endTransaction()
    db.execSQL("VACUUM")
    close(db)
    this.synchronized {
      channels = Map.empty
      networks = Map.empty
    }
  }

  var openCount = 0
  override def getWritableDatabase = synchronized {
    openCount += 1
    super.getWritableDatabase
  }

  override def getReadableDatabase = synchronized {
    openCount += 1
    super.getReadableDatabase
  }

  def close(db: SQLiteDatabase): Unit = synchronized {
    openCount -= 1
    if (openCount == 0)
      db.close()
  }

  def get(ctx: Activity, netId: Long, channel: String): LogAdapter =
    get(ctx, channels(netId -> channel.toLowerCase))
  def get(ctx: Activity, netId: Long, channel: String, query: String): LogAdapter =
    get(ctx, channels(netId -> channel.toLowerCase), query)

  def adapterFor(ctx: Activity, cursor: Cursor, db: SQLiteDatabase) = {
    val sendercol = cursor.getColumnIndexOrThrow(FIELD_SENDER)
    val tscol     = cursor.getColumnIndexOrThrow(FIELD_TIMESTAMP)
    val msgcol    = cursor.getColumnIndexOrThrow(FIELD_MESSAGE)
    val actioncol = cursor.getColumnIndexOrThrow(FIELD_ACTION)
    val notifycol = cursor.getColumnIndexOrThrow(FIELD_NOTICE)

    cursor.getCount // force cursor window to load; side-effecting, boooo

    new CursorAdapter(Application.context, cursor, 0) with Closeable {

      lazy val fontSetting = Settings.get(Settings.FONT_NAME).? flatMap (
        n => Try(Typeface.createFromFile(n)).toOption)
      lazy val sizeSetting = Settings.get(Settings.FONT_SIZE)

      override def newView(context: Context, c: Cursor, v: ViewGroup) = {
        val v = MessageAdapter.messageLayout(ctx).perform()
        v.setMovementMethod(LinkMovementMethod.getInstance)
        if (sizeSetting > 0)
          v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSetting)
        fontSetting foreach v.setTypeface
        bindView(v, ctx, c)
        v
      }

      override def getItem(p: Int) = {
        cursor.moveToPosition(p)
        cursor.getLong(tscol): java.lang.Long
      }

      override def bindView(v: View, context: Context, c: Cursor) = {
        val entry = Entry(null, c.getString(sendercol), c.getLong(tscol),
          c.getString(msgcol),
          action = c.getInt(actioncol) != 0, notice = c.getInt(notifycol) != 0)
        val m: MessageLike = if (entry.action) {
          CtcpAction(entry.sender, entry.message, new Date(entry.ts))
        } else if (entry.notice) {
          Notice(entry.sender, entry.message, new Date(entry.ts))
        } else {
          Privmsg(entry.sender, entry.message, ts = new Date(entry.ts))
        }
        v.asInstanceOf[TextView].setText(MessageAdapter.formatText(ctx, m))
      }

      override def close(): Unit = {
        cursor.close()
        MessageLog.this.close(db)
      }
    }
  }

  // must be an Activity context for theming
  def get(ctx: Activity, channel: Channel): LogAdapter = {
    val db = getReadableDatabase
    val query =
      s"""
         |SELECT * FROM $TABLE_LOGS WHERE $FIELD_CHANNEL = ? ORDER BY $FIELD_TIMESTAMP
       """.stripMargin
    val cursor = db.rawQuery(query, Array(String.valueOf(channel.id)))
    adapterFor(ctx, cursor, db)
  }

  def get(ctx: Activity, channel: Channel, q: String): LogAdapter = {
    val db = getReadableDatabase
    val query =
      s"""
         |SELECT * FROM $TABLE_LOGS WHERE $FIELD_CHANNEL = ?
         | AND ($FIELD_SENDER = ? COLLATE NOCASE OR $FIELD_MESSAGE LIKE ?)
         |  ORDER BY $FIELD_TIMESTAMP
       """.stripMargin
    val cursor = db.rawQuery(query,
      Array(String.valueOf(channel.id), q, s"%$q%"))
    adapterFor(ctx, cursor, db)
  }
}

object MessageLogActivity {
  val EXTRA_SERVER = "com.hanhuy.android.irc.EXTRA_SERVER_ID"
  val EXTRA_CHANNEL = "com.hanhuy.android.irc.EXTRA_CHANNEL"
  val EXTRA_QUERY = "com.hanhuy.android.irc.EXTRA_QUERY"

  def createIntent(c: Channel) = {
    val intent = new Intent(Application.context, classOf[MessageLogActivity])
    intent.putExtra(EXTRA_SERVER, c.network.id)
    intent.putExtra(EXTRA_CHANNEL, c.name)
    intent.addFlags(
      Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS |
        Intent.FLAG_ACTIVITY_NO_HISTORY)
    intent
  }
  def createIntent(c: ChannelLike, q: String): Intent = {
    val intent = createIntent(c)
    intent.putExtra(EXTRA_QUERY, q)
    intent
  }
  def createIntent(c: ChannelLike): Intent = {
    val intent = new Intent(Application.context, classOf[MessageLogActivity])
    intent.putExtra(EXTRA_SERVER, c.server.id)
    intent.putExtra(EXTRA_CHANNEL, c.name)
    intent.addFlags(
      Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS |
      Intent.FLAG_ACTIVITY_NO_HISTORY)
    intent
  }
}
class MessageLogActivity extends AppCompatActivity {
  import MessageLogActivity._
  val log = Logcat("MessageLogActivity")
  lazy val listview = new ListView(this)

  lazy val layout = l[FrameLayout](
    w[TextView] >>= k.visibility(View.GONE) >>=
      lpK(WRAP_CONTENT, 0)(margins(all = 12 dp)) >>=
      kestrel { tv =>
        tv.setGravity(Gravity.CENTER)
        tv.setTextAppearance(this, android.R.style.TextAppearance_Medium)
      } >>= k.text(R.string.no_messages) >>= kitkatPadding,
    IO(listview) >>=
      lp(MATCH_PARENT, MATCH_PARENT) >>=
      kestrel { l =>
        l.setSelector(R.drawable.message_selector)
        l.setDrawSelectorOnTop(true)
        l.setDivider(new ColorDrawable(Color.BLACK))
        l.setDividerHeight(0)
        l.setChoiceMode(AbsListView.CHOICE_MODE_NONE)
        l.setTranscriptMode(AbsListView.TRANSCRIPT_MODE_NORMAL)
        l.setClipToPadding(false)
        l.setTranscriptMode(AbsListView.TRANSCRIPT_MODE_ALWAYS_SCROLL)
        l.setFastScrollEnabled(true)
      } >>= kitkatPadding,
    IO(progressbar) >>=
      lp(128 dp, 128 dp, Gravity.CENTER),
    toolbar.! >>=
      lpK(MATCH_PARENT, actionBarHeight)(kitkatStatusMargin)
  )

  lazy val toolbar = newToolbar

  lazy val progressbar = new ProgressBar(this)
  var adapter = Option.empty[LogAdapter]
  var nid = -1l
  var channel = ""
  lazy val dfmt = DateFormat.getDateFormat(this)
  lazy val tfmt = DateFormat.getTimeFormat(this)

  def setAdapter(a: Option[LogAdapter]): Unit = {
    if (a.isEmpty)
      getSupportActionBar.setSubtitle(null)
    listview.setAdapter(a orNull)
    progressbar.setVisibility(if (a.isEmpty) View.VISIBLE else View.GONE)
    adapter foreach (_.close())

    adapter = a

    adapter foreach { a =>
      if (a.getCount > 0) {
        val t = a.getItem(0)
        getSupportActionBar.setSubtitle(dfmt.format(t) + " " + tfmt.format(t))
      }
      listview.setSelection(a.getCount - 1)
    }
  }

  override def onNewIntent(intent: Intent) = {
    val bar = getSupportActionBar
    nid = intent.getLongExtra(EXTRA_SERVER, -1)
    channel = intent.getStringExtra(EXTRA_CHANNEL).?.getOrElse("")
    val query = intent.getStringExtra(EXTRA_QUERY)
    setAdapter(None)

    if (MessageLog.channels.contains(nid -> channel.toLowerCase)) {
      bar.setTitle(s"logs: $channel")
      bar.setSubtitle(null)
      Future {
        query.?.fold(MessageLog.get(this, nid, channel))(
          MessageLog.get(this, nid, channel, _))
      } onSuccessMain { case a => setAdapter(Some(a)) }
    } else {
      Toast.makeText(this, "No logs available", Toast.LENGTH_SHORT).show()
      finish()
    }
  }

  override def onCreate(savedInstanceState: Bundle) = {
    setTheme(if (Settings.get(Settings.DAYNIGHT_MODE)) R.style.AppTheme_Light else R.style.AppTheme_Dark)
    super.onCreate(savedInstanceState)
    import android.content.pm.ActivityInfo._
    setRequestedOrientation(
      if (Settings.get(Settings.ROTATE_LOCK))
        SCREEN_ORIENTATION_NOSENSOR else SCREEN_ORIENTATION_SENSOR)

    setContentView(layout.perform())
    setSupportActionBar(toolbar)
    // This is required because this is a vector drawable originally
    // but we rasterize vectors during build
    // due to a bug in Moto Display, it fails to render vector images
    // as a result, vector images are also deleted so only the rasterized
    // images remain. Tint manually!
    val d = getResources.getDrawable(R.drawable.abc_ic_ab_back_material)
    DrawableCompat.setTint(DrawableCompat.wrap(d.mutate()), resolveAttr(R.attr.qicrNotificationIconTint, _.data))
    toolbar.setNavigationIcon(d)

    onNewIntent(getIntent)
    var first = 0
    listview.scrolled {
      (_, fst, vis, total) =>
        if (fst != first) {
          adapter foreach { a =>
            if (fst < a.getCount) {
              val t = a.getItem(fst)
              toolbar.setSubtitle(dfmt.format(t) + " " + tfmt.format(t))
            }
          }
        }
        first = fst
    }
    listview.setOnScrollListener(new OnScrollListener {
      var first = 0

      override def onScrollStateChanged(p1: AbsListView, p2: Int) = ()

      override def onScroll(p1: AbsListView, fst: Int, vis: Int, total: Int) {
        if (fst != first) {
          adapter foreach { a =>
            if (fst < a.getCount) {
              val t = a.getItem(fst)
              toolbar.setSubtitle(dfmt.format(t) + " " + tfmt.format(t))
            }
          }
        }
        first = fst
      }
    })
  }

  lazy val label: Kestrel[TextView] = c[TableRow](lpK(WRAP_CONTENT, WRAP_CONTENT)(margins(right = 12.dp)) >=>
    kestrel(_.setTextAppearance(this, android.R.style.TextAppearance_Medium)))

  override def onOptionsItemSelected(item: MenuItem) = item.getItemId match {
    case R.id.menu_log_delete =>
      val builder = new AlertDialog.Builder(this)
      builder.setTitle("Delete Logs?")
      builder.setMessage("Delete all logs or messages from the current window?")
      builder.setPositiveButton("Current", () => {
        Future {
          MessageLog.delete(nid, channel)
        }
        finish()
      })
      builder.setNegativeButton("Cancel", null)
      builder.setNeutralButton("All", () => {
        Future {
          MessageLog.deleteAll()
        }
        finish()
      })
      builder.create().show()
      true
    case R.id.menu_log_others =>
      val othersLayout = (
        w[ExpandableListView] >>= padding(all = 12 dp)
      ).perform()

      val popup = new PopupWindow(othersLayout, 300 dp, 300 dp, true)
      val networks = MessageLog.networks.values.toList.sortBy(_.name)
      val channels = MessageLog.channels.values.toList.groupBy(_.network).map {
        case (k,v) => k -> v.sortBy(_.name)
      }
      othersLayout.setAdapter(new BaseExpandableListAdapter {

        override def getChildId(p1: Int, p2: Int) = channels(networks(p1))(p2).id
        override def getChild(p1: Int, p2: Int) = channels(networks(p1))(p2)
        override def getGroupCount = networks.size
        override def isChildSelectable(p1: Int, p2: Int) = true
        override def getGroupId(p1: Int) = networks(p1).id
        override def getGroup(p1: Int) = networks(p1)
        override def getChildrenCount(p1: Int) = channels(networks(p1)).size
        override def hasStableIds = true

        override def getChildView(p1: Int, p2: Int, p3: Boolean, p4: View, p5: ViewGroup) = {
          val text = p4.?.fold{
            val v = new TextView(MessageLogActivity.this,
              null, android.R.style.TextAppearance_Medium)
            v.setLayoutParams(new AbsListView.LayoutParams(MATCH_PARENT, 48 dp))
            v.setPadding(48 dp, 0, 0, 0)
            v.setGravity(Gravity.CENTER_VERTICAL)
            v
          }(_.asInstanceOf[TextView])

          text.setText(getChild(p1, p2).name)
          text
        }

        override def getGroupView(p1: Int, p2: Boolean, p3: View, p4: ViewGroup) = {
          val text = p3.?.fold{
            val v = new TextView(MessageLogActivity.this,
              null, android.R.style.TextAppearance_Large)
            v.setLayoutParams(new AbsListView.LayoutParams(MATCH_PARENT, 48 dp))
            v.setGravity(Gravity.CENTER_VERTICAL)
            v.setPadding(36 dp, 0, 0, 0)
            v
          }(_.asInstanceOf[TextView])

          text.setText(getGroup(p1).name)
          text
        }
      })
      networks.indices foreach (i => othersLayout.expandGroup(i))
      othersLayout.setOnChildClickListener(new OnChildClickListener {
        override def onChildClick(p1: ExpandableListView,
                                  p2: View, p3: Int, p4: Int, p5: Long) = {
          val c = channels(networks(p3))(p4)
          startActivity(createIntent(c))
          popup.dismiss()
          true
        }
      })
      popup.setBackgroundDrawable(
        getResources.getDrawable(R.drawable.log_info_background))
      popup.setOutsideTouchable(true)
      popup.showAtLocation(getWindow.getDecorView, Gravity.CENTER, 0, 0)

      true
    case R.id.menu_log_info =>
      lazy val databaseSize = new TextView(this)
      lazy val channelLines = new TextView(this)
      lazy val channelName = new TextView(this)
      lazy val channelEnd = new TextView(this)
      lazy val channelStart = new TextView(this)

      val infoLayout = (
        l[TableLayout](
          l[TableRow](
            w[TextView] >>= label >>= k.text("Log Name"),
            IO(channelName) >>= label
          ) >>= lp(MATCH_PARENT, WRAP_CONTENT),
          l[TableRow](
            w[TextView] >>= label >>= k.text("Start"),
            IO(channelStart)
          ) >>= lp(MATCH_PARENT, WRAP_CONTENT),
          l[TableRow](
            w[TextView] >>= label >>= k.text("End"),
            IO(channelEnd)
          ) >>= lp(MATCH_PARENT, WRAP_CONTENT),
          l[TableRow](
            w[TextView] >>= label >>= k.text("Line Count"),
            IO(channelLines) >>= label
          ) >>= lp(MATCH_PARENT, WRAP_CONTENT),
          w[View] >>= lp(MATCH_PARENT, 16 dp),
          l[TableRow](
            w[TextView] >>= label >>= k.text("All Logs"),
            IO(databaseSize) >>= label
          ) >>= lp(MATCH_PARENT, WRAP_CONTENT)
        ) >>= padding(all = 12 dp) >>=
          kestrel { v: TableLayout => v.setClickable(true) }
      ).perform()


      adapter foreach { a =>
        val start = a.getItem(0)
        channelStart.setText(dfmt.format(start) + " " + tfmt.format(start))
        val end = if (a.getCount > 1) a.getItem(a.getCount - 1) else start
        channelEnd.setText(dfmt.format(end) + " " + tfmt.format(end))
      }
      val f = getDatabasePath(DATABASE_NAME)
      val size = (f.length: Double) match {
        case x if x > 1000000000 => "%.3f GB" format (x / 1000000000)
        case x if x > 1000000    => "%.2f MB" format (x / 1000000)
        case x if x > 1000       => "%.0f KB" format (x / 1000)
        case x => s"$x bytes"
      }
      databaseSize.setText(size)
      channelName.setText(channel)
      adapter foreach { a => channelLines.setText(s"${a.getCount}") }

      val popup = new PopupWindow(infoLayout, 300 dp, 160 dp, true)
      popup.setBackgroundDrawable(
        getResources.getDrawable(R.drawable.log_info_background))
      popup.setOutsideTouchable(true)
      popup.showAtLocation(getWindow.getDecorView, Gravity.CENTER, 0, 0)

      true
    case android.R.id.home =>
      onBackPressed()
      true
    case _ => super.onOptionsItemSelected(item)
  }

  override def onResume() {
    super.onResume()
  }

  override def onDestroy(): Unit = {
    super.onDestroy()
    adapter foreach (_.close())
  }

  override def onBackPressed() = {
    super.onBackPressed()
    overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
  }

  override def onStart() {
    super.onStart()
  }

  override def onStop() {
    super.onStop()
  }

  override def onCreateOptionsMenu(menu: Menu) = {
    getMenuInflater.inflate(R.menu.log_menu, menu)
    val item = menu.findItem(R.id.menu_search)
    val searchView = MenuItemCompat.getActionView(
      item).asInstanceOf[android.support.v7.widget.SearchView]
    searchView.setOnCloseListener(new OnCloseListener {
      override def onClose() = {
        setAdapter(None)

        Future {
          MessageLog.get(MessageLogActivity.this, nid, channel)
        } onSuccessMain { case c => setAdapter(Some(c)) }
        false
      }
    })
    searchView.setOnQueryTextListener(new OnQueryTextListener {
      override def onQueryTextSubmit(p1: String) = {
        hideIME()
        val query = searchView.getQuery.toString.trim
        if (query.nonEmpty) {
          setAdapter(None)
          Future {
            MessageLog.get(MessageLogActivity.this, nid, channel, query)
          } onSuccessMain { case c => setAdapter(Some(c)) }
        }
        true
      }

      override def onQueryTextChange(p1: String) = true
    })
    true
  }
}