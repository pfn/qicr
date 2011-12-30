package com.hanhuy.android.irc

import android.app.Activity
import android.app.NotificationManager
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.res.Configuration
import android.os.{Bundle, Build, IBinder, Parcelable}
import android.util.Log
import android.content.DialogInterface
import android.speech.RecognizerIntent
import android.util.AttributeSet
import android.view.LayoutInflater;
import android.view.{View, ViewGroup}
import android.view.Display
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ContextMenu
import android.view.{Menu, MenuItem, MenuInflater}
import android.widget.LinearLayout
import android.widget.{TabHost, TabWidget}
import android.widget.CheckedTextView
import android.widget.AdapterView
import android.widget.TextView
import android.widget.EditText
import android.widget.CheckBox
import android.widget.Toast
import android.widget.ImageView
import android.widget.HorizontalScrollView
import android.widget.{ListView, ArrayAdapter}

import android.support.v4.app.{Fragment, FragmentActivity, DialogFragment}
import android.support.v4.app.{FragmentTransaction, FragmentManager}
import android.support.v4.app.{ListFragment, FragmentPagerAdapter}
import android.support.v4.view.{ViewPager, MenuItemCompat}

import scala.collection.JavaConversions._
import scala.collection.mutable.HashSet

import com.hanhuy.android.irc.model.MessageAdapter
import com.hanhuy.android.irc.model.Server
import com.hanhuy.android.irc.model.ChannelLike
import com.hanhuy.android.irc.model.Channel
import com.hanhuy.android.irc.model.Query
import com.hanhuy.android.irc.model.NickListAdapter

import MainActivity._

import AndroidConversions._

object MainActivity {
    val MAIN_FRAGMENT         = "mainfrag"
    val SERVER_SETUP_FRAGMENT = "serversetupfrag"
    val SERVER_SETUP_STACK    = "serversetup"
    val SERVER_MESSAGES_FRAGMENT_PREFIX = "servermessagesfrag"
    val SERVER_MESSAGES_STACK = "servermessages"

    val TAG = "MainActivity"

    val REQUEST_SPEECH_RECOGNITION = 1
}
class MainActivity extends FragmentActivity with ServiceConnection {
    val _richactivity: RichActivity = this; import _richactivity._

    lazy val settings = {
        val s = new Settings(this)
        s.preferenceChangedListeners += { key =>
            List(R.string.pref_show_nick_complete,
                    R.string.pref_show_speech_rec) foreach { r =>
                if (getString(r) == key) {
                    r match {
                    case R.string.pref_show_nick_complete =>
                        showNickComplete = s.getBoolean(r, honeycombAndNewer)
                    case R.string.pref_show_speech_rec =>
                        showSpeechRec = s.getBoolean(r, true)
                    }
                }
            }
        }
        showNickComplete = s.getBoolean(
                R.string.pref_show_nick_complete, honeycombAndNewer)
        showSpeechRec = s.getBoolean(R.string.pref_show_speech_rec, true)
        s
    }
    var showNickComplete: Boolean = _
    var showSpeechRec: Boolean = _

    // TODO remove tabhost?
    lazy val tabhost = {
        val t = findView[TabHost](android.R.id.tabhost)
        t.setup()
        t
    }
    lazy val servers = new ServersFragment
    lazy val pager = findView[ViewPager](R.id.pager)
    lazy val adapter = new MainPagerAdapter(
            getSupportFragmentManager(), tabhost, pager)

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
                case e: Exception => {Log.w(TAG,
                        "Unable to request speech recognition", e)
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
    lazy val serversFragment = {
        getSupportFragmentManager().findFragmentByTag(adapter.tabs(0).tag.get)
                .asInstanceOf[ServersFragment]
    }
    var page = -1

    val serviceConnectionListeners    = new HashSet[(IrcService) => Any]
    val serviceDisconnectionListeners = new HashSet[() => Any]

    var _service: IrcService = _
    def service = _service
    def service_=(s: IrcService) {
        _service = s
        serviceConnectionListeners.foreach(_(s))
        serviceConnectionListeners.clear()
    }

    override def onCreate(bundle: Bundle) {
        val mode = settings.getBoolean(R.string.pref_daynight_mode)
        val theme = if (mode) R.style.AppTheme_Light else R.style.AppTheme_Dark
        setTheme(theme)

        super.onCreate(bundle);

        if (bundle != null)
            page = bundle.getInt("page")

        setContentView(R.layout.main)

        adapter.createTab(getString(R.string.tab_servers), servers)

        if (honeycombAndNewer)
            HoneycombSupport.init(this)
    }

    override def onActivityResult(req: Int, res: Int, i: Intent) {
        if (req != REQUEST_SPEECH_RECOGNITION ||
                res == Activity.RESULT_CANCELED) return
        if (res != Activity.RESULT_OK) {
            Toast.makeText(this, R.string.speech_failed,
                    Toast.LENGTH_SHORT).show()
            return
        }
        val results = i.getStringArrayListExtra(
                RecognizerIntent.EXTRA_RESULTS)

        if (results.size == 0) {
            Toast.makeText(this, R.string.speech_failed,
                    Toast.LENGTH_SHORT).show()
            return
        }
        val eol = settings.getString(
                R.string.pref_speech_rec_eol,
                R.string.pref_speech_rec_eol_default)
        val clearLine = settings.getString(
                R.string.pref_speech_rec_clearline,
                R.string.pref_speech_rec_clearline_default)

        results find { r => r == eol || r == clearLine } match {
            case Some(c) => {
                if (c == eol) {
                    proc.handleLine(input.getText())
                    input.setText(null)
                } else if (c == clearLine) {
                    input.setText(null)
                }
            }
            case None => {
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
                        input.setText(null)
                    } else if (rec == clearLine) {
                        input.setText(null)
                    }
                })
                builder.setNegativeButton(R.string.speech_cancel, null)
                builder.create().show()
            }
        }
    }

    override def onSearchRequested() = {
        proc.nickComplete(Some(input))
        true // prevent KEYCODE_SEARCH being sent to onKey
    }

    override def onPause() {
        super.onPause()
        if (service != null)
            service.showing = false
    }
    override def onResume() {
        super.onResume()
        if (service != null)
            service.showing = true
        if (honeycombAndNewer)
            HoneycombSupport.init(this)

        def refreshTabs(s: IrcService = null) {
            adapter.refreshTabs(service)
            val i = getIntent()
            if (i != null && i.hasExtra(IrcService.EXTRA_SUBJECT)) {
                val subject = i.getStringExtra(IrcService.EXTRA_SUBJECT)
                i.removeExtra(IrcService.EXTRA_SUBJECT)
                if (subject != null) {
                    if (subject == "")
                        tabhost.setCurrentTab(0)
                    else {
                        val parts = subject.split(IrcService.EXTRA_SPLITTER)
                        if (parts.length == 2) {
                            val tab = adapter.tabs.indexWhere(t => {
                                t.channel match {
                                case Some(c) => parts(1) == c.name &&
                                        parts(0) == c.server.name
                                case None => false
                                }
                            })
                            //tabhost.setCurrentTab(tab)
                            pager.setCurrentItem(tab)
                            // onpagechanged will scroll the tab
                        }
                    }
                }
            } else if (i != null && i.hasExtra(IrcService.EXTRA_PAGE)) {
                val page = i.getIntExtra(IrcService.EXTRA_PAGE, 0)
                //tabhost.setCurrentTab(page)
                pager.setCurrentItem(page)
            }
        }

        if (service != null) refreshTabs()
        else serviceConnectionListeners += refreshTabs

        pageChanged(adapter.page)
    }
    override def onNewIntent(i: Intent) {
        super.onNewIntent(i)
        setIntent(i)
    }
    override def onStart() {
        super.onStart()
        serviceConnectionListeners += servers.onIrcServiceConnected
        serviceDisconnectionListeners += servers.onIrcServiceDisconnected _

        bindService(new Intent(this, classOf[IrcService]), this,
                Context.BIND_AUTO_CREATE)
    }

    override def onServiceConnected(name : ComponentName, binder : IBinder) {
        service = binder.asInstanceOf[LocalIrcService].instance
        service.bind(this)
        service.showing = true
        if (page != -1) {
            postOnUiThread {
                // why did I do this?
                runOnUiThread { () => tabhost.setCurrentTab(page) }
                page = -1
            }
        }
    }
    override def onServiceDisconnected(name : ComponentName) {
        serviceDisconnectionListeners.foreach(_())
        serviceDisconnectionListeners.clear()
    }

    override def onDestroy() {
        super.onDestroy()
        if (honeycombAndNewer)
            HoneycombSupport.close()
        if (service != null)
            service.unbind()
        unbindService(this)
    }

    def postOnUiThread[A](r: => A) = async { runOnUiThread(r _) }

    def pageChanged(idx: Int) {
        input.setVisibility(if (idx == 0) View.GONE else View.VISIBLE)

        val m = getSupportFragmentManager()
        if ((0 until m.getBackStackEntryCount) find { i =>
                 m.getBackStackEntryAt(i).getName() == SERVER_SETUP_STACK
            } isDefined) {
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
            postOnUiThread { HoneycombSupport.invalidateActionBar() }
        }

        f match {
            // post to thread to make sure it shows up when done paging
            case m: MessagesFragment => postOnUiThread {
                    try {
                        m.getListView.setSelection(
                                m.getListAdapter.getCount() - 1)
                    } catch {
                        case e: Exception => Log.w(TAG,
                                "Failed to set list position", e)
                    }
                }
            case _ => Unit
        }

        if (f.isInstanceOf[QueryFragment]) {
            nickcomplete.setVisibility(View.GONE)
            speechrec.setVisibility(if (showSpeechRec)
                    View.VISIBLE else View.GONE)
        } else if (f.isInstanceOf[ChannelFragment]) {
            nickcomplete.setVisibility(if (showNickComplete)
                    View.VISIBLE else View.GONE)
            speechrec.setVisibility(if (showSpeechRec)
                    View.VISIBLE else View.GONE)
        } else {
            nickcomplete.setVisibility(View.GONE)
            speechrec.setVisibility(View.GONE)
        }
    }

    override def onCreateOptionsMenu(menu: Menu): Boolean = {
        val inflater = new MenuInflater(this)
        inflater.inflate(R.menu.main_menu, menu)
        List(R.id.exit, R.id.settings).foreach { item =>
            MenuItemCompat.setShowAsAction(menu.findItem(item),
                    MenuItem.SHOW_AS_ACTION_IF_ROOM |
                    MenuItem.SHOW_AS_ACTION_WITH_TEXT)
        }
        MenuItemCompat.setShowAsAction(menu.findItem(R.id.toggle_theme),
                MenuItem.SHOW_AS_ACTION_WITH_TEXT)
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
            if (honeycombAndNewer)
                HoneycombSupport.recreate()
            else {
                service.queueCreateActivity(tabhost.getCurrentTab())
                finish()
            }
            true
        }
        case _ => false
        }
    }
    override def onSaveInstanceState(state: Bundle) {
        super.onSaveInstanceState(state)
        page = tabhost.getCurrentTab()
        state.putInt("page", page)
    }

    def exit(message: Option[String] = None) {
        val prompt = settings.getBoolean(
                R.string.pref_quit_prompt, true)
        if (service.connected && prompt) {
            var builder = new AlertDialog.Builder(this)
            builder.setTitle(R.string.quit_confirm_title)
            builder.setMessage(getString(R.string.quit_confirm))
            builder.setPositiveButton(R.string.yes,
                    (d: DialogInterface, id: Int) => {
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

// TODO fix dialog dismiss on-recreate
class ServerSetupFragment extends DialogFragment {
    var _server: Server = _
    var thisview: View = _
    def server = {
        val s = _server
        s.name        = thisview.findView[EditText](R.id.add_server_name)
        s.hostname    = thisview.findView[EditText](R.id.add_server_host)
        s.port        = thisview.findView[EditText](R.id.add_server_port)
        s.ssl         = thisview.findView[CheckBox](R.id.add_server_ssl)
        s.autoconnect = thisview.findView[CheckBox](
                R.id.add_server_autoconnect)
        s.nickname    = thisview.findView[EditText](R.id.add_server_nickname)
        s.altnick     = thisview.findView[EditText](R.id.add_server_altnick)
        s.realname    = thisview.findView[EditText](R.id.add_server_realname)
        s.username    = thisview.findView[EditText](R.id.add_server_username)
        s.password    = thisview.findView[EditText](R.id.add_server_password)
        s.autojoin    = thisview.findView[EditText](R.id.add_server_autojoin)
        s.autorun     = thisview.findView[EditText](R.id.add_server_autorun)
        _server
    }
    def server_=(s: Server) = {
        _server = s
        if (thisview != null && s != null) {
            thisview.findView[EditText](
                    R.id.add_server_name).setText(s.name)
            thisview.findView[EditText](
                    R.id.add_server_host).setText(s.hostname)
            thisview.findView[EditText](
                    R.id.add_server_port).setText("" + s.port)
            thisview.findView[CheckBox](
                    R.id.add_server_ssl).setChecked(s.ssl)
            thisview.findView[CheckBox](
                    R.id.add_server_autoconnect).setChecked(s.autoconnect)
            thisview.findView[EditText](
                    R.id.add_server_nickname).setText(s.nickname)
            thisview.findView[EditText](
                    R.id.add_server_altnick).setText(s.altnick)
            thisview.findView[EditText](
                    R.id.add_server_realname).setText(s.realname)
            thisview.findView[EditText](
                    R.id.add_server_username).setText(s.username)
            thisview.findView[EditText](
                    R.id.add_server_password).setText(s.password)
            thisview.findView[EditText](
                    R.id.add_server_autojoin).setText(s.autojoin)
            thisview.findView[EditText](
                    R.id.add_server_autorun).setText(s.autorun)
        }
    }

    override def onCreate(bundle: Bundle) {
        super.onCreate(bundle)
        setHasOptionsMenu(true)
        setRetainInstance(true)
    }

    override def onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        if (menu.findItem(R.id.save_server) != null) return
        inflater.inflate(R.menu.server_setup_menu, menu)
        List(R.id.save_server, R.id.cancel_server).foreach(item =>
                 MenuItemCompat.setShowAsAction(menu.findItem(item),
                         MenuItem.SHOW_AS_ACTION_IF_ROOM |
                         MenuItem.SHOW_AS_ACTION_WITH_TEXT))
    }
    override def onOptionsItemSelected(item: MenuItem) : Boolean = {
        item.getItemId() match {
            case R.id.save_server => {
                val activity = getActivity().asInstanceOf[MainActivity]
                val manager = activity.getSupportFragmentManager()
                val s = server
                if (s.valid) {
                    if (s.id == -1)
                        activity.service.addServer(s)
                    else
                        activity.service.updateServer(s)
                    manager.popBackStack()
                } else {
                    Toast.makeText(getActivity(), R.string.server_incomplete,
                            Toast.LENGTH_SHORT).show()
                }
                true
            }
            case R.id.cancel_server => {
                val activity = getActivity().asInstanceOf[MainActivity]
                val manager = activity.getSupportFragmentManager()
                manager.popBackStack()
                true
            }
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
        import android.view.ContextThemeWrapper
        val activity = getActivity().asInstanceOf[MainActivity]
        val m = activity.settings.getBoolean(R.string.pref_daynight_mode)
        //val d = new AlertDialog.Builder(new ContextThemeWrapper(activity,
        //    if (m) R.style.AppTheme_Light else R.style.AppTheme_Dark))
        val d = new AlertDialog.Builder(activity)
                .setTitle(R.string.server_details)
                .setPositiveButton(R.string.save_server, null)
                .setNegativeButton(R.string.cancel_server, null)
                .setView(createView(getActivity().getLayoutInflater(), null))
                .create()
        // block dismiss on positive button click
        d.setOnShowListener { () =>
            val b = d.getButton(DialogInterface.BUTTON_POSITIVE)
            b.setOnClickListener { () =>
                val manager = activity.getSupportFragmentManager()
                val s = server
                if (s.valid) {
                    if (s.id == -1)
                        activity.service.addServer(s)
                    else
                        activity.service.updateServer(s)
                    d.dismiss()
                } else {
                    Toast.makeText(getActivity(),
                            R.string.server_incomplete,
                            Toast.LENGTH_SHORT).show()
                }
            }
        }
        d
    }
}

// TODO convert to a Dialog fragment (checkpoint first!)
class MessagesFragment(_a: MessageAdapter = null) extends ListFragment {
    def this() = this(null)
    var _adapter = _a
    lazy val _service = getActivity().asInstanceOf[MainActivity].service
    var __service: IrcService = _
    def service = if (__service != null) __service else _service

    def adapter = _adapter
    def adapter_=(a: MessageAdapter) = {
        _adapter = a
        if (getActivity() != null)
            _adapter.context = getActivity().asInstanceOf[MainActivity]

        setListAdapter(_adapter)
        service.messages += ((id, _adapter))
        try {
            getListView().setSelection(if (adapter.getCount() > 0)
                    _adapter.getCount()-1 else 0)
        } catch {
            case e: Exception => Log.i(TAG, "Content view not ready", e)
        }
    }
    var id = -1

    override def onCreate(bundle: Bundle) {
        super.onCreate(bundle)
        if (id == -1 && bundle != null)
            id = bundle.getInt("id")
        else
            id = service.newMessagesId()
    }

    override def onCreateView(inflater: LayoutInflater,
            container: ViewGroup, bundle: Bundle) : View =
        inflater.inflate(R.layout.fragment_messages, container, false)

    override def onActivityCreated(bundle: Bundle) {
        super.onActivityCreated(bundle)

        val activity = getActivity().asInstanceOf[MainActivity]
        if (adapter != null) {
            val service = activity.service
            adapter.context = activity
            service.messages += ((id, adapter))
            setListAdapter(adapter)
        }
        if (activity.service == null)
            activity.serviceConnectionListeners += onServiceConnected
        else
            onServiceConnected(activity.service)
    }

    override def onResume() {
        super.onResume()
        if (adapter != null)
            getListView().setSelection(adapter.getCount()-1)
    }
    override def onSaveInstanceState(bundle: Bundle) {
        super.onSaveInstanceState(bundle)
        bundle.putInt("id", id)
    }
    def onServiceConnected(service: IrcService) {
        if (adapter == null && id != -1) {
            __service = service
            service.messages.get(id) foreach { adapter = _ }
        }
    }
}

class NickListFragment extends DialogFragment
with AdapterView.OnItemClickListener {
    // sucky hack because of using the view directly
    var activity: MainActivity = _
    var listview: ListView = _
    var showAsDialog = true
    var adapter: Option[NickListAdapter] = None

    override def onActivityCreated(bundle: Bundle) {
        super.onActivityCreated(bundle)
        activity = getActivity().asInstanceOf[MainActivity]
    }
    override def onCreateView(inflater: LayoutInflater,
            container: ViewGroup, bundle: Bundle) : View = {
        listview = inflater.inflate(R.layout.fragment_nicklist,
                container, false).asInstanceOf[ListView]
        if (showAsDialog && adapter.isEmpty)
            getActivity().asInstanceOf[MainActivity].postOnUiThread(dismiss _)
        if (showAsDialog || !honeycombAndNewer)
            registerForContextMenu(listview)
        listview.setOnItemClickListener(this)
        adapter.foreach(listview.setAdapter(_))
        listview
    }

    var contextPos: Int = _
    override def onContextItemSelected(item: MenuItem): Boolean = {
        item.getItemId match {
        case R.id.nick_insert => insertNick()
        case R.id.nick_start_chat => Toast.makeText(activity,
                            "Not implemented yet, use /msg",
                            Toast.LENGTH_SHORT).show()
        }
        true
    }

    override def onCreateContextMenu(menu: ContextMenu,
            v: View, info: ContextMenu.ContextMenuInfo) {
        val i = info.asInstanceOf[AdapterView.AdapterContextMenuInfo]
        contextPos = i.position
        val inflater = activity.getMenuInflater()
        inflater.inflate(R.menu.nicklist_menu, menu)
    }

    def insertNick() {
        var nick = listview.getAdapter().getItem(contextPos)
                .asInstanceOf[String]
        val c = nick.charAt(0)
        if (c == '@' || c == '+')
            nick = nick.substring(1)
        val cursor = activity.input.getSelectionStart
        nick += (if (cursor == 0) ", " else " ")
        activity.input.getText.insert(cursor, nick)
    }
    override def onItemClick(parent: AdapterView[_],
            view: View, pos: Int, id: Long) {
        contextPos = pos
        if (honeycombAndNewer) {
            HoneycombSupport.startActionMode(this)
        } else {
            insertNick()
            if (showAsDialog) dismiss()
        }
    }
}

class ChannelFragment(a: MessageAdapter, c: Channel)
extends MessagesFragment(a) {
    def this() = this(null, null)
    var channel = c
    var nicklist: Option[ListView] = None
    //Log.d(TAG, "Creating ChannelFragment: " + this, new StackTrace)
    override def onCreate(bundle: Bundle) {
        super.onCreate(bundle)
        setHasOptionsMenu(true)

        if (channel == null) {
            val activity = getActivity().asInstanceOf[MainActivity]
            def setChannel(s: IrcService) {
                val c = s.chans.get(bundle.getInt("id"))
                c.foreach(ch => channel = ch.asInstanceOf[Channel])
                //Log.d(TAG, format(
                //        "Loading instance state: %d => %s => %s",
                //        id, channel, this))
            }
            if (activity.service != null)
                setChannel(activity.service)
            else
                activity.serviceConnectionListeners += (setChannel(_))
        //} else {
            //Log.d(TAG, format(
            //        "Created ChannelFragment: %d => %s => %s",
            //        id, channel, this))
        }
    }

    private def setNickListAdapter(list: ListView) {
        val activity = getActivity().asInstanceOf[MainActivity]

        val adapter = new NickListAdapter(activity, channel)
        list.setAdapter(adapter)
        adapter
    }

    override def onCreateView(inflater: LayoutInflater,
            container: ViewGroup, bundle: Bundle) : View = {
        val v = inflater.inflate(R.layout.fragment_channel, container, false)
        val activity = getActivity().asInstanceOf[MainActivity]
        val config = activity.getResources().getConfiguration()
        // show via dialogfragment if < size_large
        if (channel != null && channel.isInstanceOf[Channel] &&
                (config.screenLayout &
                        Configuration.SCREENLAYOUT_SIZE_LARGE) == 
                                Configuration.SCREENLAYOUT_SIZE_LARGE
                || (config.screenLayout &
                        Configuration.SCREENLAYOUT_SIZE_XLARGE) != 0) {
            val f = new NickListFragment
            f.showAsDialog = false
            f.activity = activity
            val view = f.onCreateView(inflater, null, null)
            val list = view.asInstanceOf[ListView]
            nicklist = Some(list)
            setNickListAdapter(list)
            v.findView[ViewGroup](R.id.nicklist_container).addView(view)
            // TODO this puts them one atop the other, even when horizontal?
            //v.asInstanceOf[ViewGroup].addView(view)
        }
        v
    }

    override def onActivityCreated(bundle: Bundle) {
        super.onActivityCreated(bundle)
        val activity = getActivity().asInstanceOf[MainActivity]
        if (id != -1 && channel != null && a != null) {
            activity.service.chans += ((id, channel))
            a.channel = channel
        }
    }

    override def onSaveInstanceState(bundle: Bundle) {
        super.onSaveInstanceState(bundle)
        //Log.d(TAG, format(
        //        "Saving instance state: %d => %s => %s",
        //        id, channel, this))
    }
    override def onCreateOptionsMenu(menu: Menu, inflater: MenuInflater)  {
        if (menu.findItem(R.id.channel_close) != null) return
        inflater.inflate(R.menu.channel_menu, menu)
        List(/*R.id.channel_leave,*/
             R.id.channel_close).foreach(item =>
                     MenuItemCompat.setShowAsAction(menu.findItem(item),
                             MenuItem.SHOW_AS_ACTION_IF_ROOM |
                             MenuItem.SHOW_AS_ACTION_WITH_TEXT))
    }

    override def onOptionsItemSelected(item: MenuItem): Boolean = {
        //if (!(getUserVisibleHint() && isVisible() && isResumed() && isAdded() && isInLayout())) return false
        if (R.id.channel_close == item.getItemId()) {
            val activity = getActivity().asInstanceOf[MainActivity]
            val prompt = activity.settings.getBoolean(
                    R.string.pref_close_tab_prompt, true)

            // TODO FIXME closes the wrong tab/fragment/huh?
            Log.i(TAG, "Requesting tab close for: " + channel + " <= " + id)
            def removeChannel() {
                if (channel.state == Channel.State.JOINED) {
                    activity.service.channels.get(channel) foreach { _.part() }
                }
                activity.service.messages -= id
                activity.service.chans    -= id
                activity.service.channels -= channel
                //Log.d(TAG, "Trying to remove: " + this + " => " + getUserVisibleHint())
                activity.adapter.removeTab(
                        activity.adapter.getItemPosition(this))
            }
            if (channel.state == Channel.State.JOINED && prompt) {
                var builder = new AlertDialog.Builder(activity)
                builder.setTitle(R.string.channel_close_confirm_title)
                builder.setMessage(getString(R.string.channel_close_confirm))
                builder.setPositiveButton(R.string.yes,
                        (d: DialogInterface, id: Int) => {
                    removeChannel()
                    channel.state = Channel.State.PARTED
                })
                builder.setNegativeButton(R.string.no, null)
                builder.create().show()
            } else {
                removeChannel()
            }
            return true
        } else if (R.id.channel_names == item.getItemId()) {
            val activity = getActivity().asInstanceOf[MainActivity]
            val adapter = new NickListAdapter(activity, channel)
            val tx = activity.getSupportFragmentManager().beginTransaction()

            val f = new NickListFragment
            val m = activity.settings.getBoolean(R.string.pref_daynight_mode)
            f.setStyle(DialogFragment.STYLE_NO_TITLE,
                    if (m) R.style.AppTheme_Light else R.style.AppTheme_Dark)
            // should be set in onCreateDialog
            //f.getDialog().setTitle("Names")
            f.adapter = Some(adapter)
            f.show(tx, "nick list")
            return true
        }
        false
    }
}

class QueryFragment(a: MessageAdapter, q: Query)
extends MessagesFragment(a) {
    def query = q
    def this() = this(null, null)

    override def onCreate(bundle: Bundle) {
        super.onCreate(bundle)
        setHasOptionsMenu(true)
    }
    override def onActivityCreated(bundle: Bundle) {
        super.onActivityCreated(bundle)
        if (id != -1 && q != null) {
            val activity = getActivity().asInstanceOf[MainActivity]
            activity.service.chans += ((id, q))
            a.channel = q
        }
    }

    override def onCreateOptionsMenu(menu: Menu, inflater: MenuInflater)  {
        if (menu.findItem(R.id.query_close) != null) return
        inflater.inflate(R.menu.query_menu, menu)
        List(R.id.query_close).foreach(item =>
                     MenuItemCompat.setShowAsAction(menu.findItem(item),
                             MenuItem.SHOW_AS_ACTION_IF_ROOM |
                             MenuItem.SHOW_AS_ACTION_WITH_TEXT))
    }

    override def onOptionsItemSelected(item: MenuItem): Boolean = {
        if (R.id.query_close == item.getItemId()) {
            val activity = getActivity().asInstanceOf[MainActivity]
            val prompt = activity.settings.getBoolean(
                    R.string.pref_close_tab_prompt, true)
            def removeQuery() {
                val query = activity.service.chans(id).asInstanceOf[Query]
                activity.service.queries  -=
                        ((query.server, query.name.toLowerCase()))
                activity.service.messages -= id
                activity.service.chans    -= id
                activity.service.channels -= query
                activity.adapter.removeTab(
                        activity.adapter.getItemPosition(this))
            }
            if (prompt) {
                var builder = new AlertDialog.Builder(activity)
                builder.setTitle(R.string.query_close_confirm_title)
                builder.setMessage(getString(R.string.query_close_confirm))
                builder.setPositiveButton(R.string.yes,
                        (d: DialogInterface, resid: Int) => {
                    removeQuery()
                })
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

class ServersFragment extends ListFragment {
    var thisview: View = _
    var service: IrcService = _
    var adapter: ServerArrayAdapter = _
    var _server: Option[Server] = None // currently selected server
    var serverMessagesFragmentShowing: Option[String] = None
    override def onCreate(bundle: Bundle) {
        super.onCreate(bundle)
        setHasOptionsMenu(true)
        setRetainInstance(true)
        adapter = new ServerArrayAdapter(getActivity())
        setListAdapter(adapter)
        if (service != null) {
            service.getServers.foreach(adapter.add(_))
            adapter.notifyDataSetChanged()
        }
    }

    override def onResume() {
        super.onResume()
        if (honeycombAndNewer)
            HoneycombSupport.menuItemListener = onServerMenuItemClicked
    }

    override def onCreateView(inflater: LayoutInflater,
            container: ViewGroup, bundle: Bundle) : View = {
        thisview = inflater.inflate(R.layout.fragment_servers, container, false)
        thisview
    }

    override def onListItemClick(list: ListView, v: View, pos: Int, id: Long) {
        v.findView[CheckedTextView](R.id.server_item_text).setChecked(true)
        val activity = getActivity().asInstanceOf[MainActivity]
        val manager = activity.getSupportFragmentManager()
        manager.popBackStack(SERVER_SETUP_STACK,
                FragmentManager.POP_BACK_STACK_INCLUSIVE)
        val server = adapter.getItem(pos)
        if (honeycombAndNewer) {
            HoneycombSupport.invalidateActionBar()
            HoneycombSupport.startActionMode(server)
        }

        if (server.state == Server.State.CONNECTED) {
            activity.input.setVisibility(View.VISIBLE)
            activity.input.setFocusable(true)
        }
        addServerMessagesFragment(server)
        _server = Some(server)
    }

    def onIrcServiceConnected(_service: IrcService) {
        service = _service
        if (adapter != null) {
            adapter.clear()
            service.getServers.foreach(adapter.add(_))
            adapter.notifyDataSetChanged()
        }
        service.serverChangedListeners += changeListener
        service.serverAddedListeners   += addListener
        service.serverRemovedListeners += removeListener
    }

    def clearServerMessagesFragment(mgr: FragmentManager,
            tx: FragmentTransaction = null) {
        var mytx: FragmentTransaction = null
        if (tx == null)
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
        val main = getActivity().asInstanceOf[MainActivity]
        val mgr = main.getSupportFragmentManager()
        val name = SERVER_MESSAGES_FRAGMENT_PREFIX + server.name
        var fragment = mgr.findFragmentByTag(name)
                .asInstanceOf[MessagesFragment]

        mgr.popBackStack(SERVER_SETUP_STACK,
                FragmentManager.POP_BACK_STACK_INCLUSIVE)
        val tx = mgr.beginTransaction()
        clearServerMessagesFragment(mgr, tx)

        serverMessagesFragmentShowing = Some(name)
        if (fragment == null) {
            fragment = new MessagesFragment(server.messages)
            tx.add(R.id.servers_container, fragment, name)
        } else {
            fragment.adapter = server.messages
            if (fragment.isVisible()) return
            tx.show(fragment)
        }

        tx.commit()
    }

    def addServerSetupFragment(_s: Option[Server] = None) {
        val main = getActivity().asInstanceOf[MainActivity]
        val mgr = main.getSupportFragmentManager()
        var fragment: ServerSetupFragment = null
        fragment = mgr.findFragmentByTag(SERVER_SETUP_FRAGMENT)
                .asInstanceOf[ServerSetupFragment]
        if (fragment == null)
            fragment = new ServerSetupFragment
        if (fragment.isVisible()) return

        val server = _s match {
            case Some(s) => s
            case None => {
                val listview = getListView()
                val checked = listview.getCheckedItemPosition()
                if (AdapterView.INVALID_POSITION != checked)
                    listview.setItemChecked(checked, false)
                new Server
            }
        }
        val tx = mgr.beginTransaction()
        clearServerMessagesFragment(mgr, tx)

        val config = getActivity().getResources().getConfiguration()

        if ((config.screenLayout & Configuration.SCREENLAYOUT_SIZE_LARGE) == 
                Configuration.SCREENLAYOUT_SIZE_LARGE // 0x3, ugh
                || (config.screenLayout &
                        Configuration.SCREENLAYOUT_SIZE_XLARGE) != 0) {
            tx.add(R.id.servers_container, fragment, SERVER_SETUP_FRAGMENT)
            tx.addToBackStack(SERVER_SETUP_STACK)
            tx.commit() // can't commit a show
        } else {
            val m = main.settings.getBoolean(R.string.pref_daynight_mode)
            fragment.setStyle(DialogFragment.STYLE_NO_TITLE,
                    if (m) R.style.AppTheme_Light else R.style.AppTheme_Dark)
            fragment.show(tx, SERVER_SETUP_FRAGMENT)
        }

        fragment.server = server
        if (honeycombAndNewer)
            thisview.getHandler().post { () =>
                    HoneycombSupport.invalidateActionBar()
            }
    }
    def onIrcServiceDisconnected() {
        service.serverChangedListeners -= changeListener
        service.serverAddedListeners   -= addListener
        service.serverRemovedListeners -= removeListener
    }

    def changeListener(server: Server) {
        if (!_server.isEmpty && _server.get == server &&
                server.state == Server.State.CONNECTED && getActivity() != null)
            getActivity().asInstanceOf[MainActivity]
                    .input.setVisibility(View.VISIBLE)
        if (adapter != null)
            adapter.notifyDataSetChanged()
    }
    def removeListener(server: Server) {
        adapter.remove(server)
        adapter.notifyDataSetChanged()
    }
    def addListener(server: Server) {
        adapter.add(server)
        adapter.notifyDataSetChanged()
    }

    def onServerMenuItemClicked(item: MenuItem, server: Option[Server]):
            Boolean = {
        item.getItemId() match {
            case R.id.server_delete => {
                server match {
                case Some(s) => {
                    var builder = new AlertDialog.Builder(getActivity())
                    val mgr = getActivity().getSupportFragmentManager()
                    clearServerMessagesFragment(mgr)
                    builder.setTitle(R.string.server_confirm_delete)
                    builder.setMessage(getActivity().getString(
                            R.string.server_confirm_delete_message,
                            s.name))
                    builder.setPositiveButton(R.string.yes,
                            (d: DialogInterface, id: Int) => {
                        service.deleteServer(s)
                    })
                    builder.setNegativeButton(R.string.no, null)
                    builder.create().show()
                }
                case None => Toast.makeText(getActivity(),
                        R.string.server_not_selected, Toast.LENGTH_SHORT).show()
                }
                true
            }
            case R.id.server_connect => {
                server match {
                case Some(s) => service.connect(s)
                case None => Toast.makeText(getActivity(),
                        R.string.server_not_selected, Toast.LENGTH_SHORT).show()
                }
                true
            }
            case R.id.server_disconnect => {
                server match {
                case Some(s) => service.disconnect(s)
                case None => Toast.makeText(getActivity(),
                        R.string.server_not_selected, Toast.LENGTH_SHORT).show()
                }
                true
            }
            case R.id.server_options => {
                addServerSetupFragment(server)
                true
            }
            case _ => false
        }
    }
    override def onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        if (menu.findItem(R.id.add_server) != null) return
        inflater.inflate(R.menu.servers_menu, menu)
        val item = menu.findItem(R.id.add_server)
        MenuItemCompat.setShowAsAction(item, MenuItem.SHOW_AS_ACTION_IF_ROOM |
                                         MenuItem.SHOW_AS_ACTION_WITH_TEXT)

        if (!honeycombAndNewer)
            inflater.inflate(R.menu.server_menu, menu)
    }
    override def onOptionsItemSelected(item: MenuItem) : Boolean = {
        val activity = getActivity().asInstanceOf[MainActivity]
        if (R.id.add_server == item.getItemId()) {
            activity.serversFragment.addServerSetupFragment()
            return true
        }
        getActivity().asInstanceOf[MainActivity]
                .serversFragment.onServerMenuItemClicked(item, _server)
    }
    override def onPrepareOptionsMenu(menu: Menu) {
        val activity = getActivity().asInstanceOf[MainActivity]
        val m = activity.getSupportFragmentManager()

        var page = 0
        if (activity.adapter != null)
            page = activity.adapter.page

        val found = page == 0 && ((0 until m.getBackStackEntryCount) find {
                i => m.getBackStackEntryAt(i).getName() == SERVER_SETUP_STACK
            } isDefined)

        menu.findItem(R.id.add_server).setVisible(!found)

        if (!honeycombAndNewer) {
            if (getListView().getCheckedItemPosition() == -1)
                _server = None

            val connected = !_server.isEmpty && { _server.get.state match {
                case Server.State.INITIAL      => false
                case Server.State.DISCONNECTED => false
                case _                         => true
            }}

            menu.findItem(R.id.server_options).setVisible(!_server.isEmpty)
            menu.findItem(R.id.server_delete).setVisible(!_server.isEmpty)
            menu.findItem(R.id.server_connect).setVisible(
                    !connected && !_server.isEmpty)
            menu.findItem(R.id.server_disconnect).setVisible(connected)
        }
    }
}

class ServerArrayAdapter(context: Context)
extends ArrayAdapter[Server](
        context, R.layout.server_item, R.id.server_item_text) {
    override def getView(pos: Int, reuseView: View, parent: ViewGroup) :
            View = {
        import Server.State._
        val server = getItem(pos)
        val list = parent.asInstanceOf[ListView]
        val v = super.getView(pos, reuseView, parent)
        val checked = list.getCheckedItemPosition()
        val img = v.findView[ImageView](R.id.server_item_status)

        v.findView[View](R.id.server_item_progress).setVisibility(
                if (server.state == Server.State.CONNECTING)
                        View.VISIBLE else View.INVISIBLE)
        img.setImageResource(server.state match {
            case INITIAL      => android.R.drawable.presence_offline
            case DISCONNECTED => android.R.drawable.presence_busy
            case CONNECTED    => android.R.drawable.presence_online
            case CONNECTING   => android.R.drawable.presence_away
        })
        img.setVisibility(if (server.state != Server.State.CONNECTING)
                View.VISIBLE else View.INVISIBLE)

        v.findView[CheckedTextView](R.id.server_item_text).setChecked(
                pos == checked)
        v
    }
}
