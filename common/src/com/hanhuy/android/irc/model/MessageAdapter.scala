package com.hanhuy.android.irc.model

import com.hanhuy.android.irc._
import com.hanhuy.android.irc.AndroidConversions._

import SpannedGenerator._
import MessageLike._
import TypedResource._

import scala.ref.WeakReference

import android.graphics.Typeface
import android.view.LayoutInflater
import android.content.Context
import android.widget.BaseAdapter
import android.widget.TextView
import android.view.{View, ViewGroup}

import java.text.SimpleDateFormat

import MessageAdapter._
import android.text.style.ClickableSpan
import android.text.TextPaint
import android.text.method.LinkMovementMethod

trait MessageAppender {
  def add(m: MessageLike): Unit
}

object MessageAdapter {
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
      case Whois(text) => text
      case Query() => formatText(c, msg, R.string.query_template,
        colorNick(ch.get.name))
      case Privmsg(s, m, o, v) => gets(c, R.string.message_template, msg,
         s, m, (o,v))
      case Notice(s, m) => gets(c, R.string.notice_template, msg, s, m)
      case CtcpAction(s, m) => gets(c, R.string.action_template, msg, s, m)
      case CtcpRequest(server, t, cmd, args) =>
        ch flatMap { chan =>
          if (chan.server != server)
            Some(getString(c, R.string.ctcp_request_template_s,
              t, cmd, args getOrElse "", server.name))
          else
            None
        } getOrElse {
          getString(c, R.string.ctcp_request_template,
            t, cmd, args getOrElse "")
        }
      case CtcpReply(server, s, cmd, a) => a map { arg =>
        ch flatMap { chan =>
          if (chan.server != server)
            Some(getString(c, R.string.ctcp_response_template_s_3,
              cmd, s, arg, server.name))
          else
            None
        } getOrElse {
          getString(c, R.string.ctcp_response_template_3, cmd, s, arg)
        }
      } getOrElse {
        ch flatMap { chan =>
          if (chan.server != server)
            Some(getString(c, R.string.ctcp_response_template_s_2,
              cmd, s, server.name))
          else
            None
        } getOrElse {
          getString(c, R.string.ctcp_response_template_2, cmd, s)
        }
      }
      case Topic(src, t) => src map { s =>
        formatText(c, msg, R.string.topic_template_2,
          colorNick(s), bold(italics(channel.name)), t)
      } getOrElse {
        formatText(c, msg, R.string.topic_template_1,
          bold(italics(channel.name)), t)
      }
      case NickChange(o, n) =>
        formatText(c, msg, R.string.nick_change_template,
          colorNick(o), colorNick(n))
      case Join(n, u)    => formatText(c, msg, R.string.join_template,
        colorNick(n), u)
      case Part(n, u, m) => formatText(c, msg, R.string.part_template,
        colorNick(n), u, if (m == null) "" else m)
      case Quit(n, u, m) => formatText(c, msg, R.string.quit_template,
        colorNick(n), u, m)
      case Kick(o, n, m) => formatText(c, msg, R.string.kick_template,
        colorNick(o), colorNick(n),
        if (m == null) "" else m)
      case CommandError(m)  => formatText(c, msg, -1, m)
      case ServerInfo(m)    => formatText(c, msg, -1, m)
      case Motd(m)          => formatText(c, msg, -1, m)
      case SslInfo(m)       => formatText(c, msg, -1, m)
      case SslError(m)      => formatText(c, msg, -1, m)
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
    val server = channel.server
    val (op, voice) = modes

    val prefix = {if (op) "@" else if (voice) "+" else ""}

    if (channel.isInstanceOf[Query]) {
      if (server.currentNick equalsIgnoreCase src)
        formatText(c, m, res, textColor(0xff009999, src), msg)
      else
        formatText(c, m, res, textColor(0xffcc0000, src), msg)
    } else if (server.currentNick equalsIgnoreCase src) {
      formatText(c, m, res, "%1%2" formatSpans (prefix, bold(src)), msg)
    } else if (IrcListeners.matchesNick(server, msg) &&
        !server.currentNick.equalsIgnoreCase(src)) {
      formatText(c, m, res, "%1%2" formatSpans(prefix,
        bold(italics(colorNick(src)))), bold(msg))
    } else
      formatText(c, m, res,
        "%1%2" formatSpans(prefix, colorNick(src)), msg)
  }
  private case class NickClick(nick: String) extends ClickableSpan {
    override def updateDrawState(ds: TextPaint) = ()

    def onClick(v: View) {
      v.getContext match {
        case a: MainActivity =>
          def insertNick() {
            val cursor = a.input.getSelectionStart
            // TODO make ", " a preference
            a.input.getText.insert(cursor,
              nick + (if (cursor == 0) ", " else " "))
          }
          // TODO refactor this callback
          HoneycombSupport.startNickActionMode(nick) { item =>
            val R_id_nick_insert = R.id.nick_insert
            val R_id_nick_start_chat = R.id.nick_start_chat
            val R_id_nick_whois = R.id.nick_whois
            item.getItemId match {
              case R_id_nick_whois =>
                val proc = CommandProcessor(a)
                proc.channel = a.service.lastChannel
                proc.WhoisCommand.execute(Some(nick))
              case R_id_nick_insert => insertNick()
              case R_id_nick_start_chat =>
                a.service.startQuery(a.service.lastChannel.get.server, nick)
            }
            ()
          }
        case _ => // ignore
      }
    }
  }

  def colorNick(nick: String): CharSequence = {
    val text = textColor(nickColor(nick), nick)
    if (nick == "***") text else
      SpannedGenerator.span(NickClick(text), text)
  }
}

class MessageAdapter extends BaseAdapter with EventBus.RefOwner {
  implicit var channel: ChannelLike = _

  private var filterCache = Option.empty[Seq[MessageLike]]

  var showJoinPartQuit = false
  val messages = new collection.mutable.Queue[MessageLike]
  var _maximumSize = DEFAULT_MAXIMUM_SIZE
  def maximumSize = _maximumSize
  def maximumSize_=(s: Int) = {
      _maximumSize = s
      ensureSize()
  }
    // only register once to prevent memory leak
  UiBus += { case BusEvent.PreferenceChanged(s, k) =>
    if (k == Settings.MESSAGE_LINES) {
      val max = s.get(Settings.MESSAGE_LINES).toInt
      maximumSize = max
    } else if (k == Settings.SHOW_JOIN_PART_QUIT) {
      showJoinPartQuit = s.get(Settings.SHOW_JOIN_PART_QUIT)
      notifyDataSetChanged()
    } else if (k == Settings.SHOW_TIMESTAMP) {
      MessageAdapter.showTimestamp = s.get(Settings.SHOW_TIMESTAMP)
      notifyDataSetChanged()
    }
  }

  var _inflater: WeakReference[LayoutInflater] = _
  def inflater = _inflater.get getOrElse { throw new IllegalStateException }
  var _activity: WeakReference[MainActivity] = _
  // can't make this IrcService due to resource changes on recreation
  def activity_= (c: MainActivity) = {
    if (c != null) {
      _activity = new WeakReference(c)
      _inflater = new WeakReference(c.systemService[LayoutInflater])
      if (c.service != null) {
        val s = c.service.settings
        // It'd be nice to register a ServiceBus listener, but no way
        // to clean up when this adapter goes away?
        // add it to UiBus here maybe?
        maximumSize = s.get(Settings.MESSAGE_LINES).toInt
        showJoinPartQuit = s.get(Settings.SHOW_JOIN_PART_QUIT)
        MessageAdapter.showTimestamp = s.get(Settings.SHOW_TIMESTAMP)
      }
    }
  }

  def activity = _activity.get getOrElse { throw new IllegalStateException }
  // would be nice to move this into the companion
  lazy val font =
    Typeface.createFromAsset(activity.getAssets, "DejaVuSansMono.ttf")

  def clear() {
    messages.clear()
    notifyDataSetChanged()
  }

  private def ensureSize() {
    while (messages.size > _maximumSize && !messages.isEmpty)
      messages.dequeue()
  }

  protected[model] def add(item: MessageLike) {
    messages += item
    ensureSize()
    if (_activity != null && isMainThread)
      _activity.get.foreach { _ => notifyDataSetChanged() }
  }

  def filteredMessages = {
    if (showJoinPartQuit)
      messages
    else {
      filterCache getOrElse {
        val filtered = messages.filter {
          case Join(_,_)   => false
          case Part(_,_,_) => false
          case Quit(_,_,_) => false
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

  override def getView(pos: Int, convertView: View, container: ViewGroup) =
    createViewFromResource(pos, convertView, container)
  private def createViewFromResource(
      pos: Int, convertView: View, container: ViewGroup): View = {
    val c = convertView.asInstanceOf[TextView]
    val view = if (c != null) c else {
      val v = inflater.inflate(TR.layout.message_item, container, false)
      if (!icsAndNewer)
        v.setTypeface(font)
      v.setMovementMethod(LinkMovementMethod.getInstance)
      v
    }

    view.setText(formatText(getItem(pos)))
    view
  }

  override def notifyDataSetChanged() {
    filterCache = None
    super.notifyDataSetChanged()
  }

  private def formatText(msg: MessageLike) =
    MessageAdapter.formatText(activity, msg)

}
