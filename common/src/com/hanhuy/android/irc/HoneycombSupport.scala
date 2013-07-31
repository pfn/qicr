package com.hanhuy.android.irc

import com.hanhuy.android.irc.model.Server

import scala.ref.WeakReference

import android.os.StrictMode

import android.support.v7.app.ActionBar
import android.support.v7.view.ActionMode
import android.view.{Menu, MenuItem, MenuInflater}
import android.view.View

import AndroidConversions._
import android.support.v4.view.{MenuItemCompat, MenuCompat}

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
      activity.recreate()
    } else {
      activity.service.queueCreateActivity(activity.adapter.page)
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

  def setupSpinnerNavigation(a: MainPagerAdapter) {
    val bar = activity.getSupportActionBar
    a.tabindicators.setVisibility(View.GONE)
    bar.setNavigationMode(ActionBar.NAVIGATION_MODE_LIST)
    bar.setDisplayOptions(0, ActionBar.DISPLAY_SHOW_TITLE)
    bar.setListNavigationCallbacks(
      a.DropDownAdapter, a.actionBarNavigationListener _)
  }

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
      true
    }

    override def onDestroyActionMode(mode: ActionMode) = ()

    override def onPrepareActionMode(mode: ActionMode, menu: Menu) = {
      _server.get map { s =>
        val connected = s.state match {
          case Server.State.INITIAL      => false
          case Server.State.DISCONNECTED => false
          case _                         => true
        }

        menu.findItem(R.id.server_connect).setVisible(!connected)
        menu.findItem(R.id.server_disconnect).setVisible(connected)
      }
      true
    }
  }

  def startNickActionMode(nick: String)(f: (MenuItem => Unit)) {
    NickListActionModeSetup.callback = f
    NickListActionModeSetup.nick = nick
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
      List(R.id.nick_insert,
        R.id.nick_start_chat).foreach(i =>
          MenuItemCompat.setShowAsAction(menu.findItem(i),
            MenuItem.SHOW_AS_ACTION_IF_ROOM | MenuItem.SHOW_AS_ACTION_WITH_TEXT)
        )
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
