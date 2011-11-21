package com.hanhuy.android.irc

import com.hanhuy.android.irc.model.MessageAdapter
import com.hanhuy.android.irc.model.MessageLike._
import com.hanhuy.android.irc.model.ChannelLike
import com.hanhuy.android.irc.model.Server
import com.hanhuy.android.irc.model.Channel
import com.hanhuy.android.irc.model.Query

import android.view.View
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.util.Log

import com.sorcix.sirc.IrcConnection
import com.sorcix.sirc.{User => SircUser}

import scala.collection.mutable.HashMap

trait Command {
    def execute(args: Option[String])
}
class InputProcessor(activity: MainActivity) {
    val TAG = "InputProcessor"
    val commands = new HashMap[String,Command]

    var channel: Option[ChannelLike] = None
    var server: Option[Server] = None

    def onEditorActionListener(v: View, action: Int, e: KeyEvent):
            Boolean = {
        Log.i(TAG, "editoraction: " + action + " e: " + e)
        val input = v.asInstanceOf[EditText]
        if (action == EditorInfo.IME_ACTION_SEND)
            Unit // ignored
        if (action == EditorInfo.IME_NULL) {
            val line = input.getText()
            val f = activity.adapter.getItem(activity.adapter.page)
            f match {
            case s: ServersFragment => { server = s._server; channel = None }
            case c: ChannelFragment => {
                val ch = activity.service.chans(c.id)
                server = Some(ch.server)
                channel = Some(ch)
            }
            case q: QueryFragment => {
                val qu = activity.service.chans(q.id)
                server = Some(qu.server)
                channel = Some(qu)
            }
            case _ => { server = None; channel = None}
            }
            processLine(line.toString())
            input.setText(null)
        }
        false // return false so the keyboard can collapse
    }

    def onKeyListener(v: View, k: Int, e: KeyEvent): Boolean = {
        val input = v.asInstanceOf[EditText]
        Log.i(TAG, "key: " + k + " e: " + e)
        // keyboard shortcuts / honeycomb and above only
        // TAB / SEARCH nick completion
        if (KeyEvent.ACTION_UP == e.getAction()) {
            val meta = e.getMetaState()
            val altOn   = (meta & KeyEvent.META_ALT_ON)   > 0
            val ctrlOn  = (meta & KeyEvent.META_CTRL_ON)  > 0
            val shiftOn = (meta & KeyEvent.META_SHIFT_ON) > 0
            var pageTarget = -1

            k match {
            case KeyEvent.KEYCODE_TAB => {
                if (ctrlOn && shiftOn) { // move backward in tabs
                    val count = activity.adapter.getCount()
                    val current = activity.tabhost.getCurrentTab()
                    val next = if (current - 1 < 0) count - 1 else current - 1
                    activity.tabhost.setCurrentTab(next)
                } else if (ctrlOn) { // move forward in tabs
                    val count = activity.adapter.getCount()
                    val current = activity.tabhost.getCurrentTab()
                    val next = if (current + 1 >= count) 0 else current + 1
                    activity.tabhost.setCurrentTab(next)
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
            case _ => Unit
            }
            if (altOn && pageTarget != -1) {
                activity.tabhost.setCurrentTab(pageTarget)
                return true
            }
        }
        false
    }

    // accept a nullable string in case input comes from elsewhere
    // that isn't pre-cleaned
    def processLine(line: String) {
        if (line == null || line.trim().length() == 0) return
        if (line.charAt(0) == '/') {
            val idx = line.indexOf(" ")
            var cmd = line
            var args: Option[String] = None
            if (idx != -1) {
                cmd = line.substring(1, idx)

                val a = line.substring(idx + 1)
                if (a.trim().length() == 0) args = None
                else args = Some(a)
            }
            cmd = cmd.toLowerCase()
            if (cmd.length() == 0 || cmd.charAt(0) == ' ') {
                cmd = activity.getString(R.string.command_quote)
                val a = line.substring(2)
                if (a.trim().length() == 0)
                    args = None
                else
                    args = Some(a)
            }
            processCommand(cmd, args)
        } else {
            sendMessage(Some(line))
        }
    }

    def addCommandError(error: String) {
        getCurrentAdapter() match {
        case Some(a) => a.add(CommandError(error)) // TODO don't use adapter
        case None => Unit
        }
    }
    def getCurrentAdapter(): Option[MessageAdapter] = {
        val f = activity.adapter.getItem(activity.adapter.page)
        f match {
        case c: ChannelFragment => Some(c.adapter)
        case q: QueryFragment => Some(q.adapter)
        case s: ServersFragment => {
            if (s.serverMessagesFragmentShowing.isEmpty)
                None
            else {
                val tag = s.serverMessagesFragmentShowing.get
                val fm = activity.getSupportFragmentManager()
                val g = fm.findFragmentByTag(tag).asInstanceOf[MessagesFragment]
                if (g != null) Some(g.adapter) else None
            }
        }
        case _ => None
        }
    }
    def getCurrentServer(): Option[Server] = {
        activity.adapter.getItem(activity.adapter.page) match {
        case f: ChannelFragment => Some(activity.service.chans(f.id).server)
        case f: QueryFragment => Some(activity.service.chans(f.id).server)
        case f: ServersFragment => f._server
        case _ => None
        }
    }

    def sendMessage(line: Option[String], action: Boolean = false) {
        if (line.isEmpty) return

        val f = activity.adapter.getItem(activity.adapter.page)
        f match {
        case c: ChannelFragment => {
            val ch = activity.service.chans(c.id).asInstanceOf[Channel]
            val channel = activity.service.channels(ch)
            if (ch.server.state != Server.State.CONNECTED)
                return addCommandError(activity.getString(
                        R.string.error_server_disconnected))
            if (ch.asInstanceOf[Channel].state != Channel.State.JOINED)
                return addCommandError(activity.getString(
                        R.string.error_channel_disconnected))
            if (action) {
                ch.add(CtcpAction(ch.server.currentNick, line.get))
                channel.sendAction(line.get)
            } else {
                ch.add(Privmsg(ch.server.currentNick, line.get))
                channel.sendMessage(line.get)
            }
        }
        case q: QueryFragment => {
            val query = activity.service.chans(q.id).asInstanceOf[Query]
            if (query.server.state != Server.State.CONNECTED)
                return addCommandError(activity.getString(
                        R.string.error_server_disconnected))
            val conn = activity.service.connections(query.server)
            val user = conn.createUser(query.name)
            if (action) {
                query.add(CtcpAction(query.server.currentNick, line.get))
                user.sendAction(line.get)
            } else {
                query.add(Privmsg(query.server.currentNick, line.get))
                user.sendMessage(line.get)
            }
        }
        case _ =>
                addCommandError(activity.getString(R.string.error_no_channel))
        }
    }

    def sendAction(line: Option[String]) {
        sendMessage(line, true)
    }

    def processCommand(cmd: String, args: Option[String]) {
        commands.get(cmd) match {
        case Some(c) => c.execute(args)
        case None => addCommandError(
                activity.getString(R.string.error_command_unknown, cmd))
        }
    }

    object JoinCommand extends Command {
        override def execute(args: Option[String]) {
            if (args.isEmpty)
                return addCommandError(activity.getString(R.string.usage_join))

            var channel = args.get
            val idx = channel.indexOf(" ")
            var password: Option[String] = None
            if (idx != -1) {
                password = Some(channel.substring(idx + 1))
                channel = channel.substring(0, idx)
            }
            val first = channel.charAt(0)
            if (first != '#' && first != '&')
                channel = "#" + channel

            val f = activity.adapter.getItem(activity.adapter.page)
            var conn: Option[IrcConnection] = None
            f match {
            case s: ServersFragment => {
                if (s._server.isEmpty)
                    return addCommandError(
                            activity.getString(R.string.error_join_unknown))
                conn = Some(activity.service.connections(s._server.get))
            }
            case c: ChannelFragment => {
                val ch = activity.service.chans(c.id)
                conn = Some(activity.service.connections(ch.server))
            }
            case q: QueryFragment => {
                val qu = activity.service.chans(q.id)
                conn = Some(activity.service.connections(qu.server))
            }
            case _ => Unit
            }

            if (!conn.isEmpty) {
                val c = conn.get.createChannel(channel)
                if (!password.isEmpty)
                    c.join(password.get)
                else
                    c.join()
            } else {
                addCommandError(activity.getString(R.string.error_join_unknown))
            }
        }
    }
    commands += ((activity.getString(R.string.command_join_1), JoinCommand),
                 (activity.getString(R.string.command_join_2), JoinCommand))

    object PartCommand extends Command {
        override def execute(args: Option[String]) = Unit
    }
    commands += ((activity.getString(R.string.command_leave_1), PartCommand),
                 (activity.getString(R.string.command_leave_2), PartCommand))

    object QuitCommand extends Command {
        override def execute(args: Option[String]) = activity.exit(args)
    }
    commands += ((activity.getString(R.string.command_quit), QuitCommand))

    object QuoteCommand extends Command {
        override def execute(args: Option[String]) = sendMessage(args)
    }
    commands += ((activity.getString(R.string.command_quote), QuoteCommand))

    def messageCommandSend(args: Option[String], notice: Boolean = false) {
        val usage = if (notice) R.string.usage_notice else R.string.usage_msg

        if (args.isEmpty)
            return addCommandError(activity.getString(usage))
        val a = args.get
        val idx = a.indexOf(" ")
        if (idx == -1)
            return addCommandError(activity.getString(usage))
        val target = a.substring(0, idx)
        val line = a.substring(idx + 1)
        if (line.trim().length() == 0)
            return addCommandError(activity.getString(usage))
        getCurrentServer() match {
        case Some(s) => {
            if (s.state != Server.State.CONNECTED)
                return addCommandError(activity.getString(
                        R.string.error_server_disconnected))

            val c = activity.service.connections(s)
            activity.service.addQuery(c, target, line, sending = true)
            val user = c.createUser(target)
            if (notice) user.sendNotice(line)
            else user.sendMessage(line)
        }
        case None => Unit
        }
    }

    object MessageCommand extends Command {
        override def execute(args: Option[String]) = messageCommandSend(args)
    }
    commands += ((activity.getString(R.string.command_msg_1), MessageCommand),
                 (activity.getString(R.string.command_msg_2), MessageCommand))

    object NoticeCommand extends Command {
        override def execute(args: Option[String]) =
                messageCommandSend(args, true)
    }
    commands += ((activity.getString(R.string.command_notice), NoticeCommand))

    object ActionCommand extends Command {
        override def execute(args: Option[String]) = sendAction(args)
    }
    commands += ((activity.getString(R.string.command_action_1), ActionCommand),
                 (activity.getString(R.string.command_action_2), ActionCommand))

    object PingCommand extends Command {
        override def execute(args: Option[String]) = Unit
    }
    commands += ((activity.getString(R.string.command_ping), PingCommand))

    object TopicCommand extends Command {
        override def execute(args: Option[String]) = Unit
    }
    commands += ((activity.getString(R.string.command_topic), TopicCommand))

    object InviteCommand extends Command {
        override def execute(args: Option[String]) = Unit
    }
    commands += ((activity.getString(R.string.command_invite), InviteCommand))

    object IgnoreCommand extends Command {
        override def execute(args: Option[String]) = Unit
    }
    commands += ((activity.getString(R.string.command_ignore), IgnoreCommand))
}

class CommandProcessor {
}
