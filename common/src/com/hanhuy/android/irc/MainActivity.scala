package com.hanhuy.android.irc

import android.app.{NotificationManager, Activity, AlertDialog}
import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.{Bundle, IBinder}
import android.content.DialogInterface
import android.speech.RecognizerIntent
import android.util.Log
import android.view.View
import android.view.{Menu, MenuItem, MenuInflater}
import android.widget.{AdapterView, Toast}

import android.support.v4.app.FragmentManager

import scala.collection.JavaConversions._

import com.hanhuy.android.irc.model._

import MainActivity._

import AndroidConversions._
import android.support.v7.app.ActionBarActivity
import android.support.v4.widget.DrawerLayout
import scala.Some
import android.database.DataSetObserver

object MainActivity {
  val MAIN_FRAGMENT         = "mainfrag"
  val SERVERS_FRAGMENT      = "servers-fragment"
  val SERVER_SETUP_FRAGMENT = "serversetupfrag"
  val SERVER_SETUP_STACK    = "serversetup"
  val SERVER_MESSAGES_FRAGMENT_PREFIX = "servermessagesfrag"
  val SERVER_MESSAGES_STACK = "servermessages"

  val TAG = "MainActivity"

  val REQUEST_SPEECH_RECOGNITION = 1

  if (gingerbreadAndNewer) GingerbreadSupport.init()

  implicit def toMainActivity(a: Activity) = a.asInstanceOf[MainActivity]

  def getFragmentTag(s: Server) = if (s != null) "fragment:server:" + s.name
    else "fragment:server:null-server-input"

  def getFragmentTag(c: ChannelLike) = {
    val s = if (c == null) null else c.server
    val sinfo = if (s == null) "server-object-null:"
      else "%s::%s::%d::%s::%s::".format(
        s.name, s.hostname, s.port, s.username, s.nickname)
    "fragment:" + sinfo + (c match {
    case ch: Channel => ch.name 
    case qu: Query   => qu.name
    case _ => "null"
    })
  }
}
class MainActivity extends ActionBarActivity with ServiceConnection
with EventBus.RefOwner {
  val _richactivity: RichActivity = this; import _richactivity._

  lazy val settings = {
    val s = Settings(this)
    UiBus += { case BusEvent.PreferenceChanged(_, key) =>
      List(Settings.SHOW_NICK_COMPLETE,
        Settings.SHOW_SPEECH_REC,
        Settings.NAVIGATION_MODE) foreach { r =>
        if (r == key) {
          r match {
          case Settings.SHOW_NICK_COMPLETE =>
            showNickComplete = s.get(Settings.SHOW_NICK_COMPLETE)
          case Settings.SHOW_SPEECH_REC =>
            showSpeechRec = s.get(Settings.SHOW_SPEECH_REC)
          case Settings.NAVIGATION_MODE =>
             // flag recreate onResume
            toggleSelectorMode = true
          }
        }
      }
    }
    showNickComplete = s.get(Settings.SHOW_NICK_COMPLETE)
    showSpeechRec = s.get(Settings.SHOW_SPEECH_REC)
    s
  }
  private var toggleSelectorMode = false
  private var showNickComplete = false
  private var showSpeechRec = false

  lazy val servers = { // because of retain instance
    val f = getSupportFragmentManager.findFragmentByTag(SERVERS_FRAGMENT)
    if (f != null) f.asInstanceOf[ServersFragment] else new ServersFragment
  }
  lazy val tabs = findView(TR.tabs)
  lazy val drawer = findView(TR.drawer_layout)
  lazy val drawerLeft = findView(TR.drawer_left)
  lazy val drawerRight = findView(TR.drawer_right)
  lazy val channels = drawerLeft.findView(TR.channel_list)
  lazy val pager = findView(TR.pager)
  lazy val adapter = new MainPagerAdapter(this)

  lazy val newmessages = {
    val v = findView(TR.btn_new_messages)
    v.onClick (adapter.goToNewMessages)
    v
  }

  lazy val nickcomplete = {
    val complete = findView(TR.btn_nick_complete)
    complete.onClick { proc.nickComplete(input) }
    complete
  }
  lazy val speechrec = {
    val speech = findView(TR.btn_speech_rec)
    speech.onClick {
      val intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
      intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
      intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
      try {
        startActivityForResult(intent, REQUEST_SPEECH_RECOGNITION)
      } catch {
        case e: Exception => {
          Log.w(TAG, "Unable to request speech recognition", e)
          Toast.makeText(this, R.string.speech_unsupported,
            Toast.LENGTH_SHORT).show()
        }
      }
    }
    speech
  }

  lazy val proc = new MainInputProcessor(this)
  lazy val input = {
    val i = findView(TR.input)
    i.setOnEditorActionListener(proc.onEditorActionListener _)
    i.setOnKeyListener(proc.onKeyListener _)
    i.addTextChangedListener(proc.TextListener)
    i
  }
  private var page = -1 // used for restoring tab selection on recreate

  var _service: IrcService = _
  def service = _service
  def service_=(s: IrcService) {
    _service = s
    UiBus.send(BusEvent.ServiceConnected(s))
  }

  override def onCreate(bundle: Bundle) {
    val mode = settings.get(Settings.DAYNIGHT_MODE)
    setTheme(if (mode) R.style.AppTheme_Light else R.style.AppTheme_Dark)

    super.onCreate(bundle)
    setContentView(R.layout.main)

    if (bundle != null)
      page = bundle.getInt("page")

    adapter.createTab(getString(R.string.tab_servers), servers)

    drawer.setScrimColor(0x11000000)
    drawer.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, drawerRight)
    drawer.setDrawerListener(new DrawerLayout.SimpleDrawerListener {
      override def onDrawerClosed(drawerView: View) {
        HoneycombSupport.stopActionMode()
      }
    })
    channels.setOnItemClickListener { (pos: Int) =>
      pager.setCurrentItem(pos)
      drawer.closeDrawer(drawerLeft)
    }
    channels.setAdapter(adapter.DropDownAdapter)

    HoneycombSupport.init(this)
    settings.get(Settings.NAVIGATION_MODE) match {
      case Settings.NAVIGATION_MODE_DROPDOWN =>
        HoneycombSupport.setupSpinnerNavigation(adapter)
        drawer.setDrawerLockMode(
          DrawerLayout.LOCK_MODE_LOCKED_CLOSED, drawerLeft)
      case Settings.NAVIGATION_MODE_TABS =>
        drawer.setDrawerLockMode(
          DrawerLayout.LOCK_MODE_LOCKED_CLOSED, drawerLeft)
      case Settings.NAVIGATION_MODE_DRAWER =>
        drawer.setDrawerLockMode(
          DrawerLayout.LOCK_MODE_UNLOCKED, drawerLeft)
        tabs.setVisibility(View.GONE)
    }

    import android.content.pm.ActivityInfo._
    setRequestedOrientation(
      if (settings.get(Settings.ROTATE_LOCK))
        SCREEN_ORIENTATION_NOSENSOR else SCREEN_ORIENTATION_SENSOR)
  }

  override def onActivityResult(req: Int, res: Int, i: Intent) {
    if (req != REQUEST_SPEECH_RECOGNITION ||
      res == Activity.RESULT_CANCELED) return
    if (res != Activity.RESULT_OK) {
      Toast.makeText(this, R.string.speech_failed, Toast.LENGTH_SHORT).show()
      return
    }
    val results = i.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)

    if (results.size == 0) {
      Toast.makeText(this, R.string.speech_failed, Toast.LENGTH_SHORT).show()
      return
    }

    val eol = settings.get(Settings.SPEECH_REC_EOL)
    val clearLine = settings.get(Settings.SPEECH_REC_CLEAR_LINE)

    results find { r => r == eol || r == clearLine } match {
    case Some(c) =>
      if (c == eol) {
        proc.handleLine(input.getText)
        InputProcessor.clear(input)
      } else if (c == clearLine) {
        InputProcessor.clear(input)
      }
    case None =>
      val builder = new AlertDialog.Builder(this)
      builder.setTitle(R.string.speech_select)
      builder.setItems(results.toArray(
        new Array[CharSequence](results.size)),
        (d: DialogInterface, which: Int) => {
          input.getText.append(results(which) + " ")

          val rec = results(which).toLowerCase
          if (rec.endsWith(" " + eol) || rec == eol) {
            val t = input.getText
            val line = t.substring(0, t.length() - eol.length() - 1)
            proc.handleLine(line)
            InputProcessor.clear(input)
          } else if (rec == clearLine) {
            InputProcessor.clear(input)
          }
        })
      builder.setNegativeButton(R.string.speech_cancel, null)
      builder.create().show()
    }
  }

  override def onSearchRequested() = {
    proc.nickComplete(input)
    true // prevent KEYCODE_SEARCH being sent to onKey
  }

  override def onResume() {
    super.onResume()
    val nm = systemService[NotificationManager]
    nm.cancel(IrcService.MENTION_ID)
    nm.cancel(IrcService.DISCON_ID)
    nm.cancel(IrcService.PRIVMSG_ID)
    if (toggleSelectorMode) {
      val newnav = settings.get(Settings.NAVIGATION_MODE)
      val isDropNav = HoneycombSupport.isSpinnerNavigation
      if ((isDropNav && newnav != Settings.NAVIGATION_MODE_DROPDOWN) ||
          (!isDropNav && newnav == Settings.NAVIGATION_MODE_DROPDOWN)) {
        UiBus.post { HoneycombSupport.recreate() }
      } else {
        newnav match {
          case Settings.NAVIGATION_MODE_TABS =>
            tabs.setVisibility(View.VISIBLE)
            drawer.setDrawerLockMode(
              DrawerLayout.LOCK_MODE_LOCKED_CLOSED, drawerLeft)
          case Settings.NAVIGATION_MODE_DRAWER =>
            tabs.setVisibility(View.GONE)
            drawer.setDrawerLockMode(
              DrawerLayout.LOCK_MODE_UNLOCKED, drawerLeft)
        }
      }
    }


    if (service != null)
      service.showing = true

    def refreshTabs(s: IrcService = null) {
      adapter.refreshTabs(if (s != null) s else service)
      val i = getIntent
      if (i != null && i.hasExtra(IrcService.EXTRA_SUBJECT)) {
        val subject = i.getStringExtra(IrcService.EXTRA_SUBJECT)
        // removeExtra so subsequent onResume doesn't select this tab
        i.removeExtra(IrcService.EXTRA_SUBJECT)
        if (subject != null) {
          if (subject == "")
            pager.setCurrentItem(0)
          else {
            val parts = subject.split(IrcService.EXTRA_SPLITTER)
            if (parts.length == 2) {
              adapter.selectTab(parts(1), parts(0))
              page = -1
            }
          }
        }
      } else if (i != null && i.hasExtra(IrcService.EXTRA_PAGE)) {
        val page = i.getIntExtra(IrcService.EXTRA_PAGE, 0)
        //tabhost.setCurrentTab(page)
        pager.setCurrentItem(page)
      }
      // scroll tabwidget if necessary
      pageChanged(adapter.page)
    }

    if (service != null) refreshTabs()
    else UiBus += { case BusEvent.ServiceConnected(s) =>
      refreshTabs(s)
      EventBus.Remove
    }
  }

  override def onNewIntent(i: Intent) {
    super.onNewIntent(i)
    setIntent(i)
  }

  override def onStart() {
    super.onStart()
    HoneycombSupport.init(this)
    bindService(new Intent(this, classOf[IrcService]), this,
      Context.BIND_AUTO_CREATE)
  }

  override def onServiceConnected(name: ComponentName, binder: IBinder) {
    service = binder.asInstanceOf[LocalIrcBinder].service
    service.bind(this)
    service.showing = true
    servers.onIrcServiceConnected(service)
    if (page != -1) {
      UiBus.post {
        pager.setCurrentItem(page)
        page = -1
      }
    }
  }

  override def onServiceDisconnected(name : ComponentName) =
    UiBus.send(BusEvent.ServiceDisconnected)

  override def onStop() {
    super.onStop()
    HoneycombSupport.close()

    if (service != null) {
      service.showing = false
      service.unbind()
    }
    unbindService(this)
  }

  override def onDestroy() {
    super.onDestroy()
    // unregister, or else we have a memory leak on observer -> this
    Option(drawerRight.findView(TR.nick_list).getAdapter) foreach {
      _.unregisterDataSetObserver(observer)
    }
  }

  def pageChanged(idx: Int) {
    input.setVisibility(if (idx == 0) View.GONE else View.VISIBLE)

    val m = getSupportFragmentManager
    if ((0 until m.getBackStackEntryCount) exists { i =>
      m.getBackStackEntryAt(i).getName == SERVER_SETUP_STACK
    }) {
      m.popBackStack(SERVER_SETUP_STACK,
        FragmentManager.POP_BACK_STACK_INCLUSIVE)
    }

    val f = adapter.getItem(idx)
    // workaround for disappearing menus, might be required for <3.0
    (0 until adapter.getCount()) foreach { i =>
      adapter.getItem(i).setMenuVisibility(false)
    }
    f.setMenuVisibility(true)

    HoneycombSupport.stopActionMode()
    UiBus.post { HoneycombSupport.invalidateActionBar() }

    f match {
      // post to thread to make sure it shows up when done paging
      case m: MessagesFragment => UiBus.post {
        try {
          m.getListView.setSelection(m.getListAdapter.getCount - 1)
        } catch {
          case e: Exception => Log.w(TAG, "Failed to set list position", e)
        }
      }
      case _ => ()
    }

    f match {
      case _: QueryFragment => {
        drawer.setDrawerLockMode(
          DrawerLayout.LOCK_MODE_LOCKED_CLOSED, drawerRight)
        nickcomplete.setVisibility(View.GONE)
        speechrec.setVisibility(
          if (showSpeechRec) View.VISIBLE else View.GONE)
      }
      case c: ChannelFragment => {

        drawer.setDrawerLockMode(
          DrawerLayout.LOCK_MODE_UNLOCKED, drawerRight)

        val nicks = drawerRight.findView(TR.nick_list)
        Option(nicks.getAdapter) foreach { a =>
          try { // throws because of the unregister in onStop  :-(
            a.unregisterDataSetObserver(observer)
          } catch { case _: Exception => }
        }
        nicks.setAdapter(NickListAdapter(this, c.channel))
        findView(TR.user_count).setText(
          getString(R.string.users_count,
            nicks.getAdapter.getCount: java.lang.Integer))
        nicks.getAdapter.registerDataSetObserver(observer)
        def insertNick(pos: Int) {
          var nick = nicks.getAdapter.getItem(pos).asInstanceOf[String]
          val c = nick.charAt(0)
          if (c == '@' || c == '+')
            nick = nick.substring(1)
          val cursor = input.getSelectionStart
          // TODO make ", " a preference
          nick += (if (cursor == 0) ", " else " ")
          input.getText.insert(cursor, nick)
        }
        nicks.setOnItemClickListener {
          (pos: Int) =>
            HoneycombSupport.startNickActionMode(
              nicks.getAdapter.getItem(pos).toString) { item: MenuItem =>
              // TODO refactor this callback (see messageadapter)
              val R_id_nick_insert = R.id.nick_insert
              val R_id_nick_start_chat = R.id.nick_start_chat
              val R_id_nick_whois = R.id.nick_whois
              var nick = nicks.getAdapter.getItem(pos).toString
              val ch = nick.charAt(0)
              if (ch == '@' || ch == '+')
                nick = nick.substring(1)
              item.getItemId match {
                case R_id_nick_whois =>
                  proc.processor.channel = service.lastChannel
                  proc.processor.WhoisCommand.execute(Some(nick))
                case R_id_nick_insert => insertNick(pos)
                case R_id_nick_start_chat =>
                  service.startQuery(c.channel.server, nick)
              }

              ()
            }
            ()
        }

        nickcomplete.setVisibility(
          if (showNickComplete) View.VISIBLE else View.GONE)
        speechrec.setVisibility(
          if (showSpeechRec) View.VISIBLE else View.GONE)
      }
      case _: ServerMessagesFragment => {
        drawer.setDrawerLockMode(
          DrawerLayout.LOCK_MODE_LOCKED_CLOSED, drawerRight)
        input.setVisibility(View.VISIBLE)
        nickcomplete.setVisibility(View.GONE)
        speechrec.setVisibility(View.GONE)
      }
      case _ => {
        drawer.setDrawerLockMode(
          DrawerLayout.LOCK_MODE_LOCKED_CLOSED, drawerRight)
        nickcomplete.setVisibility(View.GONE)
        speechrec.setVisibility(View.GONE)
      }
    }
    channels.setItemChecked(idx, true)
  }

  override def onCreateOptionsMenu(menu: Menu): Boolean = {
    val inflater = new MenuInflater(this)
    inflater.inflate(R.menu.main_menu, menu)
    val item = menu.findItem(R.id.toggle_rotate_lock)
    val locked = settings.get(Settings.ROTATE_LOCK)
    item.setChecked(locked)
    true
  }

  override def onOptionsItemSelected(item: MenuItem) : Boolean = {
    val R_id_exit = R.id.exit
    val R_id_settings = R.id.settings
    val R_id_toggle_theme = R.id.toggle_theme
    val R_id_toggle_rotate_lock = R.id.toggle_rotate_lock
    item.getItemId match {
    case R_id_exit => {
            exit()
            true
    }
    case R_id_settings => {
      val clazz = if (honeycombAndNewer) classOf[SettingsFragmentActivity]
        else classOf[SettingsActivity]
      val intent = new Intent(this, clazz)
      startActivity(intent)
      true
    }
    case R_id_toggle_theme => {
      val mode = settings.get(Settings.DAYNIGHT_MODE)
      settings.set(Settings.DAYNIGHT_MODE, !mode)
      _recreate()
      true
    }
    case R_id_toggle_rotate_lock => {
      import android.content.pm.ActivityInfo._
      val locked = !item.isChecked
      item.setChecked(locked)
      settings.set(Settings.ROTATE_LOCK, locked)
      setRequestedOrientation(
        if (locked) SCREEN_ORIENTATION_NOSENSOR else SCREEN_ORIENTATION_SENSOR)
      true
    }
    case _ => false
    }
  }

  private def _recreate() { // _recreate for name clash
    HoneycombSupport.recreate()
  }

  override def onSaveInstanceState(state: Bundle) {
    super.onSaveInstanceState(state)
    page = adapter.page
    state.putInt("page", page)
  }

  def exit(message: Option[String] = None) {
    val prompt = settings.get(Settings.QUIT_PROMPT)
    if (service.connected && prompt) {
      val builder = new AlertDialog.Builder(this)
      builder.setTitle(R.string.quit_confirm_title)
      builder.setMessage(getString(R.string.quit_confirm))
      builder.setPositiveButton(R.string.yes,
        () => {
          service.quit(message, Some(finish _))
        })
      builder.setNegativeButton(R.string.no, null)
      builder.create().show()
    } else {
      service.quit(message,
        if (service.connected) Some(finish _) else { finish(); None })
    }
  }
  // interesting, this causes a memory leak--fix by unregistering onStop
  val observer = new DataSetObserver {
    override def onChanged() {
      val nicks = drawerRight.findView(TR.nick_list)
      findView(TR.user_count).setText(
        getString(R.string.users_count,
          nicks.getAdapter.getCount: java.lang.Integer))
    }
  }
}

