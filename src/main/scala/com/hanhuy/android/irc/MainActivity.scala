package com.hanhuy.android.irc

import android.annotation.TargetApi
import android.app.{NotificationManager, Activity, AlertDialog}
import android.content.{Context, Intent, DialogInterface}
import android.graphics.{Color, Rect}
import android.graphics.drawable.ColorDrawable
import android.os.{Build, Bundle}
import android.speech.RecognizerIntent
import android.support.design.widget.TabLayout
import android.support.v4.view.{ViewCompat, MotionEventCompat, ViewPager}
import android.util.DisplayMetrics
import android.view.View.MeasureSpec
import android.view._
import android.webkit.{WebChromeClient, WebViewClient, WebView}
import android.widget._

import android.support.v4.app.FragmentManager
import scala.collection.JavaConversions._

import com.hanhuy.android.irc.model._

import MainActivity._
import com.hanhuy.android.common._
import com.hanhuy.android.extensions._
import com.hanhuy.android.conversions._

import android.support.v7.app.{AppCompatActivity, ActionBarDrawerToggle}
import android.support.v7.widget.Toolbar
import android.support.v4.widget.{ViewDragHelper, DrawerLayout}
import android.database.DataSetObserver
import com.hanhuy.android.irc.model.BusEvent

import iota._
import Tweaks._
import Futures._

object MainActivity {
  val MAIN_FRAGMENT         = "mainfrag"
  val SERVERS_FRAGMENT      = "servers-fragment"
  val SERVER_SETUP_FRAGMENT = "serversetupfrag"
  val SERVER_SETUP_STACK    = "serversetup"
  val SERVER_MESSAGES_FRAGMENT_PREFIX = "servermessagesfrag"
  val SERVER_MESSAGES_STACK = "servermessages"

  val log = Logcat("MainActivity")

  if (gingerbreadAndNewer) GingerbreadSupport.init()

  @inline implicit def toMainActivity(a: Activity): MainActivity = a.asInstanceOf[MainActivity]

  def getFragmentTag(c: Option[MessageAppender]) = c match {
    case Some(ch: ChannelLike) =>
      val sinfo = ch.server.?.fold("server-object-null:") { s =>
        "%s::%s::%d::%s::%s::".format(
          s.name, s.hostname, s.port, s.username, s.nickname)
      }

      "fragment:" + sinfo + ch.name
    case Some(s: Server) =>
      "fragment:server:" + s.name
    case None =>
      "none"
  }

  var instance = Option.empty[MainActivity]
}
class MainActivity extends AppCompatActivity with EventBus.RefOwner with ActivityResultManager {
  import ViewGroup.LayoutParams._

  private[this] var requestRecreate = false
  var inputHeight = Option.empty[Int]

  lazy val drawerBackground =
    themeAttrs(R.styleable.AppTheme, _.getColor(R.styleable.AppTheme_qicrDrawerBackground, 0))
  lazy val inputBackground =
    themeAttrs(R.styleable.AppTheme, _.getDrawable(R.styleable.AppTheme_inputBackground))


  def imeShowing = _imeShowing
  private var _imeShowing = false

  lazy val nickcomplete = new ImageButton(this)
  lazy val topdrawer = new FrameLayout(this)
  lazy val bottomdrawer = new FrameLayout(this)
  lazy val qicrdrawers = QicrRelativeLayout(this, toolbar, uparrow, topdrawer, bottomdrawer)

  lazy val drawerWidth = if(sw(600 dp)) 288.dp else 192.dp

  type RLP = RelativeLayout.LayoutParams
  def buttonElevation[V <: View]: Kestrel[V] = condK(v(21) ? elevation(2.dp))
  lazy val mainLayout = c[FrameLayout](IO(drawer)(
    IO(qicrdrawers)(
      IO(tabs) >>= id(Id.tabs) >>=
        lpK(MATCH_PARENT, WRAP_CONTENT) { p: RLP =>
          p.addRule(RelativeLayout.ALIGN_PARENT_TOP, 1)
        } >>= kitkatPaddingTop >>=
        kestrel { t => t.setTabMode(TabLayout.MODE_SCROLLABLE) },
      // id must be set or else fragment manager complains
      IO(pager) >>= id(Id.pager) >>= lpK(MATCH_PARENT, MATCH_PARENT) {
        p: RLP =>
          p.addRule(RelativeLayout.BELOW, Id.tabs)
          p.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM, 1)
      },
      IO(buttonLayout)(
        IO(nickcomplete) >>=
          imageResource(resolveAttr(R.attr.qicrNickCompleteIcon, _.resourceId)) >>=
            hook0.click(IO { proc.nickComplete(input) }) >>= gone >>= buttonTweaks >>=
            buttonElevation,
        IO(newmessages) >>=
          imageResource(resolveAttr(R.attr.qicrNewMessageIcon, _.resourceId)) >>=
          gone >>= hook0.click(IO {
          qicrdrawers.openDrawer(toolbar)
        }) >>= buttonTweaks >>= buttonElevation,
        IO(input) >>= id(Id.input) >>=
          lpK(0, MATCH_PARENT, 1.0f)(margins(all = 4.dp)) >>=
          hint(R.string.input_placeholder) >>= inputTweaks >>= invisible >>=
          padding(left = 8 dp, right = 8 dp) >>=
          backgroundDrawable(inputBackground) >>= kestrel { e =>
            e.setOnEditorActionListener(proc.onEditorActionListener _)
            e.setOnKeyListener(proc.onKeyListener _)
            e.addTextChangedListener(proc.TextListener)
          } >>= buttonElevation,
        IO(send) >>=
          imageResource(resolveAttr(R.attr.qicrSendIcon, _.resourceId)) >>=
          hook0.onClick(IO {
            proc.handleLine(input.getText.toString)
            InputProcessor.clear(input)
          }) >>= buttonTweaks >>= gone >>= buttonElevation,
        IO(speechrec) >>= buttonElevation >>=
          imageResource(resolveAttr(R.attr.qicrSpeechRecIcon, _.resourceId)) >>=
          hook0.onClick(IO {
            val intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
          intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            try {
              val f = requestActivityResult(intent)
              f.onFailureMain { case _ =>
                Toast.makeText(this, R.string.speech_failed, Toast.LENGTH_SHORT).show()
              }
              f.onSuccessMain { case i =>
                val results = i.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                if (results.size == 0) {
                  Toast.makeText(this, R.string.speech_failed, Toast.LENGTH_SHORT).show()
                } else {
                  val eol = Settings.get(Settings.SPEECH_REC_EOL)
                  val clearLine = Settings.get(Settings.SPEECH_REC_CLEAR_LINE)

                  results find { r => r == eol || r == clearLine } match {
                    case Some(c) =>
                      if (c == eol) {
                        proc.handleLine(input.getText.toString)
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
                            val t = input.getText.toString
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
              }
            } catch {
              case x: Exception =>
                log.e("Unable to request speech recognition", x)
                Toast.makeText(this, R.string.speech_unsupported,
                  Toast.LENGTH_SHORT).show()
            }
          }) >>= buttonTweaks
      ) >>= horizontal >>= id(Id.buttonlayout) >>=
        lpK(MATCH_PARENT, 48.dp) { p: RLP =>
          p.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM, 1)
        } >>= kitkatInputMargin,
      topdrawer.!(
        w[ListView] >>= lp(MATCH_PARENT, MATCH_PARENT) >>= kestrel { l =>
          l.setTranscriptMode(AbsListView.TRANSCRIPT_MODE_ALWAYS_SCROLL)
          l.setAdapter(NotificationCenter)
          l.onItemClick { (_, _, pos, _) =>
            def onActiveNotification[A](action: MainPagerAdapter => A): Unit = {
              NotificationCenter.markAllRead ()
              newmessages.setVisibility (View.GONE)
              qicrdrawers.closeDrawer (toolbar)
              action(adapter)
            }
            NotificationCenter.getItem(pos) foreach { n =>
              n match {
                case n@UserMessageNotification(_,_,_,_) =>
                  onActiveNotification(n.action)
                case n@ChannelMessageNotification(_,_,_,_,_) =>
                  onActiveNotification(n.action)
                case _ =>
              }
            }
          }
        } >>= kitkatPaddingRight
      ) >>= kestrel { _.setClickable(true) } >>=
        backgroundColor(drawerBackground) >>= lp(MATCH_PARENT, MATCH_PARENT),
      bottomdrawer.!(
        w[ListView] >>= lp(MATCH_PARENT, MATCH_PARENT) >>= kestrel { l =>
          l.setDivider(new ColorDrawable(Color.TRANSPARENT))
          l.setDividerHeight(0)
          l.setTranscriptMode(AbsListView.TRANSCRIPT_MODE_ALWAYS_SCROLL)
          l.setAdapter(HistoryAdapter)
          l.onItemClick { (_, _, pos, _) =>
            HistoryAdapter.getItem(pos).foreach(input.setText)
            qicrdrawers.closeDrawer(uparrow)
          }
        } >>= kitkatPaddingRight
      ) >>= clickable >>=
        backgroundColor(drawerBackground) >>= lp(MATCH_PARENT, MATCH_PARENT) >>= condK(iota.v(21) ? elevation(12.dp))
      ,
      uparrow.! >>= imageResource(
        resolveAttr(R.attr.qicrInputHistoryIcon, _.resourceId)) >>=
        imageScale(ImageView.ScaleType.CENTER) >>=
        lpK(48.dp, 48.dp) { p: RLP =>
          p.addRule(RelativeLayout.ALIGN_TOP, Id.buttonlayout)
          p.addRule(RelativeLayout.CENTER_HORIZONTAL, 1)
          margins(top = -24.dp)(p)
        } >>= hook0.onClick(IO { qicrdrawers.toggleBottomDrawer() }),
      toolbar.! >>= lpK(MATCH_PARENT, actionBarHeight)(kitkatStatusMargin)
    ) >>= lp(MATCH_PARENT, actionBarHeight),
    IO(drawerLeft)(
      IO(channels) >>= lp(MATCH_PARENT, MATCH_PARENT) >>= listTweaks >>= kitkatPadding
    ) >>=
      lp(drawerWidth, MATCH_PARENT, Gravity.LEFT) >>=
      backgroundColor(drawerBackground),
    IO(drawerRight)(
      IO(userCount) >>= lpK(MATCH_PARENT, WRAP_CONTENT)(
        margins(all = getResources.getDimensionPixelSize(R.dimen.standard_margin))) >>= kitkatPaddingTop,
      IO(nickList) >>= lp(MATCH_PARENT, MATCH_PARENT) >>= listTweaks >>= kitkatPaddingBottom
    ) >>= vertical >>=
      lp(drawerWidth, MATCH_PARENT, Gravity.RIGHT) >>=
      backgroundColor(drawerBackground)
  ) >>= lp(MATCH_PARENT, MATCH_PARENT)).perform()

  lazy val toolbar = newToolbar

  def listTweaks[V <: ListView]: Kestrel[V] = kestrel { l =>
    l.setCacheColorHint(drawerBackground)
    l.setFastScrollEnabled(true)
    l.setChoiceMode(AbsListView.CHOICE_MODE_SINGLE)
    l.setPadding(12 dp, 12 dp, 12 dp, 12 dp)
    if(v(19)) l.setClipToPadding(false)
  }

  lazy val manager: IrcManager = IrcManager.init()
  private var currentPopup = Option.empty[PopupWindow]

  def supportIsDestroyed: Boolean = {
    if (Build.VERSION.SDK_INT >= 17)
      isDestroyed
    else {
      destroyed
    }
  }

  UiBus += {
    case BusEvent.LinkClickEvent(url) =>
      if (!supportIsDestroyed) { // reference holding badness causes this to fail otherwise
        val p = new DisplayMetrics
        getWindow.getWindowManager.getDefaultDisplay.getMetrics(p)
        val popup = new PopupWindow(this)
        val web = new WebView(this)
        val title = new TextView(this)
        val icon = new ImageView(this)
        val progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progress.setIndeterminate(false)
        progress.setProgress(0)
        progress.setMax(100)
        val popupLayout = l[RelativeLayout](
          IO(icon) >>= backgroundColor(0xff26a69a) >>= padding(left = 8 dp) >>=
            imageResource(resolveAttr(R.attr.qicrBrowserOpenIcon, _.resourceId)) >>=
            imageScale(ImageView.ScaleType.CENTER_INSIDE) >>=
            lpK(WRAP_CONTENT, 36 dp) {p: RLP =>
              p.addRule(RelativeLayout.ALIGN_PARENT_TOP, 1)
              p.addRule(RelativeLayout.ALIGN_PARENT_LEFT, 1)
            } >>= id(Id.icon),
          IO(title) >>= text(url) >>= textGravity(Gravity.LEFT | Gravity.CENTER) >>=
            backgroundColor(0xff26A69A) >>= padding(left = 8 dp, top = 4 dp, right = 8 dp, bottom = 4 dp) >>=
            singleLine >>=
            lpK(MATCH_PARENT, 36 dp) { p: RLP =>
              p.addRule(RelativeLayout.ALIGN_PARENT_TOP, 1)
              p.addRule(RelativeLayout.ALIGN_PARENT_RIGHT, 1)
              p.addRule(RelativeLayout.RIGHT_OF, Id.icon)
            } >>= id(Id.title),
          IO(web) >>= lpK(MATCH_PARENT, MATCH_PARENT) { p: RLP =>
            p.addRule(RelativeLayout.BELOW, Id.title)
            p.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM, 1)
          },
          IO(progress) >>= lpK(MATCH_PARENT, 8 dp) { p: RLP =>
            p.addRule(RelativeLayout.BELOW, Id.title)
          }
        ).perform()
        def launchLink(): Unit = {
          val u = web.getUrl.? getOrElse url
          val uri = android.net.Uri.parse(u)
          val intent = new Intent(Intent.ACTION_VIEW, uri)
          intent.putExtra(android.provider.Browser.EXTRA_APPLICATION_ID, getPackageName)
          try {
            startActivity(intent)
          } catch {
            case e: android.content.ActivityNotFoundException =>
              Toast.makeText(this,
                "Activity was not found for intent, " + intent, Toast.LENGTH_SHORT).show()
          }

          popup.dismiss()
        }
        icon onClick0 launchLink()
        title onClick0 launchLink()
        val settings = web.getSettings
        settings.setJavaScriptCanOpenWindowsAutomatically(false)
        settings.setJavaScriptEnabled(true)
        val chrome = new WebChromeClient {
          override def onProgressChanged(view: WebView, newProgress: Int) = {
            progress.setProgress(newProgress)
            if (newProgress == 100) {
              UiBus.handler.postDelayed(() => {
                progress.setVisibility(View.GONE)
              }, 500)
            } else {
              progress.setVisibility(View.VISIBLE)
            }
          }
          override def onReceivedTitle(view: WebView, t: String) =
            title.setText(t)
        }
        val client = new WebViewClient {
          override def shouldOverrideUrlLoading(view: WebView, url: String) =
            !url.startsWith("http") && super.shouldOverrideUrlLoading(view, url)
        }
        web.onLongClick0(true) // disable text selection popups => causes crash
        web.setWebChromeClient(chrome)
        web.setWebViewClient(client)
        web.loadUrl(url)
        popup.setContentView(popupLayout)
        popup.setWidth(p.widthPixels - (if (sw(600 dp)) 128.dp else 64.dp))
        popup.setHeight(p.heightPixels - (192 dp))
        popup.setOutsideTouchable(true)
        popup.setTouchable(true)
        popup.showAtLocation(mainLayout, Gravity.CENTER, 0, 0)
        currentPopup = Some(popup)
        popup.onDismiss {
          web.destroy()
          currentPopup = None
        }
      }
    case BusEvent.IMEShowing(showing) =>
      if (showing) send.setVisibility(View.GONE)
      else if (input.getText.length > 0) send.setVisibility(View.VISIBLE)
      _imeShowing = showing
    case BusEvent.PreferenceChanged(_, key) => key match {
      case Settings.NAVIGATION_MODE =>
        requestRecreate = true
      case _ =>
    }
    case BusEvent.NewNotification => newmessages.setVisibility(View.VISIBLE)
    case BusEvent.ReadNotification => newmessages.setVisibility(View.GONE)
  }

  override def onBackPressed() = {
    currentPopup match {
      case Some(popup) => popup.dismiss()
      case None => super.onBackPressed()
    }
    currentPopup = None
  }

  def showNickComplete = Settings.get(Settings.SHOW_NICK_COMPLETE)
  def showSpeechRec = Settings.get(Settings.SHOW_SPEECH_REC)

  lazy val drawerToggle = new ActionBarDrawerToggle(this,
    drawer, R.string.app_name, R.string.app_name)
  lazy val servers = { // because of retain instance
    getSupportFragmentManager.findFragmentByTag(SERVERS_FRAGMENT).?.fold(new ServersFragment){
    _.asInstanceOf[ServersFragment]}
  }
  lazy val tabs = new TabLayout(this)
  lazy val drawer = new KitKatDrawerLayout(this)
  lazy val drawerLeft = new LinearLayout(this)
  lazy val nickList = new ListView(this)
  lazy val userCount = new TextView(this)
  lazy val drawerRight = new LinearLayout(this)
  lazy val channels = new ListView(this)
  lazy val pager = new ViewPager(this)
  lazy val adapter = new MainPagerAdapter(this)

  lazy val newmessages = new ImageButton(this)

  def setSendVisible(b: Boolean) =
    send.setVisibility(if (b) View.VISIBLE else View.GONE)

  lazy val send = new ImageButton(this)
  lazy val speechrec = new ImageButton(this)
  lazy val buttonLayout = new LinearLayout(this)

  lazy val proc = new MainInputProcessor(this)
  lazy val input = new EditText(this)
  lazy val uparrow = new ImageView(this)
  private var page = -1 // used for restoring tab selection on recreate

  override def onCreate(bundle: Bundle) {
    HoneycombSupport.init(this)
    LifecycleService.start()
    getWindow.setSoftInputMode(
      WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
    manager.start()
    setTheme(if (Settings.get(Settings.DAYNIGHT_MODE)) R.style.AppTheme_Light else R.style.AppTheme_Dark)

    super.onCreate(bundle)
    mainLayout.onPreDraw { l =>
      if (buttonLayout.getMeasuredHeight > 0) {
        mainLayout.getViewTreeObserver.removeOnPreDrawListener(l)
        val lp = buttonLayout.getLayoutParams.asInstanceOf[ViewGroup.MarginLayoutParams]
        inputHeight = Some(buttonLayout.getMeasuredHeight + lp.topMargin)
        true
      } else false
    }
    setContentView(mainLayout)
    ViewCompat.setElevation(toolbar, 4.dp)
    setSupportActionBar(toolbar)

    bundle.?.foreach { b => page = b.getInt("page") }

    adapter.createTab(getString(R.string.tab_servers), servers)

    drawer.setScrimColor(0x11000000)
    drawer.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, drawerRight)
    drawer.setDrawerListener(new DrawerLayout.DrawerListener {
      override def onDrawerClosed(drawerView: View) {
        HoneycombSupport.stopActionMode()
        if (drawerView == drawerLeft)
          drawerToggle.onDrawerClosed(drawerView)
      }

      override def onDrawerOpened(drawerView: View) {
        hideIME()
        if (drawerView == drawerLeft)
          drawerToggle.onDrawerOpened(drawerView)
      }

      override def onDrawerSlide(p1: View, p2: Float) = {
        if (p1 == drawerLeft)
          drawerToggle.onDrawerSlide(p1, p2)
      }

      override def onDrawerStateChanged(p1: Int) =
        drawerToggle.onDrawerStateChanged(p1)
    })
    channels.onItemClick { (_,_,pos,_) =>
      pager.setCurrentItem(pos)
      drawer.closeDrawer(drawerLeft)
    }
    channels.setAdapter(adapter.DropDownAdapter)

    Settings.get(Settings.NAVIGATION_MODE) match {
      case Settings.NAVIGATION_MODE_TABS =>
        drawer.setDrawerLockMode(
          DrawerLayout.LOCK_MODE_LOCKED_CLOSED, drawerLeft)
      case Settings.NAVIGATION_MODE_DRAWER | _ =>
        drawer.setDrawerLockMode(
          DrawerLayout.LOCK_MODE_UNLOCKED, drawerLeft)
        tabs.setVisibility(View.GONE)
    }

    import android.content.pm.ActivityInfo._
    setRequestedOrientation(
      if (Settings.get(Settings.ROTATE_LOCK))
        SCREEN_ORIENTATION_NOSENSOR else SCREEN_ORIENTATION_SENSOR)
  }

  override def onPostCreate(savedInstanceState: Bundle) {
    super.onPostCreate(savedInstanceState)
    val nav = Settings.get(Settings.NAVIGATION_MODE)
    getSupportActionBar.setDisplayShowHomeEnabled(true)
    if (nav == Settings.NAVIGATION_MODE_DRAWER) {
      getSupportActionBar.setDisplayHomeAsUpEnabled(true)
    }
    drawerToggle.syncState()
    drawerToggle.setDrawerIndicatorEnabled(true)
  }


  override def onSearchRequested() = {
    proc.nickComplete(input)
    true // prevent KEYCODE_SEARCH being sent to onKey
  }

  override def onResume() {
    super.onResume()
    val nm = this.systemService[NotificationManager]
    nm.cancelAll()

    def refreshTabs() {
      adapter.refreshTabs()
      getIntent.?.fold {
        if (page != -1) {
          pager.setCurrentItem(page)
          page = -1
        }
      } { i =>
        if (i.hasExtra(IrcManager.EXTRA_SUBJECT)) {
          val subject = i.getStringExtra(IrcManager.EXTRA_SUBJECT)
          // removeExtra so subsequent onResume doesn't select this tab
          i.removeExtra(IrcManager.EXTRA_SUBJECT)
          if (subject == "")
            pager.setCurrentItem(0)
          else {
            val parts = subject.split(IrcManager.EXTRA_SPLITTER)
            if (parts.length == 2) {
              adapter.selectTab(parts(1), parts(0))
              page = -1
            }
          }
        } else if (i.hasExtra(IrcManager.EXTRA_PAGE)) {
          val page = i.getIntExtra(IrcManager.EXTRA_PAGE, 0)
          i.removeExtra(IrcManager.EXTRA_PAGE)
          //tabhost.setCurrentTab(page)
          pager.setCurrentItem(page)
        }
      }
      // scroll tabwidget if necessary
      pageChanged(adapter.page)
    }

    refreshTabs()
    newmessages.setVisibility(if (NotificationCenter.hasImportantNotifications)
      View.VISIBLE else View.GONE)

    if (requestRecreate) _recreate()
  }

  override def onNewIntent(i: Intent) {
    super.onNewIntent(i)
    setIntent(i)
  }

  override def onStart() {
    super.onStart()
    instance = Some(this)
    IrcManager.init()
    ServiceBus.send(BusEvent.MainActivityStart)
    HoneycombSupport.init(this)
  }

  override def onStop() {
    super.onStop()
    instance = None
    ServiceBus.send(BusEvent.MainActivityStop)
    HoneycombSupport.close()
  }

  private[this] var destroyed = false
  override def onDestroy() {
    destroyed = true
    super.onDestroy()
    // unregister, or else we have a memory leak on observer -> this
    nickList.getAdapter.? foreach {
      _.unregisterDataSetObserver(observer)
    }
    ServiceBus.send(BusEvent.MainActivityDestroy)
  }

  def pageChanged(idx: Int) {
    input.setVisibility(if (idx == 0) View.INVISIBLE else View.VISIBLE)
    uparrow.setVisibility(if (idx == 0) View.INVISIBLE else View.VISIBLE)

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
        (IO(nickcomplete) >>= gone).perform()

        (IO(speechrec) >>= condK(showSpeechRec ? visible | gone)).perform()
      case c: ChannelFragment =>

        drawer.setDrawerLockMode(
          DrawerLayout.LOCK_MODE_UNLOCKED, drawerRight)

        nickList.getAdapter.? foreach { a =>
          try { // throws because of the unregister in onStop  :-(
            a.unregisterDataSetObserver(observer)
          } catch { case _: Exception => }
        }
        nickList.setAdapter(NickListAdapter(this, c.channel))
        userCount.setText(
          getString(R.string.users_count,
            nickList.getAdapter.getCount: java.lang.Integer))
        nickList.getAdapter.registerDataSetObserver(observer)
        nickList.onItemClick { (_,_,pos,_) =>
            HoneycombSupport.startNickActionMode(
              nickList.getAdapter.getItem(pos).toString) { item: MenuItem =>
              // TODO refactor this callback (see messageadapter)
              val nick = nickList.getAdapter.getItem(pos).toString.dropWhile(n => Set(' ','@','+')(n))
              item.getItemId match {
                case R.id.nick_whois =>
                  proc.processor.channel = manager.lastChannel
                  proc.processor.WhoisCommand.execute(Some(nick))
                case R.id.channel_log =>
                  c.channel foreach { ch =>
                    startActivity(MessageLogActivity.createIntent(ch, nick))
                  }
                  overridePendingTransition(
                    R.anim.slide_in_left, R.anim.slide_out_right)
                case R.id.nick_ignore =>
                  proc.processor.channel = manager.lastChannel
                  if (Config.Ignores(nick))
                    proc.processor.UnignoreCommand.execute(Some(nick))
                  else
                    proc.processor.IgnoreCommand.execute(Some(nick))
                case R.id.nick_start_chat =>
                  c.channel.foreach { ch =>
                    manager.startQuery(ch.server, nick)
                  }
              }

              ()
            }
            ()
        }

        (IO(nickcomplete) >>= condK(showNickComplete ? visible | gone)).perform()
        (IO(speechrec) >>= condK(showSpeechRec ? visible | gone)).perform()
      case _: ServerMessagesFragment =>
        drawer.setDrawerLockMode(
          DrawerLayout.LOCK_MODE_LOCKED_CLOSED, drawerRight)
        input.setVisibility(View.VISIBLE)
        (IO(nickcomplete) >>= gone).perform()
        (IO(speechrec) >>= gone).perform()
      case _ =>
        drawer.setDrawerLockMode(
          DrawerLayout.LOCK_MODE_LOCKED_CLOSED, drawerRight)
        (IO(nickcomplete) >>= gone).perform()

        (IO(speechrec) >>= gone).perform()
    }
    channels.setItemChecked(idx, true)
  }

  def toggleNickList(): Unit = {
    if (drawer.isDrawerOpen(Gravity.RIGHT))
      drawer.closeDrawer(Gravity.RIGHT)
    else
      drawer.openDrawer(Gravity.RIGHT)
  }
  override def onCreateOptionsMenu(menu: Menu): Boolean = {
    getMenuInflater.inflate(R.menu.main_menu, menu)
    val item = menu.findItem(R.id.toggle_rotate_lock)
    val locked = Settings.get(Settings.ROTATE_LOCK)
    item.setChecked(locked)
    true
  }


  override def onOptionsItemSelected(item: MenuItem) : Boolean = {
    drawerToggle.onOptionsItemSelected(item)
    val R_id_exit = R.id.exit
    val R_id_settings = R.id.settings
    val R_id_toggle_theme = R.id.toggle_theme
    val R_id_toggle_rotate_lock = R.id.toggle_rotate_lock
    item.getItemId match {
    case R_id_exit =>
            exit()
            true
    case R_id_settings =>
      val intent = new Intent(this, classOf[SettingsFragmentActivity])
      startActivity(intent)
      true
    case R_id_toggle_theme =>
      val mode = Settings.get(Settings.DAYNIGHT_MODE)
      Settings.set(Settings.DAYNIGHT_MODE, !mode)
      _recreate()
      true
    case R_id_toggle_rotate_lock =>
      import android.content.pm.ActivityInfo._
      val locked = !item.isChecked
      item.setChecked(locked)
      Settings.set(Settings.ROTATE_LOCK, locked)
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
    val prompt = Settings.get(Settings.QUIT_PROMPT)
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
      userCount.setText(
        getString(R.string.users_count,
          nickList.getAdapter.getCount: java.lang.Integer))
    }
  }

}

// workaround for https://code.google.com/p/android/issues/detail?id=63777
class KitKatDrawerLayout(c: Context) extends DrawerLayout(c) {
  private var baseline = Integer.MAX_VALUE
  private var change = 0

  override def fitSystemWindows(insets: Rect) = {
    val adj = insets.top + insets.bottom
    baseline = math.min(adj, baseline)

    if (baseline != Integer.MAX_VALUE && adj > baseline) {
      change = adj - baseline
      UiBus.send(BusEvent.IMEShowing(true))
    } else if (adj == baseline) {
      change = 0
      UiBus.send(BusEvent.IMEShowing(false))
    }

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

case class QicrRelativeLayout(val activity: Activity, toolbar: Toolbar, input: View, topdrawer: View, bottomdrawer: View) extends RelativeLayout(activity) with HasActivity {
  lazy val vdh = ViewDragHelper.create(this, 1.0f, VdhCallback)

  object VdhCallback extends ViewDragHelper.Callback {
    override def tryCaptureView(child: View, pointerId: Int) = child == toolbar || child == input

    @TargetApi(11)
    override def onViewPositionChanged(changedView: View, left: Int, top: Int, dx: Int, dy: Int) = {
      if (changedView == toolbar) {
        closeDrawer(input)
        topdrawer.layout(0, toolbarStart, getWidth, top)
        if (v(11))
          topdrawer.setAlpha(math.max(toolbarDragOffset(top), 0.1f))
      } else if (changedView == input) {
        closeDrawer(toolbar)
        val bottom = kitkatBottomPadding(activity)
        // uh, why is this a magic number, is it because dragOffset doesn't reach 1.0?
        if (v(11))
          input.setRotation(inputDragOffset(top) * 194)
        val t = (getHeight - bottom) - (inputStart - top)
        val b = getHeight - bottom
        bottomdrawer.layout(0, t, getWidth, b)
        if (v(11))
          bottomdrawer.setAlpha(math.max(inputDragOffset(top), 0.1f))
      }
    }

    override def onViewReleased(releasedChild: View, xvel: Float, yvel: Float) = {
      val newtop: (Boolean => Boolean, Int => Float, Int, Int) => Int = (fling, offset, end, start) =>
        if (fling(offset(releasedChild.getTop) > 0.5f)) end else start

      val info = if (releasedChild == toolbar)
        Some((yvel > 0 ||  yvel == 0 && (_: Boolean), toolbarDragOffset _, toolbarDragRange, toolbarStart))
      else if (releasedChild == input)
        Some((yvel < 0 || yvel == 0 && (_: Boolean), inputDragOffset _, inputDragRange, inputStart))
      else None

      info.foreach { args =>
        val r = vdh.settleCapturedViewAt(releasedChild.getLeft,
          newtop.tupled(args))
        if (r) ViewCompat.postInvalidateOnAnimation(QicrRelativeLayout.this)
      }
    }

    override def clampViewPositionHorizontal(child: View, left: Int, dx: Int) = child.getLeft

    override def clampViewPositionVertical(child: View, top: Int, dy: Int) = {
      if (child == toolbar)
        math.min(math.max(top, toolbarStart), toolbarDragRange)
      else if (child == input)
        math.min(math.max(top, inputDragRange), inputStart)
      else top
    }

    override def getViewVerticalDragRange(child: View) =
      if (child == toolbar) toolbarDragRange
      else if (child == input) inputDragRange
      else 0
  }

  @inline def toolbarDragOffset(top: Int) = top / toolbarDragRange.toFloat
  @inline def inputDragOffset(top: Int) = (inputStart - top) / inputDragRange.toFloat
  @inline def toolbarDragRange = (getHeight - kitkatBottomPadding - toolbar.getHeight) / 2
  @inline def inputDragRange = (getHeight - kitkatBottomPadding - input.getHeight) / 2
  lazy val inputStart = input.getTop
  lazy val toolbarStart = toolbar.getTop

  override def computeScroll() = {
    if (vdh.continueSettling(true))
      ViewCompat.postInvalidateOnAnimation(this)
  }

  override def onInterceptTouchEvent(ev: MotionEvent) = {
    val action = MotionEventCompat.getActionMasked(ev)

    if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_UP) {
      vdh.cancel()
      val x = ev.getX.toInt
      val y = ev.getY.toInt
      val r = new Rect
      if (!vdh.isViewUnder(topdrawer, x, y) && !vdh.isViewUnder(bottomdrawer, x, y) &&
        !vdh.isViewUnder(toolbar, x, y) && !vdh.isViewUnder(input, x, y))
        closeDrawers()
      else false
    } else
      vdh.shouldInterceptTouchEvent(ev)
  }

  // specifically use | not ||
  def closeDrawers() = closeDrawer(input) | closeDrawer(toolbar)

  def openDrawer(view: View) = {
    val info = if (view == toolbar) {
      Some((toolbarDragOffset _, toolbarDragRange))
    } else if (view == input) {
      Some((inputDragOffset _, inputDragRange))
    } else None

    info.fold(false) { case (offset, end) =>
      if (offset(view.getTop) < 0.5f) {
        if (vdh.smoothSlideViewTo(view, view.getLeft, end))
          ViewCompat.postInvalidateOnAnimation(this)
        true
      } else false
    }
  }

  def closeDrawer(view: View) = {
    val info = if (view == toolbar) {
      Some((toolbarDragOffset _, toolbarStart))
    } else if (view == input) {
      Some((inputDragOffset _, inputStart))
    } else None

    info.fold(false) { case (offset, start) =>
      if (offset(view.getTop) > 0.5f) {
        if (vdh.smoothSlideViewTo(view, view.getLeft, start))
          ViewCompat.postInvalidateOnAnimation(this)
        true
      } else false
    }
  }
  def toggleBottomDrawer() = {
    val offset = inputDragOffset(input.getTop)
    if (vdh.smoothSlideViewTo(input, input.getLeft, if (offset > 0.5) inputStart else inputDragRange))
      ViewCompat.postInvalidateOnAnimation(this)
  }

  override def onTouchEvent(ev: MotionEvent) = {
    vdh.processTouchEvent(ev)
    true
  }

  override def onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) = {
    val toolbarCurrent = toolbar.getTop
    val inputCurrent = input.getTop
    val toolbarLayout = (toolbar.getLeft, toolbar.getTop, toolbar.getRight, toolbar.getBottom)
    val inputLayout = (input.getLeft, input.getTop, input.getRight, input.getBottom)
    val layoutToolbar = (toolbar.layout _).tupled
    val layoutInput = (input.layout _).tupled

    super.onLayout(changed, l, t, r, b)

    if (toolbarCurrent != 0) layoutToolbar(toolbarLayout)
    if (inputCurrent != 0) layoutInput(inputLayout)

    val topdrawerHeight = toolbarDragRange - kitkatStatusTopPadding
    val bottomdrawerHeight = inputDragRange - kitkatStatusTopPadding
    topdrawer.getLayoutParams.height = topdrawerHeight
    topdrawer.measure(measureSpec._1,
      MeasureSpec.makeMeasureSpec(topdrawerHeight, MeasureSpec.AT_MOST))
    bottomdrawer.getLayoutParams.height = bottomdrawerHeight
    bottomdrawer.measure(measureSpec._1,
      MeasureSpec.makeMeasureSpec(bottomdrawerHeight, MeasureSpec.AT_MOST))

    topdrawer.layout(0, toolbarStart, getWidth, toolbarCurrent)
    val bottom = kitkatBottomPadding(activity)
    val tp = (getHeight - bottom) - (inputStart - inputCurrent)
    val bt = getHeight - bottom
    bottomdrawer.layout(0, tp, getWidth, bt)

  }

  var measureSpec = (0,0)
  override def onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = {
    measureSpec = (widthMeasureSpec,heightMeasureSpec)
    super.onMeasure(widthMeasureSpec, heightMeasureSpec)
  }
}
