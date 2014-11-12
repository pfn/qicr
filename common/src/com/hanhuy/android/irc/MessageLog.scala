package com.hanhuy.android.irc

import java.io.Closeable

import java.util.Date

import android.app.Activity
import android.content.{Intent, ContentValues, Context}
import android.database.Cursor
import android.database.sqlite.{SQLiteDatabase, SQLiteOpenHelper}
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.{Bundle, Handler, HandlerThread}
import android.provider.BaseColumns
import MessageLog._
import android.support.v7.app.ActionBarActivity
import android.text.TextUtils.TruncateAt
import android.text.method.LinkMovementMethod
import android.view.ViewGroup.LayoutParams._
import android.view.{MenuItem, Gravity, ViewGroup, View}
import android.widget._
import com.hanhuy.android.common._
import com.hanhuy.android.irc.Tweaks._
import com.hanhuy.android.irc.model.{Channel => ModelChannel, _}
import com.hanhuy.android.irc.model.MessageLike.{CtcpAction, Notice, Privmsg}
import AndroidConversions._
import RichLogger.{w => _, _}
import macroid._
import macroid.FullDsl._

/**
 * @author pfnguyen
 */
object MessageLog {
  implicit val TAG = LogcatTag("MessageLog")
  val DATABASE_NAME    = "logs"
  val DATABASE_VERSION = 1

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

  val TABLE_LOGS_CHANNEL_INDEX_DDL =
    s"""
       |CREATE INDEX idx_logs_chan ON $TABLE_LOGS($FIELD_CHANNEL);
     """.stripMargin

  val TABLE_SERVERS_DDL =
    s"""
       |CREATE TABLE $TABLE_SERVERS (
       |  ${BaseColumns._ID} INTEGER PRIMARY KEY,
       |  $FIELD_NAME        TEXT NOT NULL,
       |  CONSTRAINT unq_server UNIQUE ($FIELD_NAME ASC)
       |);
     """.stripMargin

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
  def get(c: Activity, serverId: Long, channel: String): LogAdapter = {
    instance.get(c, serverId, channel)
  }

  def log(m: MessageLike, c: ChannelLike) = if (settings.get(Settings.IRC_LOGGING))
    handler.post { () => instance.log(m, c) }

  lazy val handlerThread = {
    val t = new HandlerThread("MessageLogHandler")
    t.start()
    t
  }
  // used to schedule an irc ping every 30 seconds
  lazy val handler = new Handler(handlerThread.getLooper)
  lazy val settings = Settings(Application.context)
  private val instance = new MessageLog(Application.context)
}

class MessageLog private(context: Context)
  extends SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

  def networks = {
    if (_networks.isEmpty)
      _networks = initialNetworks
    _networks
  }

  def networks_=(n: Map[Long,Network]) = _networks = n
  def channels_=(c: Map[(Long,String),Channel]) = _channels = c

  def channels = {
    if (_channels.isEmpty)
      _channels = initialChannels
    _channels
  }

  private var _networks = Map.empty[Long,Network]
  private var _channels = Map.empty[(Long,String),Channel]

  lazy val initialNetworks = {
    val db = getReadableDatabase
    val c = db.query(TABLE_SERVERS, null, null, null, null, null, null)
    val idcol   = c.getColumnIndexOrThrow(BaseColumns._ID)
    val namecol = c.getColumnIndexOrThrow(FIELD_NAME)
    val networks = Stream.continually(c.moveToNext()).takeWhile (x => x).map {
      _ =>
        val id = c.getLong(idcol)
        id -> Network(c.getString(namecol), id)
    }.force.toMap

    c.close()
    db.close()
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

    val channels = Stream.continually(c.moveToNext()).takeWhile(x => x).map { _ =>
      val network = networks(c.getLong(servercol))
      val name = c.getString(namecol)
      (network.id, name.toLowerCase) -> Channel(network,
        c.getLong(idcol), name, c.getLong(lastcol), c.getInt(querycol) != 0)
    }.force.toMap
    c.close()
    db.close()
    channels
  }

  override def onCreate(db: SQLiteDatabase) {
    db.execSQL(TABLE_SERVERS_DDL)
    db.execSQL(TABLE_CHANNELS_DDL)
    db.execSQL(TABLE_LOGS_DDL)
    db.execSQL(TABLE_LOGS_NICK_INDEX_DDL)
    db.execSQL(TABLE_LOGS_CHANNEL_INDEX_DDL)
  }

  override def onUpgrade(db: SQLiteDatabase, prev: Int, version: Int) = ()

  override def onOpen(db: SQLiteDatabase) = synchronized {
    super.onOpen(db)
    if (!db.isReadOnly)
      db.execSQL("PRAGMA foreign_keys=ON")
  }

  private def getChannel(channel: ChannelLike): Channel = synchronized {
    val network = networks.getOrElse(channel.server.id, {
      val net = Network(channel.server.name, channel.server.id)
      val db = getWritableDatabase
      db.insertOrThrow(TABLE_SERVERS, null, net.values)
      db.close()
      networks += (net.id -> net)
      net
    })

    channels.getOrElse((network.id, channel.name.toLowerCase), {
      val query = channel match {
        case q: Query => true
        case c: ModelChannel => false
      }
      val ch = Channel(network, -1, channel.name, 0, query)
      val db = getWritableDatabase
      val id = db.insertOrThrow(TABLE_CHANNELS, null, ch.values)
      db.close()
      val ch2 = ch.copy(id = id)
      channels += (network.id, ch2.name) -> ch2
      ch2
    })
  }

  def log(m: MessageLike, c: ChannelLike) = {
    val entry = m match {
      case Privmsg(sender, message, _, _, ts) =>
        logEntry(Entry(getChannel(c), sender, ts.getTime, message))
      case Notice(sender, message, ts) =>
        logEntry(Entry(getChannel(c), sender, ts.getTime, message, notice = true))
      case CtcpAction(sender, message, ts) =>
        logEntry(Entry(getChannel(c), sender, ts.getTime, message, action = true))
      case _ => None
    }
  }

  def logEntry(e: Entry) = synchronized {
    val lastTs = e.channel.lastTs
    if (lastTs < e.ts) {
      val c = e.channel.copy(lastTs = e.ts)
      channels += (c.network.id, c.name) -> c
      val db = getWritableDatabase
      db.beginTransaction()
      db.update(TABLE_CHANNELS, c.values, s"${BaseColumns._ID}  = ?",
        Array(String.valueOf(c.id)))
      val newid = db.insertOrThrow(TABLE_LOGS, null, e.values)
      db.setTransactionSuccessful()
      db.endTransaction()
      db.close()
    }
  }

  def get(ctx: Activity, netId: Long, channel: String): LogAdapter =
    get(ctx, channels(netId -> channel))

  // must be an Activity context for theming
  def get(ctx: Activity, channel: Channel): LogAdapter = {
    val db = getReadableDatabase
    val query =
      s"""
         |SELECT * FROM $TABLE_LOGS WHERE $FIELD_CHANNEL = ? ORDER BY $FIELD_TIMESTAMP
       """.stripMargin
    val cursor = db.rawQuery(query, Array(String.valueOf(channel.id)))

    val sendercol = cursor.getColumnIndexOrThrow(FIELD_SENDER)
    val tscol     = cursor.getColumnIndexOrThrow(FIELD_TIMESTAMP)
    val msgcol    = cursor.getColumnIndexOrThrow(FIELD_MESSAGE)
    val actioncol = cursor.getColumnIndexOrThrow(FIELD_ACTION)
    val notifycol = cursor.getColumnIndexOrThrow(FIELD_NOTICE)

    new CursorAdapter(Application.context, cursor, 0) with Closeable {
      override def newView(context: Context, c: Cursor, v: ViewGroup) = {
        val v = getUi(MessageAdapter.messageLayout(ctx))
        v.setMovementMethod(LinkMovementMethod.getInstance)
        bindView(v, context, c)
        v
      }

      override def bindView(v: View, context: Context, c: Cursor) = {
        val entry = Entry(channel, c.getString(sendercol), c.getLong(tscol),
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
        db.close()
      }
    }
  }
}

object MessageLogActivity {
  val EXTRA_SERVER = "com.hanhuy.android.irc.EXTRA_SERVER_ID"
  val EXTRA_CHANNEL = "com.hanhuy.android.irc.EXTRA_CHANNEL"
  def createIntent(c: ChannelLike) = {
    val intent = new Intent(Application.context, classOf[MessageLogActivity])
    intent.putExtra(EXTRA_SERVER, c.server.id)
    intent.putExtra(EXTRA_CHANNEL, c.name)
    intent
  }
}
class MessageLogActivity extends ActionBarActivity with Contexts[Activity] {
  import MessageLogActivity._
  implicit val TAG = LogcatTag("MessageLogActivity")
  var listview: ListView = _
  lazy val layout = l[FrameLayout](
    l[FrameLayout](
    w[TextView] <~ id(R.id.empty_list) <~ hide <~
      lp[LinearLayout](WRAP_CONTENT, 0, 1.0f) <~ margin(all = 12 dp) <~
      tweak { tv: TextView =>
        tv.setGravity(Gravity.CENTER)
        tv.setTextAppearance(this, android.R.attr.textAppearanceMedium)
      } <~ text(R.string.no_messages) <~ kitkatPadding,
    w[ListView] <~ id(R.id.message_list) <~ wire(listview) <~
      lp[FrameLayout](MATCH_PARENT, MATCH_PARENT) <~
      tweak { l: ListView =>
        l.setSelector(R.drawable.message_selector)
        l.setDrawSelectorOnTop(true)
        l.setDivider(new ColorDrawable(Color.BLACK))
        l.setDividerHeight(0)
        l.setChoiceMode(AbsListView.CHOICE_MODE_NONE)
        l.setTranscriptMode(AbsListView.TRANSCRIPT_MODE_NORMAL)
        l.setClipToPadding(false)
        l.setTranscriptMode(AbsListView.TRANSCRIPT_MODE_ALWAYS_SCROLL)
        l.setFastScrollEnabled(true)
      } <~ kitkatPadding
    )
  )

  var adapter = Option.empty[LogAdapter]

  override def onCreate(savedInstanceState: Bundle) = {
    super.onCreate(savedInstanceState)
    val bar = getSupportActionBar
    bar.setDisplayHomeAsUpEnabled(true)
    bar.setDisplayShowHomeEnabled(true)
    setContentView(getUi(layout))

    val intent = getIntent
    val id = intent.getLongExtra(EXTRA_SERVER, -1)
    val channel = intent.getStringExtra(EXTRA_CHANNEL)
    if (id == -1) {
      finish()
    } else {
      val a = MessageLog.get(this, id, channel)
      bar.setTitle("channel logs")
      bar.setSubtitle(channel)
      adapter = Some(a)
      listview.setAdapter(a)
    }
  }

  override def onOptionsItemSelected(item: MenuItem) = item.getItemId match {
    case android.R.id.home =>
      onBackPressed()
      true
    case _ => super.onOptionsItemSelected(item)
  }

  override def onResume() {
    super.onResume()
    adapter foreach { a => listview.setSelection(a.getCount - 1) }
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
    ServiceBus.send(BusEvent.MainActivityStart)
  }

  override def onStop() {
    super.onStop()
    ServiceBus.send(BusEvent.MainActivityStop)
  }
}