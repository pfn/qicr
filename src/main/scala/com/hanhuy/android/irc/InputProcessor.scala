package com.hanhuy.android.irc

import com.hanhuy.android.irc.model._
import com.hanhuy.android.irc.model.MessageLike._

import android.content.Context
import android.text.TextWatcher
import android.text.Editable
import android.view.View
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.text.method.TextKeyListener
import android.util.Log

import com.sorcix.sirc.IrcConnection

import scala.collection.JavaConversions._

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
abstract class InputProcessor(activity: Activity) {
  val manager = IrcManager.start()
  import InputProcessor._
  val TAG = "InputProcessor"

  val processor = CommandProcessor(activity, this)

  def currentState: (Option[Server],Option[ChannelLike])

  def onEditorActionListener(v: View, action: Int, e: KeyEvent): Boolean = {
    val input = v.asInstanceOf[EditText]
    if (action == EditorInfo.IME_ACTION_SEND)
      false // ignored for now
    else if (action == EditorInfo.IME_NULL) {
      val line = input.getText.toString
      handleLine(line)
      clear(input)
      !Settings.get(Settings.HIDE_KEYBOARD)
    } else
      false
  }

  def handleLine(line: String) {
    currentState match {
      case (s, c) => processor.server = s; processor.channel = c
    }
    processor.executeLine(line)
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
  def nickComplete(input: EditText) {
    val (_, channel) = currentState
    val c = channel match {
      case Some(c: Channel) => c
      case _ => return
    }

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
      val beginning = in.lastIndexOf(" ", start - 1)
      val p = in.substring(if (beginning == -1) 0 else (beginning + 1), start)
      completionPrefix = Some(p)
      completionOffset = Some(start - p.length())
      p
    }
    // TODO make ", " a preference
    val suffix = ", "
    val offset = completionOffset.get
    val lowerp  = prefix.toLowerCase

    if (in.length() < offset || !in.substring(
        offset).toLowerCase.startsWith(lowerp)) {
      completionPrefix = None
      completionOffset = None
      return nickComplete(input)
    }
    var current = in.substring(offset, caret)
    if (current.endsWith(suffix))
      current = current.substring(0, current.length() - suffix.length())
    current = current.toLowerCase

    val ch = manager.channels(c)
    val users = ch.getUsers.map {
      u => ( u.getNick.toLowerCase, u.getNick )
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

    val replacement = candidate map { nick =>
      nick + (if (offset == 0) suffix else "")
    } getOrElse prefix
    // replace offset to cursor with replacement
    // move cursor to end of replacement
    val out = in.substring(0, offset) + replacement + in.substring(caret)
    input.setText(out)
    input.setSelection(offset + replacement.length())
  }
}

case class SimpleInputProcessor(activity: Activity, appender: MessageAppender)
  extends InputProcessor(activity) {
  def currentState = appender match {
    case s: Server => (Some(s), None)
    case c: ChannelLike => (Some(c.server), Some(c))
  }
  def onKeyListener(v: View, k: Int, e: KeyEvent): Boolean = {
    if (KeyEvent.KEYCODE_SEARCH != k) {
      completionPrefix = None
      completionOffset = None
    }
    false
  }
}

case class MainInputProcessor(activity: MainActivity)
extends InputProcessor(activity) {

  def currentState = activity.adapter.getItem(activity.adapter.page) match {
    case s: ServersFragment => (s._server, None)
    case c: ChannelFragment => (Some(c.channel.get.server), Some(c.channel.get))
    case q: QueryFragment => (Some(q.query.get.server), Some(q.query.get))
    case _ => (None, None)
  }
  def onKeyListener(v: View, k: Int, e: KeyEvent): Boolean = {
    // keyboard shortcuts / honeycomb and above only
    if (KeyEvent.ACTION_UP == e.getAction) {
      val meta = e.getMetaState
      val altOn   = (meta & KeyEvent.META_ALT_ON)   > 0
      val ctrlOn  = (meta & KeyEvent.META_CTRL_ON)  > 0
      val shiftOn = (meta & KeyEvent.META_SHIFT_ON) > 0
      var pageTarget = -1

      k match {
        case KeyEvent.KEYCODE_TAB => {
          if (ctrlOn && shiftOn) { // move backward in tabs
          val count = activity.adapter.getCount()
            val current = activity.pager.getCurrentItem
            val next = if (current - 1 < 0) count - 1 else current - 1
            activity.pager.setCurrentItem(next)
          } else if (ctrlOn) { // move forward in tabs
          val count = activity.adapter.getCount()
            val current = activity.pager.getCurrentItem
            val next = if (current + 1 >= count) 0 else current + 1
            activity.pager.setCurrentItem(next)
          } else { // tab completion
          }
          return true
        }
        case KeyEvent.KEYCODE_1 => pageTarget =  1
        case KeyEvent.KEYCODE_2 => pageTarget =  2
        case KeyEvent.KEYCODE_3 => pageTarget =  3
        case KeyEvent.KEYCODE_4 => pageTarget =  4
        case KeyEvent.KEYCODE_5 => pageTarget =  5
        case KeyEvent.KEYCODE_6 => pageTarget =  6
        case KeyEvent.KEYCODE_7 => pageTarget =  7
        case KeyEvent.KEYCODE_8 => pageTarget =  8
        case KeyEvent.KEYCODE_9 => pageTarget =  9
        case KeyEvent.KEYCODE_0 => pageTarget = 10
        case KeyEvent.KEYCODE_Q => pageTarget = 11
        case KeyEvent.KEYCODE_W => pageTarget = 12
        case KeyEvent.KEYCODE_E => pageTarget = 13
        case KeyEvent.KEYCODE_R => pageTarget = 14
        case KeyEvent.KEYCODE_T => pageTarget = 15
        case KeyEvent.KEYCODE_Y => pageTarget = 16
        case KeyEvent.KEYCODE_U => pageTarget = 17
        case KeyEvent.KEYCODE_I => pageTarget = 18
        case KeyEvent.KEYCODE_O => pageTarget = 19
        case KeyEvent.KEYCODE_P => pageTarget = 20
        case _ => ()
      }
      if (altOn && pageTarget != -1) {
        activity.pager.setCurrentItem(pageTarget)
        return true
      }
    }
    if (KeyEvent.KEYCODE_SEARCH != k) {
      completionPrefix = None
      completionOffset = None
    }
    false
  }
}

object CommandProcessor {
  def apply(c: Context, p: InputProcessor) = new CommandProcessor(c, p)
}
// set ctx, server and channel prior to invoking executeLine
sealed class CommandProcessor(ctx: Context, proc: InputProcessor) {
  val TAG = "CommandProcessor"

  def getString(res: Int, args: String*) = ctx.getString(res, args: _*)
  var channel: Option[ChannelLike] = None
  var server: Option[Server] = None

  lazy val manager = IrcManager.start()

  lazy val activity: MainActivity = {
    ctx match {
      case a: MainActivity => a
      case _ => null
    }
  }

  // accept a nullable string in case of input that isn't pre-cleaned
  def executeLine(line: String) {
    if (line == null || line.trim().length() == 0) return
    if (line.charAt(0) == '/') {
      val idx = line.indexOf(" ")
      var cmd = line.substring(1)
      var args: Option[String] = None
      if (idx != -1) {
        cmd = line.substring(1, idx)

        val a = line.substring(idx + 1)
        if (a.trim().length() == 0) args = None else args = Some(a)
      }
      cmd = cmd.toLowerCase
      if (cmd.length() == 0 || cmd.charAt(0) == ' ') {
        cmd = getString(R.string.command_quote)
        val a = if (line.length() > 2) line.substring(2) else ""
        args = if (a.trim().length() == 0) None else Some(a)
      }
      executeCommand(cmd, args)
    } else {
      sendMessage(Some(line))
    }
  }

  def addCommandError(error: Int): Unit = addCommandError(getString(error))

  def addCommandError(error: String) {
    channel map { _.add _ } orElse (server map { _.add _}) map {
      _(CommandError(error))
    } getOrElse {
      Log.w(TAG, "Unable to addCommandError, no server or channel: " + error)
    }
  }

  def sendMessage(line: Option[String], action: Boolean = false) {
    line foreach { l =>

      channel match {
        case Some(ch: Channel) =>
          val chan = manager.channels(ch)
          if (ch.server.state != Server.State.CONNECTED)
            return addCommandError(R.string.error_server_disconnected)
          if (ch.state != Channel.State.JOINED)
            return addCommandError(R.string.error_channel_disconnected)
          if (action) {
            ch.add(CtcpAction(ch.server.currentNick, l))
            chan.sendAction(l)
          } else {
            val u = chan.getUs
            if (u != null) // I doubt this will ever be null
              ch.add(Privmsg(ch.server.currentNick, l,
                u.hasOperator, u.hasVoice))
            else
              ch.add(Privmsg(ch.server.currentNick, l))
                chan.sendMessage(l)
          }
        case Some(query: Query)=>
          if (query.server.state != Server.State.CONNECTED)
            return addCommandError(R.string.error_server_disconnected)
          manager.connections.get(query.server) map { conn =>
            val user = conn.createUser(query.name)
            if (conn.isConnected) {
              if (action) {
                query.add(CtcpAction(query.server.currentNick, l))
                user.sendAction(l)
              } else {
                query.add(Privmsg(query.server.currentNick, l))
                user.sendMessage(l)
              }
            } else {
              addCommandError(R.string.not_connected)
            }
          } getOrElse addCommandError("No connection found for this session")
        case _ => addCommandError(R.string.error_no_channel)
      }
    }
  }

  def sendAction(line: Option[String]) = sendMessage(line, true)

  def executeCommand(cmd: String, args: Option[String]) {
    commands.get(cmd) map { _.execute(args) } getOrElse {
      addCommandError(getString(R.string.error_command_unknown, cmd))
    }
  }

  def currentServer = channel map { _.server } orElse server

  object JoinCommand extends Command {
    override def execute(args: Option[String]) {
      args map { chan =>
        var chan = args.get
        val idx = chan.indexOf(" ")
        var password: Option[String] = None
        if (idx != -1) {
          password = Some(chan.substring(idx + 1))
          chan = chan.substring(0, idx)
        }
        if (chan.length == 0)
          return addCommandError(R.string.usage_join)

        val first = chan.charAt(0)
        if (first != '#' && first != '&')
          chan = "#" + chan

        withConnection { conn =>
          if (conn.isConnected) {
            val c = conn.createChannel(chan)
            password map { p => c.join(p)} getOrElse c.join()
          } else {
            addCommandError("Not connected")
          }

        }
      } getOrElse addCommandError(R.string.usage_join)
    }
  }

  object PartCommand extends Command {
    override def execute(args: Option[String]) = TODO
  }

  object QuitCommand extends Command {
    override def execute(args: Option[String]) = MainActivity.instance map {
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
      if (line.trim().length() == 0)
        return addCommandError(usage)

      withConnection { c =>
        target.charAt(0) match {
          case '&' | '#' => () // do nothing if it's a channel
          case _ => manager.addQuery(c, target, line, sending = true)
        }
        val user = c.createUser(target)
        if (notice) user.sendNotice(line) else user.sendMessage(line)
      }
    } getOrElse addCommandError(usage)
  }

  def withConnection(f: IrcConnection => Unit) {
    currentServer map { s =>
      if (s.state != Server.State.CONNECTED)
        return addCommandError(R.string.error_server_disconnected)

      manager.connections.get(s) foreach f
    } getOrElse addCommandError(R.string.error_server_disconnected)
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
        case Some(c: Channel) =>
          args map { _ => TODO } getOrElse {
            c.topic foreach (t => c.add(t.copy(forceShow = true)))
          }
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
             target, command.toUpperCase,
             Option(if (trimmedArg.length == 0) null else trimmedArg))

          val (server, channel) = proc.currentState
          // show in currently visible tab or the server's message tab
          // if not currently on a message tab
          channel orElse server map { _.add(r) } getOrElse {
            currentServer map { _.add(r) }
          }
          c.createUser(target).sendCtcp(line)
        }
      } getOrElse addCommandError(R.string.usage_ctcp)
    }
  }

  object NickCommand extends Command {
    override def execute(args: Option[String]) {
      args map { newnick =>
        withConnection (conn => Future { conn.setNick(newnick) })
      } getOrElse addCommandError(R.string.usage_nick)
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
      args map { toIgnore =>
        toIgnore.trim.split("\\s+") foreach { i =>
          Config.Ignores += i

          addCommandError(getString(R.string.ignore_add_prefix, i))
        }

        ()
      } getOrElse {
        if (Config.Ignores.isEmpty)
          addCommandError(R.string.error_ignores_empty)
        else {
          addCommandError(getString(R.string.ignore_list_prefix,
            Config.Ignores map ( "  " + _ ) mkString "\n"))
        }
        ()
      }
    }
  }

  object UnignoreCommand extends Command {
    override def execute(args: Option[String]) = {
      args map { toUnignore =>
        toUnignore.trim.split("\\s+") foreach { i =>
          Config.Ignores -= i

          addCommandError(getString(R.string.ignore_remove_prefix, i))
        }

        ()
      } getOrElse {
        addCommandError(R.string.usage_unignore)
      }
    }
  }

  object ClearCommand extends Command {
    override def execute(args: Option[String]): Unit = {
      channel map { c =>
        c.clear()
      } getOrElse addCommandError(R.string.error_no_channel)
    }
  }

  object RawCommand extends Command {
    override def execute(args: Option[String]) {
      args map { line =>
        if (line.trim().length() == 0)
          addCommandError(R.string.usage_raw)
        else
          withConnection { c => c.sendRaw(line) }
      } getOrElse addCommandError(R.string.usage_raw)
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
