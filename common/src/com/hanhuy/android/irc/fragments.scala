package com.hanhuy.android.irc

import java.util.UUID

import android.app.{Activity, AlertDialog, Dialog}
import android.content.Context
import android.content.DialogInterface
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.{Build, Bundle}
import android.view.ViewTreeObserver.OnPreDrawListener
import android.view._
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView.OnScrollListener
import android.widget._

import android.support.v4.app._

import com.hanhuy.android.irc.model.Channel
import com.hanhuy.android.irc.model.Query
import com.hanhuy.android.irc.model.Server
import com.hanhuy.android.irc.model.MessageAdapter
import com.hanhuy.android.irc.model.BusEvent

import com.hanhuy.android.common.AndroidConversions._
import com.hanhuy.android.common.{RichLogger, UiBus, EventBus}

import MainActivity._
import TypedResource._
import RichLogger.{w => _, _}

import Tweaks._
import macroid._
import macroid.FullDsl._

// TODO remove retainInstance -- messes up with theme change
// TODO fix dialog dismiss on-recreate
class ServerSetupFragment extends DialogFragment {
  val manager = IrcManager.start()
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
      case R_id_save_server =>
        val activity = getActivity
        val fm = activity.getSupportFragmentManager
        val s = server
        if (s.valid) {
          if (s.id == -1)
            manager.addServer(s)
          else
            manager.updateServer(s)
          fm.popBackStack()
        } else {
          Toast.makeText(getActivity, R.string.server_incomplete,
            Toast.LENGTH_SHORT).show()
        }
        true
      case R_id_cancel_server =>
        val manager = getActivity.getSupportFragmentManager
        manager.popBackStack()
        true
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
            manager.addServer(s)
          else
            manager.updateServer(s)
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

abstract class MessagesFragment
extends Fragment with EventBus.RefOwner with Contexts[Fragment] {

  def adapter: Option[MessageAdapter]

  var lookupId: String = ""
  var listView = slot[ListView]

  def tag: String

  def layout = w[ListView] <~ id(android.R.id.list) <~ llMatchParent <~
    kitkatPadding <~ wire(listView) <~ tweak { l: ListView =>
      l.setDrawSelectorOnTop(true)
      l.setDivider(new ColorDrawable(Color.BLACK))
      l.setDividerHeight(0)
      l.setChoiceMode(AbsListView.CHOICE_MODE_NONE)
      l.setTranscriptMode(AbsListView.TRANSCRIPT_MODE_NORMAL)
      l.setSelector(R.drawable.message_selector)
      newerThan(19) ? l.setClipToPadding(false)
      l.setDrawSelectorOnTop(true)
      if (!getActivity.isFinishing)
        l.setAdapter(adapter.get)
      l.setOnScrollListener(new OnScrollListener {
        import OnScrollListener._
        override def onScrollStateChanged(v: AbsListView, s: Int) {
          if (s == SCROLL_STATE_TOUCH_SCROLL || s == SCROLL_STATE_FLING) {
            val imm = getActivity.systemService[InputMethodManager]
            val focused = Option(getActivity.getCurrentFocus)
            focused foreach { f =>
              imm.hideSoftInputFromWindow(f.getWindowToken, 0)
            }
          }
        }

        override def onScroll(p1: AbsListView, p2: Int, p3: Int, p4: Int) {}
      })
    }

  val manager = IrcManager.start()

  override def onCreate(bundle: Bundle) {
    super.onCreate(bundle)
    if (bundle != null)
      lookupId = bundle.getString("channel.key")

    if (!adapter.isDefined) {
      manager.queueCreateActivity(0)
      if (!getActivity.isFinishing)
        getActivity.finish()
    }
  }


  override def onSaveInstanceState(outState: Bundle) = {
    lookupId = UUID.randomUUID.toString
    outState.putString("channel.key", lookupId)
    super.onSaveInstanceState(outState)
  }

  override def onResume() {
    super.onResume()
    adapter foreach (_.context = getActivity)
    scrollToEnd()
  }

  def scrollToEnd() {
    for {
      l <- listView
      a <- adapter
    } l.setSelection(a.getCount - 1)
  }

  override def onCreateView(i: LayoutInflater, c: ViewGroup, b: Bundle) = {
    adapter foreach (_.context = getActivity)
    val v = getUi(layout): View
    def inputHeight = for {
      a <- MainActivity.instance
      h <- a.inputHeight
    } yield h

    inputHeight map { h =>
      val p = v.getPaddingBottom + h
      v.setPadding(v.getPaddingLeft, v.getPaddingTop, v.getPaddingRight, p)
    } getOrElse {
      v.getViewTreeObserver.addOnPreDrawListener(new OnPreDrawListener {
        override def onPreDraw() = {
          val height = inputHeight
          inputHeight exists { h =>
            v.getViewTreeObserver.removeOnPreDrawListener(this)
            val p = v.getPaddingBottom + h
            v.setPadding(
              v.getPaddingLeft, v.getPaddingTop, v.getPaddingRight, p)
            true
          }
        }
      })
    }
    v
  }

}

class ChannelFragment(_channel: Option[Channel])
  extends MessagesFragment with EventBus.RefOwner with Contexts[Fragment] {

  def this() = this(None)

  lazy val channel = _channel orElse {
    IrcManager.instance.get.getChannel(lookupId): Option[Channel]
  }

  override lazy val adapter = channel map (_.messages)

  lazy val tag = getFragmentTag(channel)
  override def onCreate(bundle: Bundle) {
    super.onCreate(bundle)

    setHasOptionsMenu(true)
  }

  override def onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    IrcManager.instance.get.saveChannel(lookupId, channel)
  }

  override def onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) =
    inflater.inflate(R.menu.channel_menu, menu)

  override def onOptionsItemSelected(item: MenuItem): Boolean = {
    if (R.id.channel_close == item.getItemId) {
      val activity = getActivity
      val prompt = activity.settings.get(Settings.CLOSE_TAB_PROMPT)

      channel foreach { c =>
        def removeChannel() {
          if (channel != null && c.state == Channel.State.JOINED) {
            manager.channels.get(c) foreach {
              _.part()
            }
          }
          manager.remove(c)
          activity.adapter.removeTab(activity.adapter.getItemPosition(this))
        }
        if (channel != null && c.state == Channel.State.JOINED && prompt) {
          val builder = new AlertDialog.Builder(activity)
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
      return true
    }
    false
  }
}

class QueryFragment(_query: Option[Query]) extends MessagesFragment {
  def this() = this(None)
  lazy val query = _query orElse {
    IrcManager.instance.get.getChannel(lookupId): Option[Query]
  }
  override lazy val adapter = query map (_.messages)
  lazy val tag = getFragmentTag(query)

  override def onCreate(bundle: Bundle) {
    super.onCreate(bundle)
    setHasOptionsMenu(true)
  }

  override def onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    IrcManager.instance.get.saveChannel(lookupId, query)
  }
  override def onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) =
    inflater.inflate(R.menu.query_menu, menu)

  override def onOptionsItemSelected(item: MenuItem): Boolean = {
    if (R.id.query_close == item.getItemId) {
      val activity = getActivity
      val prompt = activity.settings.get(Settings.CLOSE_TAB_PROMPT)
      def removeQuery() {
        manager.remove(query.get)
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

class ServerMessagesFragment(_server: Option[Server]) extends MessagesFragment {
  def this() = this(None)
  lazy val server = _server orElse {
    IrcManager.instance.get.getChannel(lookupId): Option[Server]
  }
  override lazy val adapter = server map (_.messages)
  lazy val tag = getFragmentTag(server)
  override def onCreate(bundle: Bundle) {
    super.onCreate(bundle)
    setHasOptionsMenu(true)

    val activity = getActivity
  }

  override def onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    IrcManager.instance.get.saveChannel(lookupId, server)
  }
  override def onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
    if (server == null) return // presumably on a tablet?
    inflater.inflate(R.menu.server_messages_menu, menu)
    val connected = server.get.state match {
      case Server.State.INITIAL      => false
      case Server.State.DISCONNECTED => false
      case _                         => true
    }

    menu.findItem(R.id.server_connect).setVisible(!connected)
    menu.findItem(R.id.server_disconnect).setVisible(connected)
  }

  override def onOptionsItemSelected(item: MenuItem) : Boolean = {
    if (R.id.server_close == item.getItemId) {
      getActivity.adapter.removeTab(getActivity.adapter.getItemPosition(this))
      true
    } else {
      val r = getActivity.servers.onServerMenuItemClicked(item, server)
      if (r) HoneycombSupport.invalidateActionBar()
      r
    }
  }
}

class ServersFragment extends ListFragment
with EventBus.RefOwner with Contexts[Fragment] {
  val manager = IrcManager.start()
  var adapter: ServersAdapter = _
  var _server: Option[Server] = None // currently selected server
  var serverMessagesFragmentShowing: Option[String] = None

  lazy val layout = l[LinearLayout](
    l[LinearLayout](
      w[TextView] <~ text(R.string.server_none) <~ llMatchWidth <~
        margin(all = getResources.getDimensionPixelSize(R.dimen.standard_margin)),
      w[Button] <~ id(R.id.add_server) <~ text(R.string.add_server) <~
        On.click {
          getActivity.servers.addServerSetupFragment()
          Ui(true)
        } <~ llMatchWidth <~
        margin(all = getResources.getDimensionPixelSize(R.dimen.standard_margin))
    ) <~ id(android.R.id.empty) <~ vertical <~ llMatchParent <~ kitkatPadding,
    w[ListView] <~ id(android.R.id.list) <~ tweak { l: ListView =>
      l.setChoiceMode(AbsListView.CHOICE_MODE_SINGLE)
      l.setDrawSelectorOnTop(false)
      newerThan(19) ? l.setClipToPadding(false)
    } <~ llMatchParent <~ kitkatPadding
  )

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
    adapter = new ServersAdapter(getActivity)
    setListAdapter(adapter)
    if (manager != null) {
      adapter.notifyDataSetChanged()
    }
  }

  override def onResume() {
    super.onResume()
    HoneycombSupport.menuItemListener = onServerMenuItemClicked
  }

  override def onCreateView(inflater: LayoutInflater,
      container: ViewGroup, bundle: Bundle) = {
    val v = getUi(layout)
    v
  }

  override def onListItemClick(list: ListView, v: View, pos: Int, id: Long) {
    adapter.notifyDataSetChanged()
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
    }
    if (activity.isLargeScreen)
      addServerMessagesFragment(server)

    _server = Some(server)
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
      fragment = new ServerMessagesFragment(Some(server))
      tx.add(R.id.servers_container, fragment, name)
    } else {
      tx.remove(fragment)
      fragment = new ServerMessagesFragment(Some(server))
      tx.add(R.id.servers_container, fragment, name)
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
      adapter.notifyDataSetChanged()
    }
  }

  def addListener(server: Server) {
    if (adapter == null) return
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
      case R_id_server_delete =>
        server match {
        case Some(s) =>
          val builder = new AlertDialog.Builder(getActivity)
          val mgr = getActivity.getSupportFragmentManager
          clearServerMessagesFragment(mgr)
          builder.setTitle(R.string.server_confirm_delete)
          builder.setMessage(getActivity.getString(
            R.string.server_confirm_delete_message,
            s.name))
          builder.setPositiveButton(R.string.yes,
            () => {
              IrcManager.instance.map { _.deleteServer(s) }
              ()
            })
          builder.setNegativeButton(R.string.no, null)
          builder.create().show()
        case None =>
          Toast.makeText(getActivity,
            R.string.server_not_selected, Toast.LENGTH_SHORT).show()
      }
        true
      case R_id_server_connect =>
        server map manager.connect getOrElse {
          Toast.makeText(getActivity, R.string.server_not_selected,
            Toast.LENGTH_SHORT).show()
        }
        true
      case R_id_server_disconnect =>
        server map { manager.disconnect(_) } getOrElse {
          Toast.makeText(getActivity, R.string.server_not_selected,
            Toast.LENGTH_SHORT).show()
        }
        true
      case R_id_server_options =>
        addServerSetupFragment(server)
        true
      case R_id_server_messages =>
        server map getActivity.adapter.addServer getOrElse {
          Toast.makeText(getActivity, R.string.server_not_selected,
            Toast.LENGTH_SHORT).show()
        }
        true
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
  class ServersAdapter(context: Activity) extends BaseAdapter {
    val manager = IrcManager.start()

    override def getCount = manager.getServers.size

    override def getItemId(p1: Int) = p1

    override def getItem(x: Int) = manager.getServers(x)

    override def getView(pos: Int, convertView: View, parent: ViewGroup) = {
      import Server.State._
      val server = getItem(pos)
      val list = parent.asInstanceOf[ListView]

      val v = if (convertView != null) convertView.asInstanceOf[ViewGroup] else
        context.getLayoutInflater.inflate(TR.layout.server_item, parent, false)

      val checked = list.getCheckedItemPosition
      val img = v.findView(TR.server_item_status)

      v.findView(TR.server_item_text).setText(server.name)
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
}


