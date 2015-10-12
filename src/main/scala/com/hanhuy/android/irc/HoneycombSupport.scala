package com.hanhuy.android.irc

import com.hanhuy.android.irc.model.Server

import scala.ref.WeakReference

import android.os.StrictMode

import android.support.v7.app.ActionBar
import android.support.v7.view.ActionMode
import android.view.{Menu, MenuItem, MenuInflater}
import android.view.View

import com.hanhuy.android.common._
import android.support.v4.view.MenuItemCompat
import android.support.v7.app.ActionBar.OnNavigationListener

object HoneycombSupport {
  val TAG = "HoneycombSupport"
  var activity: MainActivity = _
  var _server: WeakReference[Server] = _
  var _actionmode: WeakReference[ActionMode] = _
  var menuItemListener: (MenuItem, Option[Server]) => Boolean = _

  def init(main: MainActivity) = activity = main

  def close() {
    menuItemListener = null
    activity = null
  }

  def invalidateActionBar() {
    if (activity != null)
      activity.supportInvalidateOptionsMenu()
  }

  def stopActionMode() {
    if (_actionmode == null) return
    _actionmode.get foreach { _.finish() }
    _actionmode = null
  }

  def recreate() {

    if (honeycombAndNewer) {
      if (activity != null)
        activity.recreate()
    } else {
      IrcManager.instance foreach (_.queueCreateActivity(activity.adapter.page))
      activity.finish()
    }
  }

  def startActionMode(server: Server) {
    _server = new WeakReference(server)
    if (activity == null) return
    _actionmode = new WeakReference(
      activity.startSupportActionMode(ServerActionModeSetup))
  }

  def setSubtitle(s: String) = if (activity != null) {
    activity.getSupportActionBar.setSubtitle(s)
  }

  implicit def toOnNavigationListener(f: (Int, Long) => Boolean):
  ActionBar.OnNavigationListener = new OnNavigationListener {
    override def onNavigationItemSelected(p1: Int, p2: Long) = f(p1, p2)
  }

  def setupSpinnerNavigation(a: MainPagerAdapter) {
    val bar = activity.getSupportActionBar
    a.tabindicators.setVisibility(View.GONE)
    bar.setNavigationMode(ActionBar.NAVIGATION_MODE_LIST)
    bar.setDisplayOptions(0, ActionBar.DISPLAY_SHOW_TITLE)
    bar.setListNavigationCallbacks(
      a.DropDownNavAdapter, a.actionBarNavigationListener _)
  }

  def isSpinnerNavigation =
    activity.getSupportActionBar.getNavigationMode ==
      ActionBar.NAVIGATION_MODE_LIST

  def setSelectedNavigationItem(pos: Int) {
    if (activity == null) return
    val bar = activity.getSupportActionBar
    if (bar.getNavigationItemCount > pos)
      activity.getSupportActionBar.setSelectedNavigationItem(pos)
  }

  object ServerActionModeSetup extends ActionMode.Callback {
    override def onActionItemClicked(mode: ActionMode, item: MenuItem) = {
      mode.finish()
      menuItemListener(item, _server.get)
    }

    override def onCreateActionMode(mode: ActionMode, menu: Menu) = {
      val inflater = new MenuInflater(activity)
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

    override def onDestroyActionMode(mode: ActionMode) = ()

    def setServerConnectionAction(menu: Menu): Unit = {
      _server.get map { s =>
        val connected = s.state match {
          case Server.State.INITIAL      => false
          case Server.State.DISCONNECTED => false
          case _                         => true
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
    NickListActionModeSetup.callback = f
    NickListActionModeSetup.nick = nick.dropWhile(n => Set(' ','@','+')(n))
    _actionmode = new WeakReference(
      activity.startSupportActionMode(NickListActionModeSetup))
  }

  object NickListActionModeSetup extends ActionMode.Callback {
    var callback: (MenuItem => Unit) = _
    var nick: String = _

    override def onActionItemClicked(mode: ActionMode, item: MenuItem) = {
      mode.finish()
      callback(item)
      true
    }

    override def onCreateActionMode(mode: ActionMode, menu: Menu) = {
      val inflater = new MenuInflater(activity)
      inflater.inflate(R.menu.nicklist_menu, menu)
      mode.setTitle(nick)
      val item = menu.findItem(R.id.nick_ignore)
      item.setChecked(Config.Ignores(nick))
      item.setIcon(if (Config.Ignores(nick))
        R.drawable.ic_menu_end_conversation_on
      else
        R.drawable.ic_menu_end_conversation)
      true
    }

    override def onDestroyActionMode(mode: ActionMode) = ()

    override def onPrepareActionMode(mode: ActionMode, menu: Menu) = true
  }
}

object GingerbreadSupport {
  var _init = false
  val DEVELOPMENT_MODE = true
  def init() {
    if (_init) return
    _init = true
    if (DEVELOPMENT_MODE) StrictMode.setVmPolicy(
      new StrictMode.VmPolicy.Builder().detectAll.penaltyLog.build)
  }
}
