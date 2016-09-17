package com.hanhuy.android.irc

import android.support.v7.app.AlertDialog
import com.hanhuy.android.irc.model._
import com.hanhuy.android.irc.model.MessageLike._

import android.content.Context
import android.text.TextWatcher
import android.text.Editable
import android.view.{LayoutInflater, ViewGroup, View, KeyEvent}
import android.view.inputmethod.EditorInfo
import android.widget.{TextView, EditText}
import android.text.method.TextKeyListener
import android.util.Log

import com.sorcix.sirc.IrcConnection

import scala.collection.JavaConversions._

import com.hanhuy.android.conversions._
import com.hanhuy.android.common._
import android.app.Activity
import com.hanhuy.android.irc.model.MessageLike.CommandError
import com.hanhuy.android.irc.model.MessageLike.Privmsg
import com.hanhuy.android.irc.model.MessageLike.CtcpAction
import com.hanhuy.android.irc.model.MessageLike.CtcpRequest
import com.hanhuy.android.irc.model.Query

import scala.concurrent.Future
import Futures._

trait Command {
  def execute(args: Option[String])
}
object InputProcessor {
  def clear(input: EditText) = TextKeyListener.clear(input.getText)
  val VOICE_INPUT_METHOD = "com.google.android.googlequicksearchbox/" +
    "com.google.android.voicesearch.ime.VoiceInputMethodService"
}
object HistoryAdapter extends TrayAdapter[String] {
  private val history = RingBuffer[String](128)

  def +=(line: String) = {
    history += line
    notifyDataSetChanged()
  }

  override def size = history.size

  override def emptyItem = R.string.no_history

  override def onGetView(position: Int, convertView: View, parent: ViewGroup) = {
    convertView.?.fold {
      val view = LayoutInflater.from(parent.getContext).inflate(
        android.R.layout.simple_list_item_1, parent, false)
      view.asInstanceOf[TextView].setText(history(position))
      view
    } { view =>
      view.asInstanceOf[TextView].setText(history(position))
      view
    }
  }

  override def itemId(position: Int) = history(position).hashCode

  override def getItem(position: Int): Option[String] =
    if (size == 0) None else history(position).?
}

abstract class InputProcessor(activity: Activity) {
  val manager = IrcManager.init()
  import InputProcessor._
  val TAG = "InputProcessor"

  val processor = CommandProcessor(activity)

  def currentState: (Option[Server],Option[ChannelLike])

  def onEditorActionListener(v: View, action: Int, e: KeyEvent): Boolean = {
    val input = v.asInstanceOf[EditText]
    if (action == EditorInfo.IME_ACTION_SEND)
      false // ignored for now
    else if (action == EditorInfo.IME_NULL) {
      val line = input.getText.toString
      handleLine(line)
      clear(input)
      val hideKeyboard = Settings.get(Settings.HIDE_KEYBOARD)
      if (hideKeyboard) {
        hideIME()(activity)
      }
      !hideKeyboard
    } else
      false
  }

  def handleLine(line: String) {
    currentState match {
      case (s, c) => processor.server = s; processor.channel = c
    }
    processor.executeLine(line)
    if (line.trim.nonEmpty) {
      HistoryAdapter += line
    }
  }

  object TextListener extends TextWatcher {
    override def afterTextChanged(s: Editable) = ()
    override def beforeTextChanged(s: CharSequence,
        start: Int, count: Int, after: Int) = ()
    override def onTextChanged(cs: CharSequence,
        start: Int, before: Int, count: Int) {

      import android.provider.{Settings => ASettings}
      val s = cs.toString
      val currentIME = ASettings.Secure.getString(
        activity.getContentResolver, ASettings.Secure.DEFAULT_INPUT_METHOD)
      val voiceIME = VOICE_INPUT_METHOD == currentIME
      if (s.contains("\n")) {
        handleLine(s.replace("\n", " "))
        cs match { case e: Editable => e.clear() }
      } else {
        activity match {
          case a: MainActivity =>
            a.setSendVisible(s.length > 0 && (!a.imeShowing || voiceIME))
          case _ =>
        }
        if (start != 0 || (count != s.length && count != 0)) {
          completionPrefix = None
          completionOffset = None
        }
      }
    }
  }

  var completionPrefix: Option[String] = None
  var completionOffset: Option[Int]    = None
  final def nickComplete(input: EditText) {
    val (_, channel) = currentState
    channel foreach { c =>

      val caret = input.getSelectionStart
      val in = input.getText.toString
      // completion logic:  starts off with recents first, then alphabet
      // match a prefix, lowercased
      //   (store the prefix and start index if not set)
      //   verify prefix still matches if set (user input on soft kb)
      //     if the prefix no longer matches, reset
      // compare against recent, then sorted (iterate through)
      // fill in the entire word, add a comma if at beginning of line
      //   word should come from users (convert to proper case)
      // if called again, replace previous result with the next result
      //   lowercase the word first
      //   compare current word against recent and sorted to find index
      //   skip current nick if found again
      // replace everytime called unless prefix and start index are unset
      // prefix and start index get unset by key events or page change
      // do nothing if no match
      //   fell off the end? revert to prefix/start over
      val prefix = completionPrefix getOrElse {
        val start = input.getSelectionStart
        val beginning = math.max(in.lastIndexOf(" ", start - 1), in.lastIndexOf("@", start - 1))
        val p = in.substring(if (beginning == -1) 0 else beginning + 1, start)
        completionPrefix = Some(p)
        completionOffset = Some(start - p.length())
        p
      }
      // TODO make ", " a preference
      val suffix = ", "
      val offset = completionOffset.get
      val lowerp = prefix.toLowerCase

      if (in.length() < offset || offset > caret || !in.substring(
        offset).toLowerCase.startsWith(lowerp)) {
        completionPrefix = None
        completionOffset = None
        nickComplete(input)
      } else {
        val selected = in.substring(offset, caret)
        val current = (if (selected.endsWith(suffix))
          selected.substring(0, selected.length() - suffix.length())
        else selected).toLowerCase

        val ch = manager.channels(c)
        val users = ch.getUsers.map {
          u => (u.getNick.toLowerCase, u.getNick)
        }.toMap

        val recent = c.messages.messages.reverse.collect {
          case ChatMessage(s, m) => s.toLowerCase
        }.distinct.toList
        val names = (recent ++ users.keys.toList.sorted.filterNot(
          recent.toSet)) filterNot { n => n == "***" || n == c.server.currentNick }

        def goodCandidate(x: String) = x.startsWith(lowerp) && users.contains(x)
        val candidate: Option[String] = if (current == lowerp) {
          val i = names.indexWhere(goodCandidate)
          if (i != -1) Some(users(names(i))) else None
        } else {
          val i = names.indexWhere(_.equals(current))
          if (i != -1 && i < names.size - 1) {
            val n = names.indexWhere(goodCandidate, i + 1)
            if (n != -1) Some(users(names(n))) else None
          } else None
        }

        val replacement = candidate.fold(prefix: CharSequence) { nick =>
          "%1%2" formatSpans(MessageAdapter.colorNick(nick), if (offset <= 1) suffix else "")
        }
        // replace offset to cursor with replacement
        // move cursor to end of replacement
        val out = "%1%2%3" formatSpans(in.substring(0, offset), replacement, in.substring(caret))
        input.setText(out)
        input.setSelection(offset + replacement.length())
      }
    }
  }
}

case class SimpleInputProcessor(activity: Activity, appender: MessageAppender)
  extends InputProcessor(activity) {
  def currentState = appender match {
    case s: Server => (Some(s), None)
    case c: ChannelLike => (Some(c.server), Some(c))
  }
  def onKeyListener(v: View, k: Int, e: KeyEvent): Boolean = {
    if (KeyEvent.KEYCODE_SEARCH != k && KeyEvent.KEYCODE_TAB != k) {
      completionPrefix = None
      completionOffset = None
    }
    false
  }
}

case class MainInputProcessor(activity: MainActivity)
extends InputProcessor(activity) {

  def currentState = activity.adapter.getItem(activity.adapter.page) match {
    case s: ServersFragment => (s.server, None)
    case s: ServerMessagesFragment => (s.server, None)
    case c: ChannelFragment => (Some(c.channel.get.server), Some(c.channel.get))
    case q: QueryFragment => (Some(q.query.get.server), Some(q.query.get))
    case _ => (None, None)
  }

  def move(forward: Boolean) {
    val count = activity.adapter.getCount()
    val current = activity.pager.getCurrentItem
    val next = if (forward) {
      if (current + 1 >= count) 0 else current + 1
    } else {
      if (current - 1 < 0) count - 1 else current - 1
    }
    activity.pager.setCurrentItem(next)
  }
  def onKeyListener(v: View, k: Int, e: KeyEvent): Boolean = {
    if (KeyEvent.KEYCODE_SEARCH != k && KeyEvent.KEYCODE_TAB != k) {
      completionPrefix = None
      completionOffset = None
    }
    // keyboard shortcuts / honeycomb and above only
    if (KeyEvent.ACTION_DOWN == e.getAction && k == KeyEvent.KEYCODE_TAB)
      nickComplete(activity.input)
    if (KeyEvent.ACTION_UP == e.getAction) {
      val meta = e.getMetaState
      val altOn   = (meta & KeyEvent.META_ALT_ON)   > 0
      val ctrlOn  = (meta & KeyEvent.META_CTRL_ON)  > 0
      val shiftOn = (meta & KeyEvent.META_SHIFT_ON) > 0
      val (handled, target) = k match {
        case KeyEvent.KEYCODE_TAB =>
          if (ctrlOn && shiftOn) { // move backward in tabs
            move(false)
          } else if (ctrlOn) {
            move(true)
          }
          (true.?,None)
        case KeyEvent.KEYCODE_DPAD_RIGHT =>
          if (altOn) move(true)
          (altOn.?,None)
        case KeyEvent.KEYCODE_DPAD_LEFT =>
          if (altOn) move(false)
          (altOn.?,None)
        case KeyEvent.KEYCODE_1 => (None,  1.?)
        case KeyEvent.KEYCODE_2 => (None,  2.?)
        case KeyEvent.KEYCODE_3 => (None,  3.?)
        case KeyEvent.KEYCODE_4 => (None,  4.?)
        case KeyEvent.KEYCODE_5 => (None,  5.?)
        case KeyEvent.KEYCODE_6 => (None,  6.?)
        case KeyEvent.KEYCODE_7 => (None,  7.?)
        case KeyEvent.KEYCODE_8 => (None,  8.?)
        case KeyEvent.KEYCODE_9 => (None,  9.?)
        case KeyEvent.KEYCODE_0 => (None, 10.?)
        case KeyEvent.KEYCODE_Q => (None, 11.?)
        case KeyEvent.KEYCODE_W => (None, 12.?)
        case KeyEvent.KEYCODE_E => (None, 13.?)
        case KeyEvent.KEYCODE_R => (None, 14.?)
        case KeyEvent.KEYCODE_T => (None, 15.?)
        case KeyEvent.KEYCODE_Y => (None, 16.?)
        case KeyEvent.KEYCODE_U => (None, 17.?)
        case KeyEvent.KEYCODE_I => (None, 18.?)
        case KeyEvent.KEYCODE_O => (None, 19.?)
        case KeyEvent.KEYCODE_P => (None, 20.?)
        case _ => (None,None)
      }
      handled.getOrElse {
        target.fold(false) { page =>
          if (altOn) {
            activity.pager.setCurrentItem(page)
          }
          altOn
        }
      }
    } else
      false
  }
}

// set ctx, server and channel prior to invoking executeLine
case class CommandProcessor(ctx: Context) {
  val TAG = "CommandProcessor"

  def getString(res: Int, args: String*) = ctx.getString(res, args: _*)
  var channel: Option[ChannelLike] = None
  var server: Option[Server] = None

  lazy val manager = IrcManager.init()

  lazy val activity = ctx match {
    case a: MainActivity => a.?
    case _ => None
  }

  // accept a nullable string in case of input that isn't pre-cleaned
  def executeLine(line: String) {
    if (line.? exists (_.nonEmpty)) {
      if (line.charAt(0) == '/') {
        val idx = line.indexOf(" ")
        val cmd = line.substring(1)
        val (cmd2, args) = if (idx != -1) {
          val a = line.substring(idx + 1)
          (line.substring(1, idx), if (a.trim().length() == 0) None else Some(a))
        } else (cmd, None)
        val (cmd3, args2) = if (cmd2.length() == 0 || cmd2.charAt(0) == ' ') {
          val a = if (line.length() > 2) line.substring(2) else ""
          (getString(R.string.command_quote), if (a.trim().length() == 0) None else Some(a))
        } else (cmd2, args)
        executeCommand(cmd3.toLowerCase, args2)
      } else {
        sendMessage(Some(line))
      }
    }
  }

  def addCommandError(error: Int): Unit = addCommandError(getString(error))

  def addCommandError(error: String) {
    (channel orElse server).fold (
      Log.w(TAG, "Unable to addCommandError, no server or channel: " + error): Any
    )(_ += CommandError(error))
  }

  def sendMessage(line: Option[String], action: Boolean = false) {
    line foreach { l =>

      channel match {
        case Some(ch: Channel) => manager.channels.get(ch).foreach { chan =>
          if (ch.server.state.now != Server.CONNECTED) {
            addCommandError(R.string.error_server_disconnected)
          } else if (ch.state != Channel.State.JOINED) {
            addCommandError(R.string.error_channel_disconnected)
          } else {
            if (action) {
              ch += CtcpAction(ch.server.currentNick, l)
              chan.sendAction(l)
            } else {
              chan.getUs.?.fold(
                ch += Privmsg(ch.server.currentNick, l)
              ){ u => ch += Privmsg(ch.server.currentNick, l,
                  u.hasOperator, u.hasVoice)
              }
              chan.sendMessage(l)
            }
          }
        }
        case Some(query: Query)=>
          if (query.server.state.now != Server.CONNECTED) {
            addCommandError(R.string.error_server_disconnected)
          } else {
            manager.connections.get(query.server).fold(
              addCommandError("No connection found for this session")
            ){ conn =>
              val user = conn.createUser(query.name)
              if (conn.isConnected) {
                if (action) {
                  query += CtcpAction(query.server.currentNick, l)
                  user.sendAction(l)
                } else {
                  query += Privmsg(query.server.currentNick, l)
                  user.sendMessage(l)
                }
              } else {
                addCommandError(R.string.not_connected)
              }
            }
          }
        case _ => addCommandError(R.string.error_no_channel)
      }
    }
  }

  def sendAction(line: Option[String]) = sendMessage(line, true)

  def executeCommand(cmd: String, args: Option[String]) {
    commands.get(cmd).fold(
      addCommandError(getString(R.string.error_command_unknown, cmd))
    )(_.execute(args))
  }

  def currentServer = channel map { _.server } orElse server

  object JoinCommand extends Command {
    override def execute(args: Option[String]) {
      args.fold(addCommandError(R.string.usage_join)){ chan =>
        val idx = chan.indexOf(" ")
        val (password, chan2) = if (idx != -1) {
          (Some(chan.substring(idx + 1)), chan.substring(0, idx))
        } else {
          (None, chan)
        }
        if (chan2.length == 0) {
          addCommandError(R.string.usage_join)
        } else {
          val first = chan.charAt(0)
          val chan3 = if (first != '#' && first != '&') "#" + chan else chan

          withConnection { conn =>
            if (conn.isConnected) {
              val c = conn.createChannel(chan3)
              password.fold(c.join())(p => c.join(p))
            } else {
              addCommandError("Not connected")
            }
          }
        }
      }
    }
  }

  object PartCommand extends Command {
    override def execute(args: Option[String]): Unit = {
      channel match {
        case Some(c: Channel) =>
          if (c.server.state.now != Server.CONNECTED)
            addCommandError(R.string.error_server_disconnected)
          else if (c.state != Channel.State.JOINED)
            addCommandError(R.string.error_channel_disconnected)
          else activity.foreach { act =>

            // TODO refactor with stuff in fragments
            val prompt = Settings.get(Settings.CLOSE_TAB_PROMPT)
            def removeChannel() {
              if (c.state == Channel.State.JOINED) {
                manager.channels.get(c) foreach {
                  _.part()
                }
              }
              manager.remove(c)
              act.adapter.removeTab(act.adapter.getItemPosition(this))
            }
            if (c.state == Channel.State.JOINED && prompt) {
              val builder = new AlertDialog.Builder(act)
              builder.setTitle(R.string.channel_close_confirm_title)
              builder.setMessage(getString(R.string.channel_close_confirm))
              builder.setPositiveButton(R.string.yes, () => {
                removeChannel()
                c.state = Channel.State.PARTED
              })
              builder.setNegativeButton(R.string.no, null)
              builder.create().show()
            } else {
              removeChannel()
            }
          }
        case Some(qu: Query) =>
          if (qu.server.state.now != Server.CONNECTED)
            addCommandError(R.string.error_server_disconnected)
          else activity.foreach { act =>
            val prompt = Settings.get(Settings.CLOSE_TAB_PROMPT)
            def removeQuery() {
              manager.remove(qu)
              act.adapter.removeTab(act.adapter.getItemPosition(this))
            }
            if (prompt) {
              val builder = new AlertDialog.Builder(act)
              builder.setTitle(R.string.query_close_confirm_title)
              builder.setMessage(getString(R.string.query_close_confirm))
              builder.setPositiveButton(R.string.yes, removeQuery _)
              builder.setNegativeButton(R.string.no, null)
              builder.create().show()
            } else
              removeQuery()
          }
        case Some(_) =>
          addCommandError("Not currently on a channel or query, cannot leave")
        case _ => addCommandError(R.string.error_no_channel)
      }
    }
  }

  object QuitCommand extends Command {
    override def execute(args: Option[String]) = MainActivity.instance foreach {
      _.exit(args)
    }
  }

  object QuoteCommand extends Command {
    override def execute(args: Option[String]) = sendMessage(args)
  }

  def messageCommandSend(args: Option[String], notice: Boolean = false) {
    val CommandPattern = """(\S+)\s(.*)""".r
    val usage = if (notice) R.string.usage_notice else R.string.usage_msg

    args collect {
      case CommandPattern(target, line) =>
      if (line.trim().length() == 0) {
        addCommandError(usage)
      } else {
        withConnection { c =>
          target.charAt(0) match {
            case '&' | '#' => () // do nothing if it's a channel
            case _ => manager.addQuery(c, target, line, sending = true)
          }
          val user = c.createUser(target)
          if (notice) user.sendNotice(line) else user.sendMessage(line)
        }
      }
    } getOrElse addCommandError(usage)
  }

  def withConnection(f: IrcConnection => Unit) {
    currentServer.fold(addCommandError(R.string.error_server_disconnected)){ s =>
      if (s.state.now != Server.CONNECTED)
        addCommandError(R.string.error_server_disconnected)
      else
        manager.connections.get(s) foreach f
    }
  }

  def TODO = addCommandError("This command has not been implemented yet")

  object MessageCommand extends Command {
    override def execute(args: Option[String]) = messageCommandSend(args)
  }

  object NoticeCommand extends Command {
    override def execute(args: Option[String]) = messageCommandSend(args, true)
  }

  object ActionCommand extends Command {
    override def execute(args: Option[String]) = sendAction(args)
  }

  object PingCommand extends Command {
    val CommandPattern = """\s*(\S+)\s*""".r
    override def execute(args: Option[String]) {
      args collect {
        case CommandPattern(target) =>
          val now = System.currentTimeMillis
          // irssi seems to use microseconds for the second part, emulate
          CtcpCommand.execute(
            Some("%s PING %d %d000" format(target, now / 1000, now & 999)))
      } getOrElse addCommandError(R.string.usage_ping)
    }
  }

  object TopicCommand extends Command {
    override def execute(args: Option[String]) {
      channel match {
        case Some(c: Channel) => args.fold(
          c.topic foreach (t => c += t.copy(forceShow = true)))(_ => TODO)
        case _ => TODO
      }
    }
  }

  object InviteCommand extends Command {
    override def execute(args: Option[String]) = TODO
  }

  object CtcpCommand extends Command {
    val CommandPattern = """(\S+)\s+(\S+)\s*(.*?)\s*""".r
    override def execute(args: Option[String]) {
      args collect {
        case CommandPattern(target, command, arg) => withConnection { c =>
          val trimmedArg = arg.trim
          val line = command.toUpperCase + (
            if (trimmedArg.length == 0) "" else " " + trimmedArg)

          val r = CtcpRequest(manager._connections(c),
             target, command.toUpperCase, trimmedArg.?.find(_.nonEmpty))

          // show in currently visible tab or the server's message tab
          // if not currently on a message tab
          (channel orElse server).fold(currentServer foreach { _ += r })(_ += r)
          c.createUser(target).sendCtcp(line)
        }
      } getOrElse addCommandError(R.string.usage_ctcp)
    }
  }

  object NickCommand extends Command {
    override def execute(args: Option[String]) {
      args.fold(addCommandError(R.string.usage_nick)){ newnick =>
        withConnection (conn => Future { conn.setNick(newnick) })
      }
    }
  }

  object KickCommand extends Command {
    override def execute(args: Option[String]) = TODO
  }

  object WhowasCommand extends Command {
    override def execute(args: Option[String]) {
      args foreach { line =>
        RawCommand.execute(Some("WHOWAS " + line))
      }
    }
  }

  object WhoisCommand extends Command {
    override def execute(args: Option[String]) {
      args foreach { line =>
        RawCommand.execute(Some("WHOIS " + line))
      }
    }
  }

  object IgnoreCommand extends Command {
    override def execute(args: Option[String]) = {
      args.fold {
        if (Config.Ignores.isEmpty)
          addCommandError(R.string.error_ignores_empty)
        else {
          addCommandError(getString(R.string.ignore_list_prefix,
            Config.Ignores map ( "  " + _ ) mkString "\n"))
        }
      } { toIgnore =>
        toIgnore.trim.split("\\s+") foreach { i =>
          Config.Ignores += i

          addCommandError(getString(R.string.ignore_add_prefix, i))
        }
      }
    }
  }

  object UnignoreCommand extends Command {
    override def execute(args: Option[String]) = {
      args.fold(addCommandError(R.string.usage_unignore)){ toUnignore =>
        toUnignore.trim.split("\\s+") foreach { i =>
          Config.Ignores -= i

          addCommandError(getString(R.string.ignore_remove_prefix, i))
        }
      }
    }
  }

  object ClearCommand extends Command {
    override def execute(args: Option[String]): Unit = {
      server.orElse(channel).fold(addCommandError(R.string.error_no_channel))(_.clear())
    }
  }

  object RawCommand extends Command {
    override def execute(args: Option[String]) {
      args.fold(addCommandError(R.string.usage_raw)){ line =>
        if (line.trim().length() == 0)
          addCommandError(R.string.usage_raw)
        else
          withConnection { c => c.sendRaw(line) }
      }
    }
  }

  object HelpCommand extends Command {
    override def execute(args: Option[String]) =
      addCommandError(R.string.help_text)
  }

  val commands = Map((getString(R.string.command_ignore),   IgnoreCommand)
              ,(getString(R.string.command_unignore), UnignoreCommand)
              ,(getString(R.string.command_whowas),   WhowasCommand)
              ,(getString(R.string.command_whois_1),  WhoisCommand)
              ,(getString(R.string.command_whois_2),  WhoisCommand)
              ,(getString(R.string.command_join_1),   JoinCommand)
              ,(getString(R.string.command_join_2),   JoinCommand)
              ,(getString(R.string.command_help_1),   HelpCommand)
              ,(getString(R.string.command_help_2),   HelpCommand)
              ,(getString(R.string.command_nick),     NickCommand)
              ,(getString(R.string.command_kick_1),   KickCommand)
              ,(getString(R.string.command_kick_2),   KickCommand)
              ,(getString(R.string.command_leave_1),  PartCommand)
              ,(getString(R.string.command_leave_2),  PartCommand)
              ,(getString(R.string.command_quit),     QuitCommand)
              ,(getString(R.string.command_quote),    QuoteCommand)
              ,(getString(R.string.command_msg_1),    MessageCommand)
              ,(getString(R.string.command_msg_2),    MessageCommand)
              ,(getString(R.string.command_notice),   NoticeCommand)
              ,(getString(R.string.command_action_1), ActionCommand)
              ,(getString(R.string.command_action_2), ActionCommand)
              ,(getString(R.string.command_ping),     PingCommand)
              ,(getString(R.string.command_ctcp),     CtcpCommand)
              ,(getString(R.string.command_topic),    TopicCommand)
              ,(getString(R.string.command_invite),   InviteCommand)
              ,(getString(R.string.command_raw),      RawCommand)
              ,(getString(R.string.command_clear),    ClearCommand)
              )
}
