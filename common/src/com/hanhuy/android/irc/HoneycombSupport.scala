package com.hanhuy.android.irc

import com.hanhuy.android.irc.model.Server

import scala.ref.WeakReference

import android.os.StrictMode

import android.app.ActionBar
import android.view.ActionMode
import android.view.{Menu, MenuItem, MenuInflater}
import android.view.View
import android.util.Log

import AndroidConversions._

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
            activity.invalidateOptionsMenu()
    }
    def stopActionMode() {
        if (_actionmode == null) return
        _actionmode.get foreach { _.finish() }
        _actionmode = null
    }
    def recreate() = activity.recreate()

    def startActionMode(server: Server) {
        _server = new WeakReference(server)
        if (activity == null) return
        _actionmode = new WeakReference(
                activity.startActionMode(ServerActionModeSetup))
    }

    def setupSpinnerNavigation(a: MainPagerAdapter) {
        val bar = activity.getActionBar
        a.hsv.setVisibility(View.GONE)
        bar.setNavigationMode(ActionBar.NAVIGATION_MODE_LIST)
        bar.setDisplayOptions(0, ActionBar.DISPLAY_SHOW_TITLE)
        bar.setListNavigationCallbacks(
                a.DropDownAdapter, a.actionBarNavigationListener _)
    }

    def setSelectedNavigationItem(pos: Int) {
        if (activity == null) return
        val bar = activity.getActionBar
        if (bar.getNavigationItemCount > pos)
            activity.getActionBar.setSelectedNavigationItem(pos)
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
                 R.id.server_options).foreach(
                         menu.findItem(_).setShowAsAction(
                                 MenuItem.SHOW_AS_ACTION_IF_ROOM |
                                 MenuItem.SHOW_AS_ACTION_WITH_TEXT))
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

    def startActionMode(f: NickListFragment) {
        NickListActionModeSetup.fragment = new WeakReference(f)
        _actionmode = new WeakReference(
                activity.startActionMode(NickListActionModeSetup))
    }

    object NickListActionModeSetup extends ActionMode.Callback {
        var fragment: WeakReference[NickListFragment] = _
        override def onActionItemClicked(mode: ActionMode, item: MenuItem) = {
            mode.finish()
            fragment.get.foreach(_.onContextItemSelected(item))
            true
        }
        override def onCreateActionMode(mode: ActionMode, menu: Menu) = {
            val inflater = new MenuInflater(activity)
            inflater.inflate(R.menu.nicklist_menu, menu)
            List(R.id.nick_insert,
                 R.id.nick_start_chat).foreach(
                         menu.findItem(_).setShowAsAction(
                                 MenuItem.SHOW_AS_ACTION_IF_ROOM |
                                 MenuItem.SHOW_AS_ACTION_WITH_TEXT))
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
