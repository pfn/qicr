package com.hanhuy.android.irc

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.res.Configuration
import android.os.{Bundle, Build, IBinder, Parcelable}
import android.content.DialogInterface
import android.speech.RecognizerIntent
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.view.Display
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.{Menu, MenuItem, MenuInflater}
import android.widget.LinearLayout
import android.widget.TabHost
import android.widget.TextView
import android.widget.EditText
import android.widget.Toast

import android.support.v4.app.FragmentActivity
import android.support.v4.app.FragmentManager
import android.support.v4.app.FragmentPagerAdapter
import android.support.v4.view.{ViewPager, MenuItemCompat}

import scala.collection.JavaConversions._

import com.hanhuy.android.irc.model.Server
import com.hanhuy.android.irc.model.ChannelLike
import com.hanhuy.android.irc.model.Channel
import com.hanhuy.android.irc.model.Query
import com.hanhuy.android.irc.model.BusEvent

import MainActivity._

import AndroidConversions._

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
      else format("%s::%s::%d::%s::%s::",
        s.name, s.hostname, s.port, s.username, s.nickname)
    "fragment:" + sinfo + (c match {
    case ch: Channel => ch.name 
    case qu: Query   => qu.name
    case _ => "null"
    })
  }
}
class MainActivity extends FragmentActivity with ServiceConnection
with EventBus.RefOwner {
  val _richactivity: RichActivity = this; import _richactivity._

  lazy val settings = {
    val s = new Settings(this)
    UiBus += { case BusEvent.PreferenceChanged(_, key) =>
      List(R.string.pref_show_nick_complete,
        R.string.pref_show_speech_rec,
        R.string.pref_selector_mode) foreach { r =>
        if (getString(r) == key) {
          r match {
          case R.string.pref_show_nick_complete =>
            showNickComplete = s.getBoolean(r, honeycombAndNewer)
          case R.string.pref_show_speech_rec =>
            showSpeechRec = s.getBoolean(r, true)
          case R.string.pref_selector_mode =>
            toggleSelectorMode = true // flag recreate onResume
          }
        }
      }
    }
    showNickComplete = s.getBoolean(
      R.string.pref_show_nick_complete, honeycombAndNewer)
    showSpeechRec = s.getBoolean(R.string.pref_show_speech_rec, true)
    s
  }
  private var toggleSelectorMode = false;
  private var showNickComplete = false
  private var showSpeechRec = false

  // stuck with tabhost because pulling out tabwidget is a massive pita
  // consider viewpagerindicator in the future?
  lazy val tabhost = {
    val t = findView[TabHost](android.R.id.tabhost)
    t.setup()
    t
  }
  lazy val servers = { // because of retain instance
    val f = getSupportFragmentManager().findFragmentByTag(SERVERS_FRAGMENT)
    if (f != null) f.asInstanceOf[ServersFragment] else new ServersFragment
  }
  lazy val pager = findView[ViewPager](R.id.pager)
  lazy val adapter = new MainPagerAdapter(this)

  lazy val newmessages = {
    val v = findView[View](R.id.btn_new_messages)
    v.setOnClickListener(adapter.goToNewMessages _)
    v
  }

  lazy val nickcomplete = {
    val complete = findView[View](R.id.btn_nick_complete)
    complete.setOnClickListener { () => proc.nickComplete(Some(input)) }
    complete
  }
  lazy val speechrec = {
    val speech = findView[View](R.id.btn_speech_rec)
    speech.setOnClickListener { () =>
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

  lazy val proc = new InputProcessor(this)
  lazy val input = {
    val i = findView[EditText](R.id.input)
    i.setOnEditorActionListener(proc.onEditorActionListener _)
    i.setOnKeyListener(proc.onKeyListener _)
    i.addTextChangedListener(proc.TextListener)
    i
  }
  var page = -1 // used for restoring tab selection on recreate

  var _service: IrcService = _
  def service = _service
  def service_=(s: IrcService) {
    _service = s
    UiBus.send(BusEvent.ServiceConnected(s))
  }

  override def onCreate(bundle: Bundle) {
    val mode = settings.getBoolean(R.string.pref_daynight_mode)
    setTheme(if (mode) R.style.AppTheme_Light else R.style.AppTheme_Dark)

    super.onCreate(bundle);
    setContentView(R.layout.main)

    if (bundle != null)
      page = bundle.getInt("page")

    adapter.createTab(getString(R.string.tab_servers), servers)

    if (honeycombAndNewer) {
      HoneycombSupport.init(this)
      if (settings.getBoolean(R.string.pref_selector_mode))
        HoneycombSupport.setupSpinnerNavigation(adapter)
    }
    import android.content.pm.ActivityInfo._
    setRequestedOrientation(
      if (settings.getBoolean(R.string.pref_rotate_lock))
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

    val eol = settings.getString(
      R.string.pref_speech_rec_eol,
      R.string.pref_speech_rec_eol_default)
    val clearLine = settings.getString(
      R.string.pref_speech_rec_clearline,
      R.string.pref_speech_rec_clearline_default)

    results find { r => r == eol || r == clearLine } match {
    case Some(c) =>
      if (c == eol) {
        proc.handleLine(input.getText())
        InputProcessor.clear(input)
      } else if (c == clearLine) {
        InputProcessor.clear(input)
      }
    case None =>
      var builder = new AlertDialog.Builder(this)
      builder.setTitle(R.string.speech_select)
      builder.setItems(results.toArray(
        new Array[CharSequence](results.size)),
        (d: DialogInterface, which: Int) => {
          input.getText().append(results(which) + " ")

          val rec = results(which).toLowerCase
          if (rec.endsWith(" " + eol) || rec == eol) {
            val t = input.getText()
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
    proc.nickComplete(Some(input))
    true // prevent KEYCODE_SEARCH being sent to onKey
  }

  override def onResume() {
    super.onResume()
    if (honeycombAndNewer && toggleSelectorMode)
      UiBus.post { HoneycombSupport.recreate() }

    if (service != null)
      service.showing = true

    def refreshTabs(s: IrcService = null) {
      adapter.refreshTabs(if (s != null) s else service)
      val i = getIntent()
      if (i != null && i.hasExtra(IrcService.EXTRA_SUBJECT)) {
        val subject = i.getStringExtra(IrcService.EXTRA_SUBJECT)
        // why'd I do removeExtra?
        i.removeExtra(IrcService.EXTRA_SUBJECT)
        if (subject != null) {
          if (subject == "")
            tabhost.setCurrentTab(0)
          else {
            val parts = subject.split(IrcService.EXTRA_SPLITTER)
            if (parts.length == 2)
              adapter.selectTab(parts(1), parts(0))
          }
        }
      } else if (i != null && i.hasExtra(IrcService.EXTRA_PAGE)) {
        val page = i.getIntExtra(IrcService.EXTRA_PAGE, 0)
        //tabhost.setCurrentTab(page)
        pager.setCurrentItem(page)
      }
    }

    if (service != null) refreshTabs()
    else UiBus += { case BusEvent.ServiceConnected(s) =>
      refreshTabs(s)
      EventBus.Remove
    }

    // scroll tabwidget if necessary
    pageChanged(adapter.page)
  }

  override def onNewIntent(i: Intent) {
    super.onNewIntent(i)
    setIntent(i)
  }

  override def onStart() {
    super.onStart()
    if (honeycombAndNewer)
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
        tabhost.setCurrentTab(page)
        page = -1
      }
    }
  }

  override def onServiceDisconnected(name : ComponentName) =
    UiBus.send(BusEvent.ServiceDisconnected)

  override def onStop() {
    super.onStop()
    if (honeycombAndNewer)
      HoneycombSupport.close()
    if (service != null) {
      service.showing = false
      service.unbind()
    }
    unbindService(this)
  }

  def pageChanged(idx: Int) {
    input.setVisibility(if (idx == 0) View.GONE else View.VISIBLE)

    val m = getSupportFragmentManager()
    if ((0 until m.getBackStackEntryCount) exists { i =>
      m.getBackStackEntryAt(i).getName() == SERVER_SETUP_STACK
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

    if (honeycombAndNewer) {
      HoneycombSupport.stopActionMode()
      UiBus.post { HoneycombSupport.invalidateActionBar() }
    }

    f match {
      // post to thread to make sure it shows up when done paging
      case m: MessagesFragment => UiBus.post {
        try {
          m.getListView.setSelection(m.getListAdapter.getCount() - 1)
        } catch {
          case e: Exception => Log.w(TAG, "Failed to set list position", e)
        }
      }
      case _ => ()
    }

    f match {
      case _: QueryFragment => {
        nickcomplete.setVisibility(View.GONE)
        speechrec.setVisibility(
          if (showSpeechRec) View.VISIBLE else View.GONE)
      }
      case _: ChannelFragment => {
        nickcomplete.setVisibility(
          if (showNickComplete) View.VISIBLE else View.GONE)
        speechrec.setVisibility(
          if (showSpeechRec) View.VISIBLE else View.GONE)
      }
      case _: ServerMessagesFragment => {
        input.setVisibility(View.VISIBLE)
        nickcomplete.setVisibility(View.GONE)
        speechrec.setVisibility(View.GONE)
      }
      case _ => {
        nickcomplete.setVisibility(View.GONE)
        speechrec.setVisibility(View.GONE)
      }
    }
  }

  override def onCreateOptionsMenu(menu: Menu): Boolean = {
    val inflater = new MenuInflater(this)
    inflater.inflate(R.menu.main_menu, menu)
    val item = menu.findItem(R.id.toggle_rotate_lock)
    val locked = settings.getBoolean(R.string.pref_rotate_lock)
    if (!honeycombAndNewer) {
      item.setTitle(if (locked) R.string.toggle_rotate_unlock
        else R.string.toggle_rotate_lock)
    }
    item.setChecked(locked)
    true
  }

  override def onOptionsItemSelected(item: MenuItem) : Boolean = {
    item.getItemId() match {
    case R.id.exit => {
            exit()
            true
    }
    case R.id.settings => {
      val clazz = if (honeycombAndNewer) classOf[SettingsFragmentActivity]
        else classOf[SettingsActivity]
      val intent = new Intent(this, clazz)
      startActivity(intent)
      true
    }
    case R.id.toggle_theme => {
      val mode = settings.getBoolean(R.string.pref_daynight_mode)
      settings.set(R.string.pref_daynight_mode, !mode)
      _recreate()
      true
    }
    case R.id.toggle_rotate_lock => {
      import android.content.pm.ActivityInfo._
      val locked = !item.isChecked()
      item.setChecked(locked)
      settings.set(R.string.pref_rotate_lock, locked)
      setRequestedOrientation(
        if (locked) SCREEN_ORIENTATION_NOSENSOR else SCREEN_ORIENTATION_SENSOR)
      true
    }
    case _ => false
    }
  }

  private def _recreate() { // _recreate for name clash
    if (honeycombAndNewer)
      HoneycombSupport.recreate()
    else {
      service.queueCreateActivity(adapter.page)
      finish()
    }
  }

  override def onSaveInstanceState(state: Bundle) {
    super.onSaveInstanceState(state)
    page = adapter.page
    state.putInt("page", page)
  }

  def exit(message: Option[String] = None) {
    val prompt = settings.getBoolean(R.string.pref_quit_prompt, true)
    if (service.connected && prompt) {
      var builder = new AlertDialog.Builder(this)
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
        if (service.connected) Some(finish _) else { finish; None })
    }
  }
}

