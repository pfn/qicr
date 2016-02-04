package com.hanhuy.android.irc.model

import android.app.Activity
import android.text.util.Linkify
import android.util.TypedValue
import com.hanhuy.android.irc._

import com.hanhuy.android.common._
import SpannedGenerator._
import MessageLike._
import com.hanhuy.android.irc.model.BusEvent.LinkClickEvent

import scala.ref.WeakReference

import android.graphics.Typeface
import android.view.{Gravity, View, ViewGroup}
import android.content.Context
import android.widget.{AbsListView, BaseAdapter, TextView}

import java.text.SimpleDateFormat

import android.text.style.{ForegroundColorSpan, URLSpan, ClickableSpan}
import android.text.{SpannableString, Spanned, Spannable, TextPaint}
import android.text.method.LinkMovementMethod

import scala.reflect.ClassTag
import scala.util.Try

import iota.{textColor => _, _}

trait MessageAppender {
  def +=(m: MessageLike): Unit
  def clear(): Unit
}

object MessageAdapter extends EventBus.RefOwner {
  lazy val gbfont = Typeface.createFromAsset(
    Application.context.getAssets, "DejaVuSansMono.ttf")
  private var fontSetting = Settings.get(Settings.FONT_NAME).? flatMap (
    n => Try(Typeface.createFromFile(n)).toOption)
  val NICK_COLORS: Array[Int] = Array(
    0xfff44336, 0xffe91e63, 0xff9c27b0, 0xff7E57C2, 0xff5c6bc0, 0xff2196f3,
    0xff03a9f4, 0xff00bcd4, 0xff009688, 0xff4caf50, 0xff8bc34a, 0xffAFB42B,
    0xffF9A825, 0xffffc107, 0xffff9800, 0xffff5722, 0xff8d6e63, 0xff607d8b
  )

  val TAG = "MessageAdapter"
  val DEFAULT_MAXIMUM_SIZE = 256
  private var showTimestamp = false
  private lazy val sdf = new SimpleDateFormat("HH:mm ")

  def formatText(c: Context, msg: MessageLike)
      (implicit channel: ChannelLike = null, nicks: Set[String] = Set.empty): CharSequence = {
    val ch = channel.?
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
        colorNick(n), u, m.?.getOrElse(""))
      case Quit(n, u, m,_) => formatText(c, msg, R.string.quit_template,
        colorNick(n), u, m)
      case Kick(o, n, m,_) => formatText(c, msg, R.string.kick_template,
        colorNick(n), colorNick(o), m.?.getOrElse(""))
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

  def nickColor(n: String) = NICK_COLORS(math.abs(n.hashCode) % NICK_COLORS.length)
  private def gets(c: Context, res: Int, m: MessageLike, src: String,
      msg: String, modes: (Boolean,Boolean) = (false, false))(
      implicit channel: ChannelLike, currentNicks: Set[String]) = {
    val server = channel.? map (_.server)
    val (op, voice) = modes

    val prefix = {if (op) "@" else if (voice) "+" else ""}

    if (channel.isInstanceOf[Query]) {
      if (server exists (_.currentNick equalsIgnoreCase src))
        formatText(c, m, res, textColor(0xff009999, src), msg)
      else
        formatText(c, m, res, textColor(0xffcc0000, src), msg)
    } else if (server exists (_.currentNick equalsIgnoreCase src)) {
      formatText(c, m, res, "%1%2" formatSpans (prefix, bold(src)), highlightNicks(msg))
    } else if (server exists (s => IrcListeners.matchesNick(s, msg) &&
        !s.currentNick.equalsIgnoreCase(src))) {
      formatText(c, m, res, "%1%2" formatSpans(prefix,
        bold(italics(colorNick(src)))), bold(msg))
    } else {
      formatText(c, m, res,
        "%1%2" formatSpans(prefix, colorNick(src)), highlightNicks(msg))
    }
  }

  def highlightNicks(msg: String)(implicit currentNicks: Set[String] = Set.empty) = {
    val message = new SpannableString(msg)
    val words = WORD_REGEX.findAllMatchIn(message).map(m => (m.matched, m.start, m.end))
    if (currentNicks.nonEmpty) {
      words foreach { case (w, i0, i1) =>
        if (currentNicks(w)) {
          message.setSpan(new ForegroundColorSpan(nickColor(w)),
            i0, i1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
          message.setSpan(NickClick(w), i0, i1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
      }
    }
    message
  }

  val LETTER = "a-zA-Z"
  val SPECIAL = """\[\]\\`_^\{\}\|"""
  val DIGIT = "0-9"
  val WORD_REGEX = s"[$LETTER$SPECIAL][$LETTER$DIGIT$SPECIAL-]*".r

  def colorNick(nick: String): CharSequence = {
    val text = textColor(nickColor(nick), nick)
    val inMain = MainActivity.instance.isDefined
    if (nick != "***" && inMain)
      SpannedGenerator.span(NickClick(text.toString), text) else text
  }
  def messageLayout(implicit ctx: Activity) = {
    import ViewGroup.LayoutParams._
    c[AbsListView](w[TextView] >>= id(android.R.id.text1) >>=
      lp(MATCH_PARENT, WRAP_CONTENT) >>=
      kestrel { tv =>
        tv.setLinksClickable(true)
        tv.setTextAppearance(ctx, android.R.style.TextAppearance_Small)
        tv.setTypeface(Typeface.MONOSPACE)
        tv.setGravity(Gravity.CENTER_VERTICAL)
      } >>= padding(left = 6 dp, right = 6 dp))
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
  def showJoinPartQuit = Settings.get(Settings.SHOW_JOIN_PART_QUIT)
  def maximumSize = Settings.maximumMessageLines

  private var filterCache = Option.empty[Seq[MessageLike]]
  def messages = _messages
  private var _messages = RingBuffer[MessageLike](maximumSize)

    // only register once to prevent memory leak
  UiBus += { case BusEvent.PreferenceChanged(s, k) =>
    if (k == Settings.MESSAGE_LINES) {
      _messages = messages.copy(maximumSize)
    } else if (k == Settings.SHOW_JOIN_PART_QUIT) {
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

  var _activity: WeakReference[Activity] = WeakReference.empty
  // can't make this IrcService due to resource changes on recreation
  def context_= (activity: Activity) = {
    activity.?.foreach { c =>
      _activity = new WeakReference(c)
      IrcManager.instance foreach { manager =>
        // It'd be nice to register a ServiceBus listener, but no way
        // to clean up when this adapter goes away?
        // add it to UiBus here maybe?
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
    nickCache = None
    if (isMainThread)
      _activity.get foreach { _ => notifyDataSetChanged() }
  }

  private[this] var nickCache = Option.empty[Set[String]]
  implicit def currentNicks = {
    nickCache getOrElse {
      val nicks = filteredMessages.collect {
        case Privmsg(sender, _, _, _, _) => sender
      }.toSet
      nickCache = Some(nicks)
      nicks
    }
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
    val c = convertView.?.find(_.getContext == context).map(_.asInstanceOf[TextView])
    val view = c.getOrElse {
      val v = messageLayout(_activity.get.get).perform()

      if (!icsAndNewer)
        v.setTypeface(gbfont)

      v.setMovementMethod(LinkMovementMethod.getInstance)
      v
    }

    if (size > 0) view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
    fontSetting foreach view.setTypeface

    val spanned = formatText(getItem(pos)) match {
      case s: Spannable => s
      case s => new SpannableString(s.?.getOrElse(""))
    }
    Linkify.addLinks(spanned,
      Linkify.WEB_URLS | Linkify.EMAIL_ADDRESSES | Linkify.MAP_ADDRESSES)
    val links = spanned.getSpans(0, spanned.length - 1, classOf[URLSpan])
    links foreach { u =>
      val url = u.getURL
      if (url.startsWith("http")) {
        val start = spanned.getSpanStart(u)
        val end = spanned.getSpanEnd(u)
        import com.hanhuy.android.conversions._
        val clickSpan: ClickableSpan = () => {
          UiBus.send(LinkClickEvent(url))
        }
        spanned.removeSpan(u)
        spanned.setSpan(clickSpan, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
      }
    }
    view.setText(spanned)
    view
  }

  override def notifyDataSetChanged() {
    filterCache = None
    nickCache = None
    super.notifyDataSetChanged()
  }

  private def formatText(msg: MessageLike) =
    MessageAdapter.formatText(context, msg)

}

case class RingBuffer[A: ClassTag](capacity: Int) extends IndexedSeq[A] {
  private val buffer = Array.ofDim[A](capacity)
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
          item.getItemId match {
            case R.id.channel_log =>
              a.startActivity(MessageLogActivity.createIntent(manager.lastChannel.get, nick))
              a.overridePendingTransition(
                R.anim.slide_in_left, R.anim.slide_out_right)
            case R.id.nick_whois =>
              val proc = CommandProcessor(a, null)
              proc.channel = manager.lastChannel
              proc.WhoisCommand.execute(Some(nick))
            case R.id.nick_ignore => ()
              val proc = CommandProcessor(a, null)
              proc.channel = manager.lastChannel
              if (Config.Ignores(nick))
                proc.UnignoreCommand.execute(Some(nick))
              else
                proc.IgnoreCommand.execute(Some(nick))
            case R.id.nick_start_chat =>
              manager.lastChannel.foreach(c => manager.startQuery(c.server, nick))
          }
          ()
        }
      case _ => // ignore
    }
  }
}
