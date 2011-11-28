package com.hanhuy.android.irc

import com.hanhuy.android.irc.model.MessageAdapter
import com.hanhuy.android.irc.model.MessageLike._
import com.hanhuy.android.irc.model.ChannelLike
import com.hanhuy.android.irc.model.Server
import com.hanhuy.android.irc.model.Channel
import com.hanhuy.android.irc.model.Query

import android.content.Context
import android.view.View
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.util.Log

import com.sorcix.sirc.IrcConnection
import com.sorcix.sirc.{User => SircUser}

import scala.collection.mutable.HashMap
import scala.ref.WeakReference

trait Command {
    def execute(args: Option[String])
}
class InputProcessor(activity: MainActivity) {
    val TAG = "InputProcessor"

    val processor = new CommandProcessor(activity)

    def onEditorActionListener(v: View, action: Int, e: KeyEvent):
            Boolean = {
        Log.i(TAG, "editoraction: " + action + " e: " + e)
        val input = v.asInstanceOf[EditText]
        if (action == EditorInfo.IME_ACTION_SEND)
            Unit // ignored
        if (action == EditorInfo.IME_NULL) {
            val line = input.getText()
            activity.adapter.getItem(activity.adapter.page) match {
            case s: ServersFragment => {
                processor.server = s._server
                processor.channel = None
            }
            case c: ChannelFragment => {
                val ch = activity.service.chans(c.id)
                processor.server = Some(ch.server)
                processor.channel = Some(ch)
            }
            case q: QueryFragment => {
                val qu = activity.service.chans(q.id)
                processor.server = Some(qu.server)
                processor.channel = Some(qu)
            }
            case _ => { processor.server = None; processor.channel = None }
            }
            processor.executeLine(line.toString())
            input.setText(null)
        }
        false // return false so the keyboard can collapse
    }

    def onKeyListener(v: View, k: Int, e: KeyEvent): Boolean = {
        val input = v.asInstanceOf[EditText]
        Log.i(TAG, "key: " + k + " e: " + e)
        // keyboard shortcuts / honeycomb and above only
        // TAB / SEARCH nick completion TODO
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

}

// set ctx, server and channel prior to invoking executeLine
class CommandProcessor(c: Context) {
    val TAG = "CommandProcessor"
    val commands = new HashMap[String,Command]
    val ctx = new WeakReference(c)

    def getString(res: Int, args: String*) = ctx.get.get.getString(res, args)
    var channel: Option[ChannelLike] = None
    var server: Option[Server] = None

    def service: IrcService = {
        ctx.get.get match {
        case s: IrcService => s
        case a: MainActivity => a.service
        }
    }
    def activity: MainActivity = {
        ctx.get.get match {
        case s: IrcService => s.activity.get
        case a: MainActivity => a
        }
    }

    // accept a nullable string in case of input that isn't pre-cleaned
    def executeLine(line: String) {
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
                cmd = getString(R.string.command_quote)
                val a = line.substring(2)
                if (a.trim().length() == 0)
                    args = None
                else
                    args = Some(a)
            }
            executeCommand(cmd, args)
        } else {
            sendMessage(Some(line))
        }
    }

    def addCommandError(error: String) {
        if (!channel.isEmpty)
            channel.get.add(CommandError(error))
        else if (!server.isEmpty)
            server.get.add(CommandError(error))
        else Log.w(TAG, "Unable to addCommandError, no server or channel")
    }

    def sendMessage(line: Option[String], action: Boolean = false) {
        if (line.isEmpty) return

        if (channel.isEmpty)
            return addCommandError(getString(R.string.error_no_channel))

        channel.get match {
        case ch: Channel => {
            val chan = service.channels(ch)
            if (ch.server.state != Server.State.CONNECTED)
                return addCommandError(getString(
                        R.string.error_server_disconnected))
            if (ch.state != Channel.State.JOINED)
                return addCommandError(getString(
                        R.string.error_channel_disconnected))
            if (action) {
                ch.add(CtcpAction(ch.server.currentNick, line.get))
                chan.sendAction(line.get)
            } else {
                ch.add(Privmsg(ch.server.currentNick, line.get))
                chan.sendMessage(line.get)
            }
        }
        case query: Query => {
            if (query.server.state != Server.State.CONNECTED)
                return addCommandError(getString(
                        R.string.error_server_disconnected))
            val conn = service.connections(query.server)
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
                addCommandError(getString(R.string.error_no_channel))
        }
    }

    def sendAction(line: Option[String]) {
        sendMessage(line, true)
    }

    def executeCommand(cmd: String, args: Option[String]) {
        commands.get(cmd) match {
        case Some(c) => c.execute(args)
        case None => addCommandError(
                getString(R.string.error_command_unknown, cmd))
        }
    }

    def getCurrentServer() = {
        channel match {
        case Some(c) => Some(c.server)
        case None => server
        }
    }
    object JoinCommand extends Command {
        override def execute(args: Option[String]) {
            if (args.isEmpty)
                return addCommandError(getString(R.string.usage_join))

            var chan = args.get
            val idx = chan.indexOf(" ")
            var password: Option[String] = None
            if (idx != -1) {
                password = Some(chan.substring(idx + 1))
                chan = chan.substring(0, idx)
            }
            val first = chan.charAt(0)
            if (first != '#' && first != '&')
                chan = "#" + chan

            var conn: Option[IrcConnection] = None
            Log.w(TAG, "JoinCommand")

            val s = getCurrentServer()
            if (!s.isEmpty)
                conn = service.connections.get(s.get)

            if (!conn.isEmpty) {
                val c = conn.get.createChannel(chan)
                Log.w(TAG, "Joining: " + chan)
                if (!password.isEmpty)
                    c.join(password.get)
                else
                    c.join()
            } else {
                addCommandError(getString(R.string.error_join_unknown))
            }
        }
    }
    commands += ((getString(R.string.command_join_1), JoinCommand),
                 (getString(R.string.command_join_2), JoinCommand))

    object PartCommand extends Command {
        override def execute(args: Option[String]) = Unit
    }
    commands += ((getString(R.string.command_leave_1), PartCommand),
                 (getString(R.string.command_leave_2), PartCommand))

    object QuitCommand extends Command {
        override def execute(args: Option[String]) = activity.exit(args)
    }
    commands += ((getString(R.string.command_quit), QuitCommand))

    object QuoteCommand extends Command {
        override def execute(args: Option[String]) = sendMessage(args)
    }
    commands += ((getString(R.string.command_quote), QuoteCommand))

    def messageCommandSend(args: Option[String], notice: Boolean = false) {
        val usage = if (notice) R.string.usage_notice else R.string.usage_msg

        if (args.isEmpty)
            return addCommandError(getString(usage))
        val a = args.get
        val idx = a.indexOf(" ")
        if (idx == -1)
            return addCommandError(getString(usage))
        val target = a.substring(0, idx)
        val line = a.substring(idx + 1)
        if (line.trim().length() == 0)
            return addCommandError(getString(usage))
        getCurrentServer() match {
        case Some(s) => {
            if (s.state != Server.State.CONNECTED)
                return addCommandError(getString(
                        R.string.error_server_disconnected))

            val c = service.connections(s)
            service.addQuery(c, target, line, sending = true)
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
    commands += ((getString(R.string.command_msg_1), MessageCommand),
                 (getString(R.string.command_msg_2), MessageCommand))

    object NoticeCommand extends Command {
        override def execute(args: Option[String]) =
                messageCommandSend(args, true)
    }
    commands += ((getString(R.string.command_notice), NoticeCommand))

    object ActionCommand extends Command {
        override def execute(args: Option[String]) = sendAction(args)
    }
    commands += ((getString(R.string.command_action_1), ActionCommand),
                 (getString(R.string.command_action_2), ActionCommand))

    object PingCommand extends Command {
        override def execute(args: Option[String]) = Unit
    }
    commands += ((getString(R.string.command_ping), PingCommand))

    object TopicCommand extends Command {
        override def execute(args: Option[String]) = Unit
    }
    commands += ((getString(R.string.command_topic), TopicCommand))

    object InviteCommand extends Command {
        override def execute(args: Option[String]) = Unit
    }
    commands += ((getString(R.string.command_invite), InviteCommand))

    object IgnoreCommand extends Command {
        override def execute(args: Option[String]) = Unit
    }
    commands += ((getString(R.string.command_ignore), IgnoreCommand))
}
