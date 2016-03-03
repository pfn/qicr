package com.hanhuy.android.irc

import com.hanhuy.android.irc.model.Server

import scala.ref.WeakReference

import android.os.StrictMode

import com.hanhuy.android.common._
import android.support.v7.view.ActionMode
import android.view.{Menu, MenuItem, MenuInflater}

import android.support.v4.view.MenuItemCompat

object HoneycombSupport {
  val TAG = "HoneycombSupport"
  var activity = Option.empty[MainActivity]
  var _server: WeakReference[Server] = WeakReference.empty
  var _actionmode: WeakReference[ActionMode] = WeakReference.empty
  var menuItemListener = Option.empty[(MenuItem, Option[Server]) => Boolean]

  def init(main: MainActivity) = activity = main.?

  def close() {
    menuItemListener = None
    activity = None
  }

  def invalidateActionBar() {
    activity.foreach(_.supportInvalidateOptionsMenu())
  }

  def stopActionMode() {
    _actionmode.get foreach { _.finish() }
    _actionmode = WeakReference.empty
  }

  def recreate() {

    if (honeycombAndNewer) {
      activity.foreach(_.recreate())
    } else {
      IrcManager.instance foreach (_.queueCreateActivity(activity.fold(0)(_.adapter.page)))
      activity.foreach(_.finish())
    }
  }

  def startActionMode(server: Server) {
    _server = new WeakReference(server)
    _actionmode = activity.fold(WeakReference.empty[ActionMode])(a => WeakReference(
      a.startSupportActionMode(ServerActionModeSetup)))
  }

  def setTitle(s: String) = activity.foreach(_.getSupportActionBar.setTitle(s))
  def setSubtitle(s: String) = activity.foreach(_.getSupportActionBar.setSubtitle(s))

  object ServerActionModeSetup extends ActionMode.Callback {
    override def onActionItemClicked(mode: ActionMode, item: MenuItem) = {
      mode.finish()
      menuItemListener.fold(false)(_(item, _server.get))
    }

    override def onCreateActionMode(mode: ActionMode, menu: Menu) = {
      activity.fold(false) { a =>
        val inflater = new MenuInflater(a)
        inflater.inflate(R.menu.server_menu, menu)
        List(R.id.server_connect,
          R.id.server_disconnect,
          R.id.server_options).foreach(i =>
          MenuItemCompat.setShowAsAction(menu.findItem(i),
            MenuItem.SHOW_AS_ACTION_IF_ROOM | MenuItem.SHOW_AS_ACTION_WITH_TEXT)
        )
        setServerConnectionAction(menu)
        true
      }
    }

    override def onDestroyActionMode(mode: ActionMode) = ()

    def setServerConnectionAction(menu: Menu): Unit = {
      _server.get foreach { s =>
        val connected = s.state.now match {
          case Server.INITIAL      => false
          case Server.DISCONNECTED => false
          case _                   => true
        }

        menu.findItem(R.id.server_connect).setVisible(!connected)
        menu.findItem(R.id.server_disconnect).setVisible(connected)
      }
    }
    override def onPrepareActionMode(mode: ActionMode, menu: Menu) = {
      setServerConnectionAction(menu)
      true
    }
  }

  def startNickActionMode(nick: String)(f: (MenuItem => Unit)) {
      _actionmode = activity.fold(WeakReference.empty[ActionMode])(a => WeakReference(
        a.startSupportActionMode(
          NickListActionModeSetup(nick.dropWhile(Set(' ', '@', '+')), f))))
  }

  case class NickListActionModeSetup[A](nick: String, callback: MenuItem => A) extends ActionMode.Callback {

    override def onActionItemClicked(mode: ActionMode, item: MenuItem) = {
      mode.finish()
      callback(item)
      true
    }

    override def onCreateActionMode(mode: ActionMode, menu: Menu) = activity.fold(false) { a =>
      implicit val act = a
      val inflater = new MenuInflater(a)
      inflater.inflate(R.menu.nicklist_menu, menu)
      mode.setTitle(nick)
      val item = menu.findItem(R.id.nick_ignore)
      item.setChecked(Config.Ignores(nick))
      item.setIcon(Application.getDrawable(a, iota.resolveAttr(if (Config.Ignores(nick))
        R.attr.qicrChatIgnoreIcon else R.attr.qicrChatEndIcon, _.resourceId)))
      true
    }

    override def onDestroyActionMode(mode: ActionMode) = ()

    override def onPrepareActionMode(mode: ActionMode, menu: Menu) = true
  }
}

object GingerbreadSupport {
  var _init = false
  def init() {
    if (!_init) {
      _init = true
      if (BuildConfig.DEBUG) StrictMode.setVmPolicy(
        new StrictMode.VmPolicy.Builder().detectAll.penaltyLog.build)
    }
  }
}
