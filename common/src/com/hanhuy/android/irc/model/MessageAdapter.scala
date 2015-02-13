package com.hanhuy.android.irc.model

import android.app.Activity
import android.text.util.Linkify
import android.util.TypedValue
import com.hanhuy.android.irc._

import com.hanhuy.android.common.{AndroidConversions, UiBus, EventBus, SpannedGenerator}
import AndroidConversions._
import SpannedGenerator._
import MessageLike._

import scala.ref.WeakReference

import android.graphics.Typeface
import android.view.{Gravity, LayoutInflater, View, ViewGroup}
import android.content.Context
import android.widget.{AbsListView, BaseAdapter, TextView}

import java.text.SimpleDateFormat

import android.text.style.ClickableSpan
import android.text.TextPaint
import android.text.method.LinkMovementMethod

import scala.reflect.ClassTag
import scala.util.Try

import Tweaks._
import macroid._
import macroid.FullDsl._

trait MessageAppender {
  def add(m: MessageLike): Unit
  def clear(): Unit
}

object MessageAdapter extends EventBus.RefOwner {
  lazy val gbfont = Typeface.createFromAsset(
    Application.context.getAssets, "DejaVuSansMono.ttf")
  private var fontSetting = Option(Settings.get(Settings.FONT_NAME)) flatMap (
    n => Try(Typeface.createFromFile(n)).toOption)
  val NICK_COLORS = Array(
    0xff33b5e5, 0xffaa66cc, 0xff99cc00, 0xffffbb33, 0xffff4444,
    0xff0099cc, 0xff9933cc, 0xff669900, 0xffff8800, 0xffcc0000)

  val TAG = "MessageAdapter"
  val DEFAULT_MAXIMUM_SIZE = 256
  private var showTimestamp = false
  private lazy val sdf = new SimpleDateFormat("HH:mm ")

  def formatText(c: Context, msg: MessageLike)
      (implicit channel: ChannelLike = null): CharSequence = {
    val ch = Option(channel)
    msg match {
      case Whois(text,_) => text
      case MessageLike.Query(_) => formatText(c, msg, R.string.query_template,
        colorNick(ch.get.name))
      case Privmsg(s, m, o, v,_) => gets(c, R.string.message_template, msg,
         s, m, (o,v))
      case Notice(s, m,_) => gets(c, R.string.notice_template, msg, s, m)
      case CtcpAction(s, m,_) => gets(c, R.string.action_template, msg, s, m)
      case CtcpRequest(server, t, cmd, args,_) =>
        ch flatMap { chan =>
          if (chan.server != server)
            Some(formatText(c, msg, R.string.ctcp_request_template_s,
              colorNick(t), textColor(nickColor(cmd), cmd),
              args getOrElse "", server.name))
          else
            None
        } getOrElse {
          formatText(c, msg, R.string.ctcp_request_template,
            colorNick(t), textColor(nickColor(cmd), cmd), args getOrElse "")
        }
      case CtcpReply(server, s, cmd, a,_) => a map { arg =>
        ch flatMap { chan =>
          if (chan.server != server)
            Some(formatText(c, msg, R.string.ctcp_response_template_s_3,
              textColor(nickColor(cmd), cmd), colorNick(s), arg,
              textColor(nickColor(server.name), server.name)))
          else
            None
        } getOrElse {
          formatText(c, msg, R.string.ctcp_response_template_3,
            textColor(nickColor(cmd), cmd), colorNick(s), arg)
        }
      } getOrElse {
        ch flatMap { chan =>
          if (chan.server != server)
            Some(formatText(c, msg, R.string.ctcp_response_template_s_2,
              textColor(nickColor(cmd), cmd), colorNick(s),
              textColor(nickColor(server.name), server.name)))
          else
            None
        } getOrElse {
          formatText(c, msg, R.string.ctcp_response_template_2,
            textColor(nickColor(cmd), cmd), colorNick(s))
        }
      }
      case Topic(src, t,_,_) => src map { s =>
        formatText(c, msg, R.string.topic_template_2,
          colorNick(s), bold(italics(channel.name)), t)
      } getOrElse {
        formatText(c, msg, R.string.topic_template_1,
          bold(italics(channel.name)), t)
      }
      case NickChange(o, n,_) =>
        formatText(c, msg, R.string.nick_change_template,
          colorNick(o), colorNick(n))
      case Join(n, u,_)    => formatText(c, msg, R.string.join_template,
        colorNick(n), u)
      case Part(n, u, m,_) => formatText(c, msg, R.string.part_template,
        colorNick(n), u, if (m == null) "" else m)
      case Quit(n, u, m,_) => formatText(c, msg, R.string.quit_template,
        colorNick(n), u, m)
      case Kick(o, n, m,_) => formatText(c, msg, R.string.kick_template,
        colorNick(n), colorNick(o), if (m == null) "" else m)
      case CommandError(m,_)  => formatText(c, msg, -1, m)
      case ServerInfo(m,_)    => formatText(c, msg, -1, m)
      case Motd(m,_)          => formatText(c, msg, -1, m)
      case SslInfo(m,_)       => formatText(c, msg, -1, m)
      case SslError(m,_)      => formatText(c, msg, -1, m)
    }
  }

  private def formatText(c: Context, msg: MessageLike, res: Int,
      args: CharSequence*) = {
    val text =  if (res == -1) args(0)
      else getString(c, res) formatSpans (args:_*)
    if (showTimestamp) {
      "%1%2" formatSpans(sdf.format(msg.ts), text)
    } else text
  }

  private def getString(c: Context, res: Int) = c.getString(res)
  private def getString(c: Context, res: Int, args: String*) = {
    res match {
    case -1 => args(0)
    case _ => c.getString(res, args: _*)
    }
  }

  def nickColor(n: String) = NICK_COLORS(math.abs(n.hashCode) % 10)
  private def gets(c: Context, res: Int, m: MessageLike, src: String,
      msg: String, modes: (Boolean,Boolean) = (false, false))(
      implicit channel: ChannelLike) = {
    val server = Option(channel) map (_.server)
    val (op, voice) = modes

    val prefix = {if (op) "@" else if (voice) "+" else ""}

    if (channel.isInstanceOf[Query]) {
      if (server exists (_.currentNick equalsIgnoreCase src))
        formatText(c, m, res, textColor(0xff009999, src), msg)
      else
        formatText(c, m, res, textColor(0xffcc0000, src), msg)
    } else if (server exists (_.currentNick equalsIgnoreCase src)) {
      formatText(c, m, res, "%1%2" formatSpans (prefix, bold(src)), msg)
    } else if (server exists (s => IrcListeners.matchesNick(s, msg) &&
        !s.currentNick.equalsIgnoreCase(src))) {
      formatText(c, m, res, "%1%2" formatSpans(prefix,
        bold(italics(colorNick(src)))), bold(msg))
    } else
      formatText(c, m, res,
        "%1%2" formatSpans(prefix, colorNick(src)), msg)
  }

  def colorNick(nick: String): CharSequence = {
    val text = textColor(nickColor(nick), nick)
    val inMain = MainActivity.instance.isDefined
    if (nick != "***" && inMain)
      SpannedGenerator.span(NickClick(text), text) else text
  }
  lazy implicit val actx = AppContext(Application.context)
  def messageLayout(ctx: Activity) = {
    implicit val c = ActivityContext(ctx)
    import Linkify._
    import ViewGroup.LayoutParams._
    w[TextView] <~ id(android.R.id.text1) <~
      lp[AbsListView](MATCH_PARENT, WRAP_CONTENT) <~
      tweak { tv: TextView =>
        tv.setAutoLinkMask(WEB_URLS | EMAIL_ADDRESSES | MAP_ADDRESSES)
        tv.setLinksClickable(true)
        tv.setTextAppearance(ctx, android.R.style.TextAppearance_Small)
        tv.setTypeface(Typeface.MONOSPACE)
        tv.setGravity(Gravity.CENTER_VERTICAL)
      } <~ padding(left = 6 dp, right = 6 dp)
  }

  UiBus += {
    case BusEvent.PreferenceChanged(_, k) =>
      if (k == Settings.FONT_NAME) {
        fontSetting = Try(Typeface.createFromFile(
          Settings.get(Settings.FONT_NAME))).toOption
      }
  }
}

class MessageAdapter(_channel: ChannelLike) extends BaseAdapter with EventBus.RefOwner {
  import MessageAdapter._

  // TODO FIXME might activity might be None

  implicit val channel = _channel
  var showJoinPartQuit = false
  var _maximumSize = DEFAULT_MAXIMUM_SIZE
  def maximumSize = _maximumSize
  def maximumSize_=(s: Int) = {
    if (_maximumSize != s)
      _messages = messages.copy(s)
    _maximumSize = s
  }

  private var filterCache = Option.empty[Seq[MessageLike]]
  def messages = _messages
  private var _messages = RingBuffer[MessageLike](maximumSize)

    // only register once to prevent memory leak
  UiBus += { case BusEvent.PreferenceChanged(s, k) =>
    if (k == Settings.MESSAGE_LINES) {
      val max = Try(s.get(Settings.MESSAGE_LINES).toInt).toOption getOrElse
        DEFAULT_MAXIMUM_SIZE
      maximumSize = max
    } else if (k == Settings.SHOW_JOIN_PART_QUIT) {
      showJoinPartQuit = s.get(Settings.SHOW_JOIN_PART_QUIT)
      notifyDataSetChanged()
    } else if (k == Settings.SHOW_TIMESTAMP) {
      MessageAdapter.showTimestamp = s.get(Settings.SHOW_TIMESTAMP)
      notifyDataSetChanged()
    } else if (k == Settings.FONT_NAME) {
      notifyDataSetChanged()
    } else if (k == Settings.FONT_SIZE) {
      size = Settings.get(Settings.FONT_SIZE)
      notifyDataSetChanged()
    }
  }

  var _activity: WeakReference[Activity] = _
  // can't make this IrcService due to resource changes on recreation
  def context_= (c: Activity) = {
    if (c != null) {
      _activity = new WeakReference(c)
      IrcManager.instance foreach { manager =>
        // It'd be nice to register a ServiceBus listener, but no way
        // to clean up when this adapter goes away?
        // add it to UiBus here maybe?
        maximumSize = Try(
          Settings.get(Settings.MESSAGE_LINES).toInt).toOption getOrElse
            Settings.MESSAGE_LINES.default.toInt
        showJoinPartQuit = Settings.get(Settings.SHOW_JOIN_PART_QUIT)
        MessageAdapter.showTimestamp = Settings.get(Settings.SHOW_TIMESTAMP)
      }
    }
  }

  def context = _activity.get orElse Some(Application.context) getOrElse {
    throw new IllegalStateException }
  // would be nice to move this into the companion

  def clear() {
    messages.clear()
    notifyDataSetChanged()
  }

  protected[model] def add(item: MessageLike) {
    messages += item
    filterCache = None
    if (_activity != null && isMainThread)
      _activity.get foreach { _ => notifyDataSetChanged() }
  }

  def filteredMessages = {
    if (showJoinPartQuit)
      messages
    else {
      filterCache getOrElse {
        val filtered = messages.filter {
          case Join(_,_,_)   => false
          case Part(_,_,_,_) => false
          case Quit(_,_,_,_) => false
          case _           => true
        }
        filterCache = Some(filtered)
        filtered
      }
    }
  }

  override def getItemId(pos: Int) : Long = pos
  override def getItem(pos: Int) : MessageLike = filteredMessages(pos)
  override def getCount : Int = filteredMessages.size

  private var size = Settings.get(Settings.FONT_SIZE)

  override def getView(pos: Int, convertView: View, container: ViewGroup) = {
    val c = if (convertView == null || convertView.getContext == context)
      convertView.asInstanceOf[TextView] else null
    val view = if (c != null) c else {
      val v = getUi(messageLayout(_activity.get.get))

      if (!icsAndNewer)
        v.setTypeface(gbfont)

      v.setMovementMethod(LinkMovementMethod.getInstance)
      v
    }

    if (size > 0) view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
    fontSetting foreach view.setTypeface

    view.setText(formatText(getItem(pos)))
    view
  }

  override def notifyDataSetChanged() {
    filterCache = None
    super.notifyDataSetChanged()
  }

  private def formatText(msg: MessageLike) =
    MessageAdapter.formatText(context, msg)

}

case class RingBuffer[A: ClassTag](capacity: Int) extends IndexedSeq[A] {
  private val buffer = Array.fill[A](capacity)(null.asInstanceOf[A])
  private var _length = 0
  private var pos = 0
  def length = _length

  private def zero = (capacity + (pos - _length)) % capacity

  def clear() {
    _length = 0
    pos = 0
  }
  def +=(a: A) {
    buffer(pos % capacity) = a
    _length = math.min(_length + 1, capacity)
    pos += 1
  }

  def apply(i: Int) = buffer((zero + i) % capacity)
  def copy(capacity: Int = capacity) = {
    val b = RingBuffer[A](capacity)
    foreach { item => b += item }
    b
  }
}

case class NickClick(nick: String) extends ClickableSpan {
  override def updateDrawState(ds: TextPaint) = ()

  def onClick(v: View) {
    v.getContext match {
      case a: MainActivity =>
        // TODO refactor this callback
        HoneycombSupport.startNickActionMode(nick) { item =>
          val manager = IrcManager.instance.get
          val R_id_nick_start_chat = R.id.nick_start_chat
          val R_id_nick_whois = R.id.nick_whois
          val R_id_nick_ignore = R.id.nick_ignore
          val R_id_nick_log = R.id.channel_log
          item.getItemId match {
            case R_id_nick_log =>
              a.startActivity(MessageLogActivity.createIntent(manager.lastChannel.get, nick))
              a.overridePendingTransition(
                R.anim.slide_in_left, R.anim.slide_out_right)
            case R_id_nick_whois =>
              val proc = CommandProcessor(a, null)
              proc.channel = manager.lastChannel
              proc.WhoisCommand.execute(Some(nick))
            case R_id_nick_ignore => ()
              val proc = CommandProcessor(a, null)
              proc.channel = manager.lastChannel
              if (Config.Ignores(nick))
                proc.UnignoreCommand.execute(Some(nick))
              else
                proc.IgnoreCommand.execute(Some(nick))
            case R_id_nick_start_chat =>
              manager.startQuery(manager.lastChannel.get.server, nick)
          }
          ()
        }
      case _ => // ignore
    }
  }
}
