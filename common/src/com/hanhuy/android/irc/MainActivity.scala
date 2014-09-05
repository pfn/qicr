package com.hanhuy.android.irc

import android.app.{NotificationManager, Activity, AlertDialog}
import android.content.{Context, Intent, DialogInterface}
import android.graphics.Rect
import android.os.{Build, Bundle}
import android.speech.RecognizerIntent
import android.support.v4.view.ViewPager
import android.view.View.MeasureSpec
import android.view.ViewTreeObserver.OnPreDrawListener
import android.view._
import android.view.inputmethod.InputMethodManager
import android.widget._

import android.support.v4.app.FragmentManager
import macroid.contrib.Layouts.RuleRelativeLayout
import scala.collection.JavaConversions._

import com.hanhuy.android.irc.model._

import MainActivity._
import TypedResource._
import com.hanhuy.android.common._
import RichLogger.{w => _, _}

import com.hanhuy.android.common._
import com.viewpagerindicator.TabPageIndicator
import AndroidConversions._
import android.support.v7.app.ActionBarActivity
import android.support.v4.widget.DrawerLayout
import android.database.DataSetObserver
import com.hanhuy.android.irc.model.BusEvent

import macroid._
import macroid.FullDsl._

object MainActivity {
  val MAIN_FRAGMENT         = "mainfrag"
  val SERVERS_FRAGMENT      = "servers-fragment"
  val SERVER_SETUP_FRAGMENT = "serversetupfrag"
  val SERVER_SETUP_STACK    = "serversetup"
  val SERVER_MESSAGES_FRAGMENT_PREFIX = "servermessagesfrag"
  val SERVER_MESSAGES_STACK = "servermessages"

  implicit val TAG = LogcatTag("MainActivity")

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

  var instance = Option.empty[MainActivity]
}
class MainActivity extends ActionBarActivity with EventBus.RefOwner with Contexts[Activity] {
  import Tweaks._
  import ViewGroup.LayoutParams._

  var inputHeight = Option.empty[Int]

  lazy val inputBackground = {
    val themeAttrs = getTheme.obtainStyledAttributes(R.styleable.AppTheme)
    val c = themeAttrs.getDrawable(R.styleable.AppTheme_inputBackground)
    themeAttrs.recycle()
    c
  }

  lazy val drawerBackground = {
    val themeAttrs = getTheme.obtainStyledAttributes(R.styleable.AppTheme)
    val c = themeAttrs.getColor(R.styleable.AppTheme_qicrDrawerBackground, 0)
    themeAttrs.recycle()
    c
  }

  private var nickcomplete = slot[ImageButton]

  lazy val drawerWidth = sw(600 dp) ? (288 dp) | (192 dp)

  import RuleRelativeLayout.Rule
  lazy val mainLayout = l[KitKatDrawerLayout](
    l[RuleRelativeLayout](
      w[TabPageIndicator] <~ id(R.id.tabs) <~
        lp[RuleRelativeLayout](MATCH_PARENT, WRAP_CONTENT,
          Rule(RelativeLayout.ALIGN_PARENT_TOP, 1)),
      w[ViewPager] <~ id(R.id.pager) <~ lp[RuleRelativeLayout](
        MATCH_PARENT, MATCH_PARENT,
        Rule(RelativeLayout.BELOW, R.id.tabs),
        Rule(RelativeLayout.ALIGN_PARENT_BOTTOM, 1)),
      l[LinearLayout](
        w[ImageButton] <~ id(R.id.btn_nick_complete) <~
          image(R.drawable.ic_btn_search) <~ On.click {
          proc.nickComplete(input)
          Ui(true)
        } <~ hide <~ wire(nickcomplete) <~ buttonTweaks,
        w[ImageButton] <~ id(R.id.btn_new_messages) <~
          image(R.drawable.ic_btn_search_go) <~ hide <~ On.click {
          adapter.goToNewMessages()
          Ui(true)
        } <~ wire(_newmessages) <~ buttonTweaks,
        w[EditText] <~ id(R.id.input) <~
          lp[LinearLayout](0, WRAP_CONTENT, 1.0f) <~
          hint(R.string.input_placeholder) <~ inputTweaks <~ hidden <~
          bg(inputBackground) <~ margin(all = 4 dp),
        w[ImageButton] <~ id(R.id.btn_speech_rec) <~
          image(android.R.drawable.ic_btn_speak_now) <~ wire(speechrec) <~
          On.click {
            val intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
          intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            try {
              startActivityForResult(intent, REQUEST_SPEECH_RECOGNITION)
            } catch {
              case x: Exception =>
                e("Unable to request speech recognition", x)
                Toast.makeText(this, R.string.speech_unsupported,
                  Toast.LENGTH_SHORT).show()
            }
            Ui(true)
          } <~ buttonTweaks
      ) <~ horizontal <~
        lp[RuleRelativeLayout](MATCH_PARENT, WRAP_CONTENT,
          Rule(RelativeLayout.ALIGN_PARENT_BOTTOM, 1)) <~ kitkatInputMargin <~
        wire(buttonLayout)
    ) <~ llMatchParent,
    l[LinearLayout](
      w[ListView] <~ id(R.id.channel_list) <~ llMatchParent <~ listTweaks <~ kitkatPadding
    ) <~ id(R.id.drawer_left) <~
      lp[DrawerLayout](drawerWidth, MATCH_PARENT, Gravity.LEFT) <~
      bg(drawerBackground),
    l[LinearLayout](
      w[TextView] <~ id(R.id.user_count) <~ llMatchWidth <~
        margin(all = getResources.getDimensionPixelSize(R.dimen.standard_margin)) <~ kitkatPaddingTop,
      w[ListView] <~ id(R.id.nick_list) <~ llMatchParent <~ listTweaks <~ kitkatPaddingBottom
    ) <~ id(R.id.drawer_right) <~ vertical <~
      lp[DrawerLayout](drawerWidth, MATCH_PARENT, Gravity.RIGHT) <~
      bg(drawerBackground)
  ) <~ id(R.id.drawer_layout) <~
    lp[FrameLayout](MATCH_PARENT, MATCH_PARENT)


  lazy val listTweaks = tweak { l: ListView =>
    l.setCacheColorHint(drawerBackground)
    l.setFastScrollEnabled(true)
    l.setChoiceMode(AbsListView.CHOICE_MODE_SINGLE)
    l.setPadding(12 dp, 12 dp, 12 dp, 12 dp)
    newerThan(19) ? l.setClipToPadding(false)
  }

  private var manager: IrcManager = null
  val _richactivity: RichActivity = this
  import _richactivity.{findView => _, _}
  val _typedactivity: TypedViewHolder = this; import _typedactivity._

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

  private var _newmessages = slot[ImageButton]
  def newmessages = _newmessages.get
  private var speechrec = slot[ImageButton]
  private var buttonLayout = slot[View]

  lazy val proc = new MainInputProcessor(this)
  lazy val input = {
    val i = findView(TR.input)
    i.setOnEditorActionListener(proc.onEditorActionListener _)
    i.setOnKeyListener(proc.onKeyListener _)
    i.addTextChangedListener(proc.TextListener)
    i
  }
  private var page = -1 // used for restoring tab selection on recreate

  override def onCreate(bundle: Bundle) {
    LifecycleService.start()
    getWindow.setSoftInputMode(
      WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
    manager = IrcManager.start()
    val mode = settings.get(Settings.DAYNIGHT_MODE)
    setTheme(if (mode) R.style.AppTheme_Light else R.style.AppTheme_Dark)

    super.onCreate(bundle)
    val view = getUi(mainLayout)
    view.getViewTreeObserver.addOnPreDrawListener(new OnPreDrawListener {
      override def onPreDraw() = {
        buttonLayout exists { l =>
          view.getViewTreeObserver.removeOnPreDrawListener(this)
          val lp = l.getLayoutParams.asInstanceOf[ViewGroup.MarginLayoutParams]
          inputHeight = Some(l.getMeasuredHeight + lp.topMargin)
          true
        }
      }
    })
    setContentView(view)

    if (bundle != null)
      page = bundle.getInt("page")

    adapter.createTab(getString(R.string.tab_servers), servers)

    drawer.setScrimColor(0x11000000)
    drawer.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, drawerRight)
    drawer.setDrawerListener(new DrawerLayout.SimpleDrawerListener {
      override def onDrawerClosed(drawerView: View) {
        HoneycombSupport.stopActionMode()
      }

      override def onDrawerOpened(drawerView: View) {
        val imm = systemService[InputMethodManager]
        val focused = Option(getCurrentFocus)
        focused foreach { f =>
          imm.hideSoftInputFromWindow(f.getWindowToken, 0)
        }
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
    nm.cancel(IrcManager.MENTION_ID)
    nm.cancel(IrcManager.DISCON_ID)
    nm.cancel(IrcManager.PRIVMSG_ID)
    nm.cancel(IrcManager.RUNNING_ID)
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


    def refreshTabs() {
      adapter.refreshTabs()
      val i = getIntent
      if (i != null && i.hasExtra(IrcManager.EXTRA_SUBJECT)) {
        val subject = i.getStringExtra(IrcManager.EXTRA_SUBJECT)
        // removeExtra so subsequent onResume doesn't select this tab
        i.removeExtra(IrcManager.EXTRA_SUBJECT)
        if (subject != null) {
          if (subject == "")
            pager.setCurrentItem(0)
          else {
            val parts = subject.split(IrcManager.EXTRA_SPLITTER)
            if (parts.length == 2) {
              adapter.selectTab(parts(1), parts(0))
              page = -1
            }
          }
        }
      } else if (i != null && i.hasExtra(IrcManager.EXTRA_PAGE)) {
        val page = i.getIntExtra(IrcManager.EXTRA_PAGE, 0)
        //tabhost.setCurrentTab(page)
        pager.setCurrentItem(page)
      } else if (page != -1) {
        pager.setCurrentItem(page)
        page = -1
      }
      // scroll tabwidget if necessary
      pageChanged(adapter.page)
    }

    refreshTabs()
  }

  override def onNewIntent(i: Intent) {
    super.onNewIntent(i)
    setIntent(i)
  }

  override def onStart() {
    super.onStart()
    instance = Some(this)
    IrcManager.start()
    ServiceBus.send(BusEvent.MainActivityStart)
    HoneycombSupport.init(this)
  }

  override def onStop() {
    super.onStop()
    instance = None
    ServiceBus.send(BusEvent.MainActivityStop)
    HoneycombSupport.close()
  }

  override def onDestroy() {
    super.onDestroy()
    // unregister, or else we have a memory leak on observer -> this
    Option(drawerRight.findView(TR.nick_list).getAdapter) foreach {
      _.unregisterDataSetObserver(observer)
    }
  }

  def pageChanged(idx: Int) {
    input.setVisibility(if (idx == 0) View.INVISIBLE else View.VISIBLE)

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
        m.scrollToEnd()
      }
      case _ => ()
    }

    f match {
      case _: QueryFragment =>
        drawer.setDrawerLockMode(
          DrawerLayout.LOCK_MODE_LOCKED_CLOSED, drawerRight)
        runUi(nickcomplete <~ hide,
          speechrec <~ (if (showSpeechRec) show else hide))
      case c: ChannelFragment =>

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
          var nick = nicks.getAdapter.getItem(pos).toString
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
                  proc.processor.channel = manager.lastChannel
                  proc.processor.WhoisCommand.execute(Some(nick))
                case R_id_nick_insert => insertNick(pos)
                case R_id_nick_start_chat =>
                  manager.startQuery(c.channel.server, nick)
              }

              ()
            }
            ()
        }

        runUi(nickcomplete <~ (if (showNickComplete) show else hide),
          speechrec <~ (if (showSpeechRec) show else hide))
      case _: ServerMessagesFragment =>
        drawer.setDrawerLockMode(
          DrawerLayout.LOCK_MODE_LOCKED_CLOSED, drawerRight)
        input.setVisibility(View.VISIBLE)
        runUi(nickcomplete <~ hide, speechrec <~ hide)
      case _ =>
        drawer.setDrawerLockMode(
          DrawerLayout.LOCK_MODE_LOCKED_CLOSED, drawerRight)
        runUi(nickcomplete <~ hide, speechrec <~ hide)
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
    case R_id_exit =>
            exit()
            true
    case R_id_settings =>
      val clazz = if (honeycombAndNewer) classOf[SettingsFragmentActivity]
        else classOf[SettingsActivity]
      val intent = new Intent(this, clazz)
      startActivity(intent)
      true
    case R_id_toggle_theme =>
      val mode = settings.get(Settings.DAYNIGHT_MODE)
      settings.set(Settings.DAYNIGHT_MODE, !mode)
      _recreate()
      true
    case R_id_toggle_rotate_lock =>
      import android.content.pm.ActivityInfo._
      val locked = !item.isChecked
      item.setChecked(locked)
      settings.set(Settings.ROTATE_LOCK, locked)
      setRequestedOrientation(
        if (locked) SCREEN_ORIENTATION_NOSENSOR else SCREEN_ORIENTATION_SENSOR)
      true
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
    if (IrcManager.instance.exists (_.connected) && prompt) {
      val builder = new AlertDialog.Builder(this)
      builder.setTitle(R.string.quit_confirm_title)
      builder.setMessage(getString(R.string.quit_confirm))
      builder.setPositiveButton(R.string.yes,
        () => {
          IrcManager.stop(message, Some(finish _))
        })
      builder.setNegativeButton(R.string.no, null)
      builder.create().show()
    } else {
      IrcManager.stop(message,
        if (manager.connected) Some(finish _) else { finish(); None })
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

// workaround for https://code.google.com/p/android/issues/detail?id=63777
class KitKatDrawerLayout(c: Context) extends DrawerLayout(c) {
  var adjustment = Integer.MAX_VALUE
  var change = 0

  override def fitSystemWindows(insets: Rect) = {
    val adj = insets.top + insets.bottom

    if (adj > adjustment) {
      change = adj - adjustment
    } else if (adj < adjustment) {
      change = 0
    }
    adjustment = adj

    super.fitSystemWindows(insets)
  }

  override def onMeasure(mw: Int, mh: Int) {
    if (Build.VERSION.SDK_INT >= 19) {
      val h = MeasureSpec.getSize(mh)
      val s = MeasureSpec.getMode(mh)
      super.onMeasure(mw, MeasureSpec.makeMeasureSpec(h - change, s))
    } else {
      super.onMeasure(mw, mh)
    }
  }
}
