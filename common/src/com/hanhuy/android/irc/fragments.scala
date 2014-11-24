package com.hanhuy.android.irc

import java.util.UUID

import android.app.{Activity, AlertDialog, Dialog}
import android.content.DialogInterface
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.TypedValue
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
import com.hanhuy.android.common.{UiBus, EventBus}

import MainActivity._
import TypedResource._

import Tweaks._
import macroid._
import macroid.FullDsl._

import scala.util.Try

// TODO fix dialog dismiss on-recreate
class ServerSetupFragment extends DialogFragment with Contexts[Fragment] {
  val manager = IrcManager.start()

  // hack to store text
  var idholder = 0x10001000
  import ViewGroup.LayoutParams._

  lazy val listSepStyle = {
    val tv = new TypedValue
    getActivity.getTheme.resolveAttribute(
      android.R.attr.listSeparatorTextViewStyle, tv, true)
    tv.resourceId
  }
  def header = {
    new TextView(getActivity, null, listSepStyle)
  }
  lazy val label = lp2(WRAP_CONTENT, WRAP_CONTENT) { lp: TableRow.LayoutParams =>
    lp.rightMargin = 12 dp
  }
  lazy val inputTweaks = tweak { e: EditText =>
    e.setSingleLine(true)
    e.setId(idholder)
    idholder = idholder + 1
  } + lp[TableRow](0, WRAP_CONTENT, 1)
  var layoutInit = false
  lazy val layout = {
    layoutInit = true
    getUi(l[ScrollView](
      l[TableLayout](
        header <~ text("Connection Info"),
        l[TableRow](
          w[TextView] <~ label <~ text("Name"),
          w[EditText] <~ inputTweaks <~ hint("required") <~ wire(server_name) <~
            textCapWords
        ) <~ lp[TableLayout](MATCH_PARENT, WRAP_CONTENT),
        l[TableRow](
          w[TextView] <~ label <~ text("Server address"),
          w[EditText] <~ inputTweaks <~ hint("required") <~ wire(server_host) <~
            textUri
        ) <~ lp[TableLayout](MATCH_PARENT, WRAP_CONTENT),
        l[TableRow](
          w[TextView] <~ label <~ text("Port"),
          w[EditText] <~ inputTweaks <~ hint("Default: 6667") <~ wire(port) <~
            number
        ) <~ lp[TableLayout](MATCH_PARENT, WRAP_CONTENT),
        l[TableRow](
          w[View],
          w[CheckBox] <~ label <~ text("Enable Autoconnect") <~ wire(autoconnect)
        ) <~ lp[TableLayout](MATCH_PARENT, WRAP_CONTENT),
        l[TableRow](
          w[View],
          w[CheckBox] <~ label <~ text("Enable SSL") <~ wire(ssl)
        ) <~ lp[TableLayout](MATCH_PARENT, WRAP_CONTENT),
        header <~ text("User Info"),
        l[TableRow](
          w[TextView] <~ label <~ text("Nickname"),
          w[EditText] <~ inputTweaks <~ hint("required") <~ wire(nickname)
        ) <~ lp[TableLayout](MATCH_PARENT, WRAP_CONTENT),
        l[TableRow](
          w[TextView] <~ label <~ text("Alt. nick"),
          w[EditText] <~ inputTweaks <~ hint("Default: <Nickname>_") <~ wire(altnick)
        ) <~ lp[TableLayout](MATCH_PARENT, WRAP_CONTENT),
        l[TableRow](
          w[TextView] <~ label <~ text("Real name"),
          w[EditText] <~ inputTweaks <~ hint("required") <~ wire(realname) <~
            textCapWords
        ) <~ lp[TableLayout](MATCH_PARENT, WRAP_CONTENT),
        l[TableRow](
          w[TextView] <~ label <~ text("Username"),
          w[EditText] <~ inputTweaks <~ hint("required") <~ wire(username)
        ) <~ lp[TableLayout](MATCH_PARENT, WRAP_CONTENT),
        l[TableRow](
          w[TextView] <~ label <~ text("Password"),
          w[EditText] <~ inputTweaks <~ hint("optional") <~ wire(password) <~
            textPassword
        ) <~ lp[TableLayout](MATCH_PARENT, WRAP_CONTENT),
        header <~ text("Session Options"),
        l[TableRow](
          w[TextView] <~ label <~ text("Auto join"),
          w[EditText] <~ inputTweaks <~ hint("#chan1 key;#chan2") <~ wire(autojoin)
        ) <~ lp[TableLayout](MATCH_PARENT, WRAP_CONTENT),
        l[TableRow](
          w[TextView] <~ label <~ text("Auto run"),
          w[EditText] <~ inputTweaks <~ hint("m pfn hi there;") <~ wire(autorun)
        ) <~ lp[TableLayout](MATCH_PARENT, WRAP_CONTENT)
      ) <~ lp[ScrollView](MATCH_PARENT, MATCH_PARENT) <~ margin(all = 8 dp) <~
        tweak { t: TableLayout => t.setColumnStretchable(1, true) }
    ) <~ lp[LinearLayout](MATCH_PARENT, MATCH_PARENT, 1.0f) <~
      (tablet ? kitkatPadding))
  }

  var server_name: EditText = _
  var server_host: EditText = _
  var port: EditText = _
  var ssl: CheckBox = _
  var autoconnect: CheckBox = _
  var nickname: EditText = _
  var altnick: EditText = _
  var realname: EditText = _
  var username: EditText = _
  var password: EditText = _
  var autojoin: EditText = _
  var autorun: EditText = _

  val _server: Server = new Server
  def server: Server = {
    val s = _server
    s.name        = server_name
    s.hostname    = server_host
    s.port        = Try(port.toInt).toOption getOrElse 6667
    s.ssl         = ssl
    s.autoconnect = autoconnect
    s.nickname    = nickname
    s.altnick     = altnick
    s.realname    = realname
    s.username    = username
    s.password    = password
    s.autojoin    = autojoin
    s.autorun     = autorun
    _server
  }
  def server_=(s: Server) = {
    _server.copy(s)
    if (layoutInit && s != null) {
      server_name.setText(s.name)
      server_host.setText(s.hostname)
      port.setText("" + s.port)
      ssl.setChecked(s.ssl)
      autoconnect.setChecked(s.autoconnect)
      nickname.setText(s.nickname)
      altnick.setText(s.altnick)
      realname.setText(s.realname)
      username.setText(s.username)
      password.setText(s.password)
      autojoin.setText(s.autojoin)
      autorun.setText(s.autorun)
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

    val l = layout
    if (bundle == null)
      server = _server
    l
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
      .setView(layout)
      .create()
    if (bundle == null)
      server = _server
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
    kitkatPadding(getActivity.tabs.getVisibility == View.GONE) <~
    wire(listView) <~ tweak { l: ListView =>
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
    manager.getChannel[Channel](lookupId)
  }

  override lazy val adapter = channel map (_.messages)

  lazy val tag = getFragmentTag(channel)
  override def onCreate(bundle: Bundle) {
    super.onCreate(bundle)

    setHasOptionsMenu(true)
  }

  override def onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    channel foreach { c =>
      manager.saveChannel(lookupId, c)
    }
  }

  override def onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) = {
    inflater.inflate(R.menu.channel_menu, menu)
    if (!Settings.get(Settings.IRC_LOGGING)) {
      val item = menu.findItem(R.id.channel_log)
      item.setVisible(false)
    }
  }

  override def onOptionsItemSelected(item: MenuItem): Boolean = {
    if (R.id.nicklist == item.getItemId) {
      MainActivity.instance foreach { _.toggleNickList() }
    }
    if (R.id.channel_log == item.getItemId) {
      startActivity(MessageLogActivity.createIntent(channel.get))
      getActivity.overridePendingTransition(
        R.anim.slide_in_left, R.anim.slide_out_right)
      return true
    }
    if (R.id.channel_close == item.getItemId) {
      val activity = getActivity
      val prompt = Settings.get(Settings.CLOSE_TAB_PROMPT)

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
        if (c.state == Channel.State.JOINED && prompt) {
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
    manager.getChannel[Query](lookupId)
  }
  override lazy val adapter = query map (_.messages)
  lazy val tag = getFragmentTag(query)

  override def onCreate(bundle: Bundle) {
    super.onCreate(bundle)
    setHasOptionsMenu(true)
  }

  override def onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    query foreach { q =>
      manager.saveChannel(lookupId, q)
    }
  }
  override def onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) = {
    inflater.inflate(R.menu.query_menu, menu)
    if (!Settings.get(Settings.IRC_LOGGING)) {
      val item = menu.findItem(R.id.channel_log)
      item.setVisible(false)
    }
  }

  override def onOptionsItemSelected(item: MenuItem): Boolean = {
    if (R.id.query_close == item.getItemId) {
      val activity = getActivity
      val prompt = Settings.get(Settings.CLOSE_TAB_PROMPT)
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
    } else if (R.id.channel_log == item.getItemId) {
      startActivity(MessageLogActivity.createIntent(query.get))
      getActivity.overridePendingTransition(
        R.anim.slide_in_left, R.anim.slide_out_right)
      return true
    }
    false
  }

}

class ServerMessagesFragment(_server: Option[Server]) extends MessagesFragment {
  def this() = this(None)
  lazy val server = _server orElse {
    manager.getChannel[Server](lookupId)
  }
  override lazy val adapter = server map (_.messages)
  lazy val tag = getFragmentTag(server)
  override def onCreate(bundle: Bundle) {
    super.onCreate(bundle)
    setHasOptionsMenu(true)
  }

  override def onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    server foreach { s =>
      manager.saveChannel(lookupId, s)
    }
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

  import ViewGroup.LayoutParams._
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
    ) <~ id(android.R.id.empty) <~ vertical <~ lp[LinearLayout](0, MATCH_PARENT, 1) <~ kitkatPadding(getActivity.tabs.getVisibility == View.GONE),
    w[ListView] <~ id(android.R.id.list) <~ tweak { l: ListView =>
      l.setChoiceMode(AbsListView.CHOICE_MODE_SINGLE)
      l.setDrawSelectorOnTop(false)
      newerThan(19) ? l.setClipToPadding(false)
    } <~ lp[LinearLayout](0, MATCH_PARENT, 1) <~ kitkatPadding(getActivity.tabs.getVisibility == View.GONE)
  ) <~ id(R.id.servers_container) <~ horizontal

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
      val m = Settings.get(Settings.DAYNIGHT_MODE)
      fragment.setStyle(DialogFragment.STYLE_NO_TITLE,
        if (m) R.style.AppTheme_Light else R.style.AppTheme_Dark)
      fragment.show(tx, SERVER_SETUP_FRAGMENT)
    }

    fragment.server = server
    getActivity.input.setVisibility(View.INVISIBLE)
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
              manager.deleteServer(s)
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


