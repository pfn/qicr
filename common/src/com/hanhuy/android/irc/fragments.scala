package com.hanhuy.android.irc

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.{View, ViewGroup}
import android.view.{Menu, MenuItem, MenuInflater}
import android.widget.AdapterView
import android.widget.{ListView, ArrayAdapter}
import android.widget.Toast
import android.util.Log

import android.support.v4.app.ListFragment
import android.support.v4.app.DialogFragment
import android.support.v4.app.{FragmentManager, FragmentTransaction}

import com.hanhuy.android.irc.model.Channel
import com.hanhuy.android.irc.model.Query
import com.hanhuy.android.irc.model.Server
import com.hanhuy.android.irc.model.MessageAdapter
import com.hanhuy.android.irc.model.BusEvent

import AndroidConversions._

import MainActivity._

// TODO remove retainInstance -- messes up with theme change
// TODO fix dialog dismiss on-recreate
class ServerSetupFragment extends DialogFragment {
  var thisview: View = _

  var _server: Server = _
  def server: Server = {
    val s = _server
    if (s == null) return _server
    s.name        = thisview.findView(TR.add_server_name)
    s.hostname    = thisview.findView(TR.add_server_host)
    s.port        = thisview.findView(TR.add_server_port)
    s.ssl         = thisview.findView(TR.add_server_ssl)
    s.autoconnect = thisview.findView(TR.add_server_autoconnect)
    s.nickname    = thisview.findView(TR.add_server_nickname)
    s.altnick     = thisview.findView(TR.add_server_altnick)
    s.realname    = thisview.findView(TR.add_server_realname)
    s.username    = thisview.findView(TR.add_server_username)
    s.password    = thisview.findView(TR.add_server_password)
    s.autojoin    = thisview.findView(TR.add_server_autojoin)
    s.autorun     = thisview.findView(TR.add_server_autorun)
    _server
  }
  def server_=(s: Server) = {
    _server = s
    if (thisview != null && s != null) {
      thisview.findView(TR.add_server_name).setText(s.name)
      thisview.findView(TR.add_server_host).setText(s.hostname)
      thisview.findView(TR.add_server_port).setText("" + s.port)
      thisview.findView(TR.add_server_ssl).setChecked(s.ssl)
      thisview.findView(TR.add_server_autoconnect).setChecked(s.autoconnect)
      thisview.findView(TR.add_server_nickname).setText(s.nickname)
      thisview.findView(TR.add_server_altnick).setText(s.altnick)
      thisview.findView(TR.add_server_realname).setText(s.realname)
      thisview.findView(TR.add_server_username).setText(s.username)
      thisview.findView(TR.add_server_password).setText(s.password)
      thisview.findView(TR.add_server_autojoin).setText(s.autojoin)
      thisview.findView(TR.add_server_autorun).setText(s.autorun)
    }
  }

  override def onCreate(bundle: Bundle) {
    super.onCreate(bundle)
    setHasOptionsMenu(true)
    //setRetainInstance(true)
  }

  override def onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) =
    inflater.inflate(R.menu.server_setup_menu, menu)

  override def onOptionsItemSelected(item: MenuItem) = {
    val R_id_cancel_server = R.id.cancel_server
    val R_id_save_server = R.id.save_server
    item.getItemId match {
      case R_id_save_server => {
        val activity = getActivity
        val manager = activity.getSupportFragmentManager
        val s = server
        if (s.valid) {
          if (s.id == -1)
            activity.service.addServer(s)
          else
            activity.service.updateServer(s)
          manager.popBackStack()
        } else {
          Toast.makeText(getActivity, R.string.server_incomplete,
            Toast.LENGTH_SHORT).show()
        }
        true
      }
      case R_id_cancel_server => {
        val manager = getActivity.getSupportFragmentManager
        manager.popBackStack()
        true
      }
      case _ => false
    }
  }

  override def onCreateView(inflater: LayoutInflater,
      container: ViewGroup, bundle: Bundle) : View = {
    // otherwise an AndroidRuntimeException occurs
    if (dialogShown) return super.onCreateView(inflater, container, bundle)

    createView(inflater, container)
  }

  private def createView(inflater: LayoutInflater, c: ViewGroup): View = {
    thisview = inflater.inflate(R.layout.fragment_server_setup, c, false)
    server = _server
    thisview
  }

  var dialogShown = false
  override def onCreateDialog(bundle: Bundle): Dialog = {
    dialogShown = true
    val activity = getActivity
    //val m = activity.settings.get(Settings.DAYNIGHT_MODE)
    //import android.view.ContextThemeWrapper
    //val d = new AlertDialog.Builder(new ContextThemeWrapper(activity,
    //    if (m) R.style.AppTheme_Light else R.style.AppTheme_Dark))
    val d = new AlertDialog.Builder(activity)
      .setTitle(R.string.server_details)
      .setPositiveButton(R.string.save_server, null)
      .setNegativeButton(R.string.cancel_server, null)
      .setView(createView(getActivity.getLayoutInflater, null))
      .create()
    // block dismiss on positive button click
    d.setOnShowListener { () =>
      val b = d.getButton(DialogInterface.BUTTON_POSITIVE)
      b.onClick {
        val s = server
        if (s != null && s.valid) {
          if (s.id == -1)
            activity.service.addServer(s)
          else
            activity.service.updateServer(s)
          d.dismiss()
        } else {
          Toast.makeText(getActivity,
            R.string.server_incomplete,
            Toast.LENGTH_SHORT).show()
        }
      }
    }
    d
  }
}

abstract class MessagesFragment(_a: MessageAdapter = null)
extends ListFragment with EventBus.RefOwner {
  def this() = this(null)

  var id = -1
  var tag: String

  // eh?
  lazy val _service = getActivity.service
  var __service: IrcService = _
  def service = if (__service != null) __service else _service

  var _adapter = _a
  def adapter = _adapter
  def adapter_=(a: MessageAdapter) = {
    _adapter = a
    if (getActivity != null) _adapter.activity = getActivity

    setListAdapter(_adapter)
    service.add(id, _adapter)
    try { // TODO FIXME figure out how to do this better
      getListView.setSelection(
        if (adapter.getCount() > 0) _adapter.getCount()-1 else 0)
    } catch {
      case e: IllegalStateException => Log.d(TAG, "Content view not ready", e)
    }
  }

  override def onCreate(bundle: Bundle) {
    super.onCreate(bundle)
    val activity = getActivity

    id = if (id == -1 && bundle != null) bundle.getInt("id")
      else service.newMessagesId()

    if (bundle != null)
      tag = bundle.getString("tag")

    if (adapter != null) { // this works by way of the network being slow
      val service = activity.service // assuming service is ready?
      adapter.activity = getActivity
      service.add(id, adapter)
      setListAdapter(adapter)
    }
    if (activity.service == null)
      UiBus += { case BusEvent.ServiceConnected(s) =>
        onServiceConnected(s)
        EventBus.Remove
      }
    else
      onServiceConnected(activity.service)
  }

  override def onCreateView(inflater: LayoutInflater,
      container: ViewGroup, bundle: Bundle) : View =
    inflater.inflate(R.layout.fragment_messages, container, false)

  override def onResume() {
    super.onResume()
    if (adapter != null) // scroll to bottom on resume
      getListView.setSelection(adapter.getCount()-1)
  }
  override def onSaveInstanceState(bundle: Bundle) {
    super.onSaveInstanceState(bundle)
    bundle.putInt("id", id)
    bundle.putString("tag", tag)
  }
  def onServiceConnected(service: IrcService) {
    if (adapter == null && id != -1) {
        __service = service
      service.messages.get(id) foreach { adapter = _ }
    }
  }
}

class ChannelFragment(a: MessageAdapter, var channel: Channel)
extends MessagesFragment(a) with EventBus.RefOwner {
  var tag = getFragmentTag(channel)
  def this() = this(null, null)
  def channelReady = channel != null


  // TODO get rid of this reference through use of UiBus
  var nicklist: Option[ListView] = None // Some when large+
  //Log.d(TAG, "Creating ChannelFragment: " + this, new StackTrace)
  override def onCreate(bundle: Bundle) {
    super.onCreate(bundle)
    setHasOptionsMenu(true)

    val activity = getActivity
    if (channel == null) {
      def setChannel(s: IrcService) {
        val c = s.chans.get(bundle.getInt("id"))
        c.foreach(ch => channel = ch.asInstanceOf[Channel])
      }
      if (activity.service != null)
        setChannel(activity.service)
      else {
        UiBus += { case BusEvent.ServiceConnected(s) =>
          setChannel(s)
          EventBus.Remove
        }
      }
    }
    // this apparently works by virtue of the network being slow?
    if (id != -1 && channelReady && a != null) {
      activity.service.add(id, channel)
      a.channel = channel
    }
  }

  override def onCreateView(inflater: LayoutInflater,
      container: ViewGroup, bundle: Bundle) : View = {
    inflater.inflate(R.layout.fragment_channel, container, false)
  }

  override def onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) =
    inflater.inflate(R.menu.channel_menu, menu)

  override def onOptionsItemSelected(item: MenuItem): Boolean = {
    if (R.id.channel_close == item.getItemId) {
      val activity = getActivity
      val prompt = activity.settings.get(Settings.CLOSE_TAB_PROMPT)

      Log.d(TAG, "Requesting tab close for: " + channel + " <= " + id)
      def removeChannel() {
        if (channel != null && channel.state == Channel.State.JOINED) {
          activity.service.channels.get(channel) foreach { _.part() }
        }
        activity.service.remove(id)
        activity.service.remove(channel)
        activity.adapter.removeTab(activity.adapter.getItemPosition(this))
      }
      if (channel != null && channel.state == Channel.State.JOINED && prompt) {
        val builder = new AlertDialog.Builder(activity)
        builder.setTitle(R.string.channel_close_confirm_title)
        builder.setMessage(getString(R.string.channel_close_confirm))
        builder.setPositiveButton(R.string.yes, () => {
          removeChannel()
          channel.state = Channel.State.PARTED
        })
        builder.setNegativeButton(R.string.no, null)
        builder.create().show()
      } else {
        removeChannel()
      }
      return true
    }
    false
  }
}

class QueryFragment(a: MessageAdapter, val query: Query)
extends MessagesFragment(a) {
  var tag = getFragmentTag(query)
  def this() = this(null, null)

  override def onCreate(bundle: Bundle) {
    super.onCreate(bundle)
    setHasOptionsMenu(true)
    if (id != -1 && query != null) {
      val activity = getActivity
      activity.service.add(id, query)
      a.channel = query
    }
  }

  override def onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) =
    inflater.inflate(R.menu.query_menu, menu)

  override def onOptionsItemSelected(item: MenuItem): Boolean = {
    if (R.id.query_close == item.getItemId) {
      val activity = getActivity
      val prompt = activity.settings.get(Settings.CLOSE_TAB_PROMPT)
      def removeQuery() {
        activity.service.chans.get(id) foreach { q =>
          val query = q.asInstanceOf[Query]
          activity.service.remove(query)
        }

        activity.service.remove(id)
        activity.adapter.removeTab(activity.adapter.getItemPosition(this))
      }
      if (prompt) {
        val builder = new AlertDialog.Builder(activity)
        builder.setTitle(R.string.query_close_confirm_title)
        builder.setMessage(getString(R.string.query_close_confirm))
        builder.setPositiveButton(R.string.yes, removeQuery _)
        builder.setNegativeButton(R.string.no, null)
        builder.create().show()
        return true
      } else
        removeQuery()
      return true
    }
    false
  }
}

class ServerMessagesFragment(var server: Server)
extends MessagesFragment(if (server != null) server.messages else null) {
  var tag = getFragmentTag(server)
  def this() = this(null)
  override def onCreate(bundle: Bundle) {
    super.onCreate(bundle)
    setHasOptionsMenu(true)

    val activity = getActivity
    if (id != -1 && server != null)
      activity.service.add(id, server)

    if (server == null) {
      def setServer(s: IrcService) {
        val _s = s.servs.get(bundle.getInt("id"))
        _s.foreach(srv => server = srv)
      }
      if (activity.service != null)
        setServer(activity.service)
      else
        UiBus += { case BusEvent.ServiceConnected(s) =>
          setServer(s)
          EventBus.Remove
        }
      }
  }

  override def onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
    if (server == null) return // presumably on a tablet?
    inflater.inflate(R.menu.server_messages_menu, menu)
    val connected = server.state match {
      case Server.State.INITIAL      => false
      case Server.State.DISCONNECTED => false
      case _                         => true
    }

    menu.findItem(R.id.server_connect).setVisible(!connected)
    menu.findItem(R.id.server_disconnect).setVisible(connected)
  }

  override def onOptionsItemSelected(item: MenuItem) : Boolean = {
    if (R.id.server_close == item.getItemId) {
      val service = getActivity.service
      service.remove(id)
      getActivity.adapter.removeTab(getActivity.adapter.getItemPosition(this))
      true
    } else {
      val r = getActivity.servers.onServerMenuItemClicked(item, Option(server))
      if (r) HoneycombSupport.invalidateActionBar()
      r
    }
  }
}

class ServersFragment extends ListFragment with EventBus.RefOwner {
  var service: IrcService = _
  var adapter: ServerArrayAdapter = _
  var _server: Option[Server] = None // currently selected server
  var serverMessagesFragmentShowing: Option[String] = None

  UiBus += {
    case e: BusEvent.ServerAdded   => addListener(e.server)
    case e: BusEvent.ServerChanged => changeListener(e.server)
    case e: BusEvent.ServerRemoved => removeListener(e.server)
  }

  override def onCreate(bundle: Bundle) {
    super.onCreate(bundle)
    setHasOptionsMenu(true)
    setRetainInstance(true) // retain for serverMessagesFragmentShowing
  }

  override def onActivityCreated(bundle: Bundle) {
    super.onActivityCreated(bundle)
    // retain instance results in the list items having the wrong theme?
    // so recreate the adapter here
    adapter = new ServerArrayAdapter(getActivity)
    setListAdapter(adapter)
    if (service != null) {
      service.getServers.foreach(adapter.add)
      adapter.notifyDataSetChanged()
    }
  }

  override def onResume() {
    super.onResume()
    HoneycombSupport.menuItemListener = onServerMenuItemClicked
  }

  override def onCreateView(inflater: LayoutInflater,
      container: ViewGroup, bundle: Bundle) = {
    val v = inflater.inflate(R.layout.fragment_servers, container, false)
    val b = v.findView(TR.add_server)
    b.onClick { getActivity.servers.addServerSetupFragment() }
    v
  }

  override def onListItemClick(list: ListView, v: View, pos: Int, id: Long) {
    v.findView(TR.server_checked_text).setChecked(true)
    val activity = getActivity
    val manager = activity.getSupportFragmentManager
    manager.popBackStack(SERVER_SETUP_STACK,
      FragmentManager.POP_BACK_STACK_INCLUSIVE)
    val server = adapter.getItem(pos)
    HoneycombSupport.invalidateActionBar()
    HoneycombSupport.startActionMode(server)

    if (server.state == Server.State.CONNECTED) {
      activity.input.setVisibility(View.VISIBLE)
      activity.input.setFocusable(true)
    }
    if (activity.isLargeScreen)
      addServerMessagesFragment(server)

    _server = Some(server)
  }

  def onIrcServiceConnected(_service: IrcService) {
    service = _service
    if (adapter != null) {
      adapter.clear()
      service.getServers.foreach(adapter.add)
      adapter.notifyDataSetChanged()
    }
  }

  def clearServerMessagesFragment(mgr: FragmentManager,
      tx: FragmentTransaction = null) {
    var mytx: FragmentTransaction = null
    if (tx == null) // need mytx to commit if set
      mytx = mgr.beginTransaction()

    serverMessagesFragmentShowing foreach { name =>
      val f = mgr.findFragmentByTag(name)
      if (f != null) {
        if (mytx != null)
          mytx.hide(f)
        else if (tx != null)
          tx.hide(f)
      }
    }

    if (mytx != null)
      mytx.commit()
  }

  def addServerMessagesFragment(server: Server) {
    val mgr = getActivity.getSupportFragmentManager
    val name = SERVER_MESSAGES_FRAGMENT_PREFIX + server.name
    var fragment = mgr.findFragmentByTag(name).asInstanceOf[MessagesFragment]

    mgr.popBackStack(SERVER_SETUP_STACK,
      FragmentManager.POP_BACK_STACK_INCLUSIVE)
    val tx = mgr.beginTransaction()
    clearServerMessagesFragment(mgr, tx)

    serverMessagesFragmentShowing = Some(name)
    if (fragment == null) {
      fragment = new ServerMessagesFragment(server)
      tx.add(R.id.servers_container, fragment, name)
    } else {
      fragment.adapter = server.messages
      if (fragment.isDetached)
        tx.attach(fragment)
      // fragment is sometimes visible without being shown?
      // showing again shouldn't hurt?
      //if (fragment.isVisible()) return
      tx.show(fragment)
    }

    tx.commit()
  }

  def addServerSetupFragment(_s: Option[Server] = None) {
    val activity = getActivity
    val mgr = activity.getSupportFragmentManager
    var fragment: ServerSetupFragment = null
    fragment = mgr.findFragmentByTag(SERVER_SETUP_FRAGMENT)
      .asInstanceOf[ServerSetupFragment]
    if (fragment == null)
      fragment = new ServerSetupFragment
    if (fragment.isVisible) return

    val server = _s getOrElse {
      val listview = getListView
      val checked = listview.getCheckedItemPosition
      if (AdapterView.INVALID_POSITION != checked)
        listview.setItemChecked(checked, false)
      new Server
    }
    val tx = mgr.beginTransaction()
    clearServerMessagesFragment(mgr, tx)

    if (activity.isLargeScreen) {
      tx.add(R.id.servers_container, fragment, SERVER_SETUP_FRAGMENT)
      tx.addToBackStack(SERVER_SETUP_STACK)
      tx.commit() // can't commit a show
    } else {
      val m = activity.settings.get(Settings.DAYNIGHT_MODE)
      fragment.setStyle(DialogFragment.STYLE_NO_TITLE,
        if (m) R.style.AppTheme_Light else R.style.AppTheme_Dark)
      fragment.show(tx, SERVER_SETUP_FRAGMENT)
    }

    fragment.server = server
    UiBus.post { HoneycombSupport.invalidateActionBar() }
  }

  def changeListener(server: Server) {
    _server map { s =>
      val a = getActivity
      if (s == server && s.state == Server.State.CONNECTED && a != null)
        a.input.setVisibility(View.VISIBLE)
    }
    if (adapter != null)
      adapter.notifyDataSetChanged()
  }

  def removeListener(server: Server) {
    if (adapter != null) {
      adapter.remove(server)
      adapter.notifyDataSetChanged()
    }
  }

  def addListener(server: Server) {
    if (adapter == null) return
    adapter.add(server)
    adapter.notifyDataSetChanged()
  }

  def onServerMenuItemClicked(item: MenuItem, server: Option[Server]):
      Boolean = {
    val R_id_server_delete = R.id.server_delete
    val R_id_server_messages = R.id.server_messages
    val R_id_server_options = R.id.server_options
    val R_id_server_connect = R.id.server_connect
    val R_id_server_disconnect = R.id.server_disconnect
    item.getItemId match {
      case R_id_server_delete => {
        server match {
        case Some(s) => {
          val builder = new AlertDialog.Builder(getActivity)
          val mgr = getActivity.getSupportFragmentManager
          clearServerMessagesFragment(mgr)
          builder.setTitle(R.string.server_confirm_delete)
          builder.setMessage(getActivity.getString(
            R.string.server_confirm_delete_message,
            s.name))
          builder.setPositiveButton(R.string.yes,
            () => {
              service.deleteServer(s)
            })
          builder.setNegativeButton(R.string.no, null)
          builder.create().show()
        }
        case None =>
          Toast.makeText(getActivity,
            R.string.server_not_selected, Toast.LENGTH_SHORT).show()
        }
        true
      }
      case R_id_server_connect => {
        server map service.connect getOrElse {
          Toast.makeText(getActivity, R.string.server_not_selected,
            Toast.LENGTH_SHORT).show()
        }
        true
      }
      case R_id_server_disconnect => {
        server map { service.disconnect(_) } getOrElse {
          Toast.makeText(getActivity, R.string.server_not_selected,
            Toast.LENGTH_SHORT).show()
        }
        true
      }
      case R_id_server_options => {
        addServerSetupFragment(server)
        true
      }
      case R_id_server_messages => {
        server map getActivity.adapter.addServer getOrElse {
          Toast.makeText(getActivity, R.string.server_not_selected,
            Toast.LENGTH_SHORT).show()
        }
        true
      }
      case _ => false
    }
  }

  override def onContextItemSelected(item: MenuItem) =
    onServerMenuItemClicked(item, _server)

  override def onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) =
    inflater.inflate(R.menu.servers_menu, menu)

  override def onOptionsItemSelected(item: MenuItem) : Boolean = {
    if (R.id.add_server == item.getItemId) {
      getActivity.servers.addServerSetupFragment()
      return true
    }
    onServerMenuItemClicked(item, _server)
  }

  override def onPrepareOptionsMenu(menu: Menu) {
    val activity = getActivity
    val m = activity.getSupportFragmentManager

    var page = 0
    if (activity.adapter != null)
      page = activity.adapter.page

    val found = page == 0 && ((0 until m.getBackStackEntryCount) exists {
      i => m.getBackStackEntryAt(i).getName == SERVER_SETUP_STACK
    })

    menu.findItem(R.id.add_server).setVisible(!found)
  }
}

class ServerArrayAdapter(context: Context)
extends ArrayAdapter[Server](
    context, R.layout.server_item, R.id.server_item_text) {

  override def getView(pos: Int, reuseView: View, parent: ViewGroup) = {
    import Server.State._
    val server = getItem(pos)
    val list = parent.asInstanceOf[ListView]
    val v = super.getView(pos, reuseView, parent)
    val checked = list.getCheckedItemPosition
    val img = v.findView(TR.server_item_status)

    v.findView(TR.server_item_progress).setVisibility(
      if (server.state == Server.State.CONNECTING) View.VISIBLE
        else View.INVISIBLE)

    img.setImageResource(server.state match {
      case INITIAL      => android.R.drawable.presence_offline
      case DISCONNECTED => android.R.drawable.presence_busy
      case CONNECTED    => android.R.drawable.presence_online
      case CONNECTING   => android.R.drawable.presence_away
    })

    img.setVisibility(
      if (server.state != Server.State.CONNECTING)
        View.VISIBLE else View.INVISIBLE)

    val t = v.findView(TR.server_checked_text)
    t.setChecked(pos == checked)

    val lag = if (server.state == CONNECTED) {
      val l = server.currentPing flatMap { p =>
        if (server.currentLag == 0) None
          else Some((System.currentTimeMillis - p).toInt)
      } getOrElse server.currentLag
      Server.intervalString(l)
    } else ""
    t.setText(lag)

    v
  }
}
