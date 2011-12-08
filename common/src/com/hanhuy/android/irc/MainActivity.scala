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
import android.os.AsyncTask
import android.util.Log
import android.content.DialogInterface
import android.speech.RecognizerIntent
import android.util.AttributeSet
import android.view.LayoutInflater;
import android.view.{View, ViewGroup}
import android.view.Display
import android.view.KeyEvent
import android.view.MotionEvent
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
import android.support.v4.view.{ViewPager, MenuCompat}

import scala.collection.JavaConversions._
import scala.collection.mutable.HashSet

import com.hanhuy.android.irc.model.MessageAdapter
import com.hanhuy.android.irc.model.Server
import com.hanhuy.android.irc.model.ChannelLike
import com.hanhuy.android.irc.model.Channel
import com.hanhuy.android.irc.model.Query
import com.hanhuy.android.irc.model.NickListAdapter

import ViewFinder._
import MainActivity._

import AndroidConversions._

object ViewFinder {

    def findView[T<:View](container: Activity, id : Int) : T =
            container.findViewById(id).asInstanceOf[T]
    def findView[T<:View](container: View, id : Int) : T =
            container.findViewById(id).asInstanceOf[T]
}
object MainActivity {
    val MAIN_FRAGMENT         = "mainfrag"
    val SERVER_SETUP_FRAGMENT = "serversetupfrag"
    val SERVER_SETUP_STACK    = "serversetup"
    val SERVER_MESSAGES_FRAGMENT_PREFIX = "servermessagesfrag"
    val SERVER_MESSAGES_STACK = "servermessages"

    val TAG = "MainActivity"

    val honeycombAndNewer =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB

    val REQUEST_SPEECH_RECOGNITION = 1
}
class MainActivity extends FragmentActivity with ServiceConnection {
    var tabhost: TabHost = _
    var servers: ServersFragment = _
    var adapter: MainPagerAdapter = _
    var proc: InputProcessor = _
    var input: EditText = _
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
        if (!honeycombAndNewer)
            setTheme(android.R.style.Theme_NoTitleBar)
        super.onCreate(bundle);

        if (bundle != null)
            page = bundle.getInt("page")

        setContentView(R.layout.main)

        tabhost = findView[TabHost](this, android.R.id.tabhost)
        tabhost.setup()

        val pager = findView[ViewPager](tabhost, R.id.pager)
        adapter = new MainPagerAdapter(
                getSupportFragmentManager(), tabhost, pager)

        servers = new ServersFragment
        adapter.createTab(getString(R.string.tab_servers), servers)

        val manager = getSupportFragmentManager()

        if (honeycombAndNewer)
            HoneycombSupport.init(this)

        input = findView[EditText](this, R.id.input)
        proc = new InputProcessor(this)
        input.setOnEditorActionListener(proc.onEditorActionListener _)
        input.setOnKeyListener(proc.onKeyListener _)
        input.addTextChangedListener(proc.TextListener)

        val complete = findView[View](this, R.id.btn_nick_complete)
        complete.setOnClickListener(() => proc.nickComplete(Some(input)))

        val speech = findView[View](this, R.id.btn_speech_rec)
        speech.setOnClickListener(() => {
            val intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            try {
                startActivityForResult(intent, REQUEST_SPEECH_RECOGNITION)
            } catch {
                case e: Exception => Log.w(TAG,
                        "Unable to request speech recognition", e)
            }
        })
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
        var builder = new AlertDialog.Builder(this)
        builder.setTitle(R.string.speech_select)
        builder.setItems(results.toArray(new Array[CharSequence](results.size)),
                (d: DialogInterface, which: Int) => {
            input.getText().append(results(which) + " ")

            // TODO make a preference for this
            val eol = " over"
            if (results(which).toLowerCase().endsWith(eol)) {
                // use the entire line, not just results(which)
                // in case the user wants to record a single
                // word to send the message
                val t = input.getText()
                val line = t.substring(0, t.length() - eol.length())
                proc.handleLine(line)
                input.setText(null)
            }
        })
        builder.setNegativeButton(R.string.speech_cancel, 
                (d: DialogInterface, id: Int) => {
        })
        builder.create().show()
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
                            tabhost.setCurrentTab(tab)
                        }
                    }
                }
            }
        }

        if (service != null) refreshTabs()
        else serviceConnectionListeners += refreshTabs
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
        service = binder.asInstanceOf[IrcService#LocalService].getService()
        service.bind(this)
        service.showing = true
        if (page != -1) {
            postOnUiThread(() => {
                runOnUiThread(() => tabhost.setCurrentTab(page))
                page = -1
            })
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
        service.unbind()
        unbindService(this)
    }

    def setFragmentVisibility(txn: FragmentTransaction, fragment: Fragment,
            visible: Boolean) {
        val tx = if (txn != null) txn
                else getSupportFragmentManager().beginTransaction()
        if (visible) {
            tx.show(fragment)
        } else {
            tx.hide(fragment)
        }
        tx.commit()
    }

    def postOnUiThread[A](r: () => A) {
        class RunnableTask extends AsyncTask[Object,Object,Unit] {
            override def doInBackground(args: Object*): Unit = 
                runOnUiThread(r)
        }
        new RunnableTask().execute()
    }

    def pageChanged(idx: Int) {
        val showNickComplete = honeycombAndNewer // TODO preference w/ default
        val showSpeechRec = true // TODO preference

        val input = findView[EditText](this, R.id.input)
        val complete = findView[View](this, R.id.btn_nick_complete)
        val speech = findView[View](this, R.id.btn_speech_rec)
        input.setVisibility(if (idx == 0) View.GONE else View.VISIBLE)

        getSupportFragmentManager().popBackStack(SERVER_SETUP_STACK,
                FragmentManager.POP_BACK_STACK_INCLUSIVE)

        val f = adapter.getItem(idx)
        // workaround for disappearing menus, might be required for <3.0
        val count = adapter.getCount()
        if (idx < count - 1)
            adapter.getItem(idx+1).setMenuVisibility(false)
        if (idx > 0)
            adapter.getItem(idx-1).setMenuVisibility(false)
        f.setMenuVisibility(false)
        f.setMenuVisibility(true)

        if (honeycombAndNewer) {
            HoneycombSupport.stopActionMode()
            postOnUiThread(() => HoneycombSupport.invalidateActionBar())
        }

        f match {
            // post to thread to make sure it shows up when done paging
            case m: MessagesFragment => postOnUiThread(() => m.getListView()
                    .setSelection(m.getListAdapter().getCount() - 1))
            case _ => Unit
        }

        // TODO implement an option to hide these buttons
        if (f.isInstanceOf[QueryFragment]) {
            complete.setVisibility(View.GONE)
            if (showSpeechRec)
                speech.setVisibility(View.VISIBLE)
        } else if (f.isInstanceOf[ChannelFragment]) {
            if (showNickComplete)
                complete.setVisibility(View.VISIBLE)
            if (showSpeechRec)
                speech.setVisibility(View.VISIBLE)
        } else {
            complete.setVisibility(View.GONE)
            speech.setVisibility(View.GONE)
        }
    }

    // can't make it lazy, it might go away
    def serversFragment = {
        val mgr = getSupportFragmentManager()
        mgr.findFragmentByTag(adapter.tabs(0).tag.get)
                .asInstanceOf[ServersFragment]
    }
    override def onCreateOptionsMenu(menu: Menu): Boolean = {
        val inflater = new MenuInflater(this)
        inflater.inflate(R.menu.main_menu, menu)
        val item = menu.findItem(R.id.exit)
        MenuCompat.setShowAsAction(item, MenuItem.SHOW_AS_ACTION_IF_ROOM |
                                         MenuItem.SHOW_AS_ACTION_WITH_TEXT)
        true
    }
    override def onOptionsItemSelected(item: MenuItem) : Boolean = {
        if (R.id.exit == item.getItemId()) {
            exit()
            return true
        }
        false
    }
    override def onSaveInstanceState(state: Bundle) {
        super.onSaveInstanceState(state)
        page = tabhost.getCurrentTab()
        state.putInt("page", page)
    }

    def exit(message: Option[String] = None) {
        if (service.running) {
            var builder = new AlertDialog.Builder(this)
            builder.setTitle(R.string.quit_confirm_title)
            builder.setMessage(getString(R.string.quit_confirm))
            builder.setPositiveButton(R.string.yes,
                    (d: DialogInterface, id: Int) => {
                service.quit(message, Some(finish _))
            })
            builder.setNegativeButton(R.string.no, 
                    (d: DialogInterface, id: Int) => {
            })
            builder.create().show()
        } else {
            service.quit(message)
            finish()
        }
    }
}

class ServerSetupFragment extends DialogFragment {
    var _server: Server = _
    var thisview: View = _
    def server = {
        val s = _server
        s.name        = findView[EditText](thisview, R.id.add_server_name)
        s.hostname    = findView[EditText](thisview, R.id.add_server_host)
        s.port        = findView[EditText](thisview, R.id.add_server_port)
        s.ssl         = findView[CheckBox](thisview, R.id.add_server_ssl)
        s.autoconnect = findView[CheckBox](thisview,
                R.id.add_server_autoconnect)
        s.nickname    = findView[EditText](thisview, R.id.add_server_nickname)
        s.altnick     = findView[EditText](thisview, R.id.add_server_altnick)
        s.realname    = findView[EditText](thisview, R.id.add_server_realname)
        s.username    = findView[EditText](thisview, R.id.add_server_username)
        s.password    = findView[EditText](thisview, R.id.add_server_password)
        s.autojoin    = findView[EditText](thisview, R.id.add_server_autojoin)
        s.autorun     = findView[EditText](thisview, R.id.add_server_autorun)
        _server
    }
    def server_=(s: Server) = {
        _server = s
        if (thisview != null && s != null) {
            findView[EditText](thisview,
                    R.id.add_server_name).setText(s.name)
            findView[EditText](thisview,
                    R.id.add_server_host).setText(s.hostname)
            findView[EditText](thisview,
                    R.id.add_server_port).setText("" + s.port)
            findView[CheckBox](thisview,
                    R.id.add_server_ssl).setChecked(s.ssl)
            findView[CheckBox](thisview,
                    R.id.add_server_autoconnect).setChecked(s.autoconnect)
            findView[EditText](thisview,
                    R.id.add_server_nickname).setText(s.nickname)
            findView[EditText](thisview,
                    R.id.add_server_altnick).setText(s.altnick)
            findView[EditText](thisview,
                    R.id.add_server_realname).setText(s.realname)
            findView[EditText](thisview,
                    R.id.add_server_username).setText(s.username)
            findView[EditText](thisview,
                    R.id.add_server_password).setText(s.password)
            findView[EditText](thisview,
                    R.id.add_server_autojoin).setText(s.autojoin)
            findView[EditText](thisview,
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
                 MenuCompat.setShowAsAction(menu.findItem(item),
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
        val d = new AlertDialog.Builder(getActivity())
                .setTitle(R.string.server_details)
                .setPositiveButton(R.string.save_server, null)
                .setNegativeButton(R.string.cancel_server, null)
                .setView(createView(getActivity().getLayoutInflater(), null))
                .create()
        // block dismiss on positive button click
        d.setOnShowListener(() => {
            val b = d.getButton(DialogInterface.BUTTON_POSITIVE)
            b.setOnClickListener(() => {
                val activity = getActivity().asInstanceOf[MainActivity]
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
            })
        })
        d
    }
}

class MessagesFragment(_a: MessageAdapter = null) extends ListFragment {
    def this() = this(null)
    var _adapter = _a
    def adapter = _adapter
    def adapter_=(a: MessageAdapter) = {
        _adapter = a
        _adapter.context = getActivity().asInstanceOf[MainActivity]
        setListAdapter(_adapter)
        val service = getActivity().asInstanceOf[MainActivity].service
        service.messages += ((id, _adapter))
        getListView().setSelection(if (adapter.getCount() > 0)
                _adapter.getCount()-1 else 0)
    }
    var id = -1

    override def onCreateView(inflater: LayoutInflater,
            container: ViewGroup, bundle: Bundle) : View =
        inflater.inflate(R.layout.fragment_messages, container, false)

    override def onActivityCreated(bundle: Bundle) {
        super.onActivityCreated(bundle)
        if (id == -1 && bundle != null) {
            id = bundle.getInt("id")
        }
        val activity = getActivity().asInstanceOf[MainActivity]
        if (adapter != null) {
            val service = activity.service
            adapter.context = activity
            id = service.newMessagesId()
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
            service.messages.get(id) match {
                case Some(a) => adapter = a
                case None    => Unit
            }
        }
    }
}

class NickListFragment extends DialogFragment {
    var listview: ListView = _
    var adapterProvided = false
    var adapter: Option[NickListAdapter] = None
    override def onCreateView(inflater: LayoutInflater,
            container: ViewGroup, bundle: Bundle) : View = {
        listview = inflater.inflate(R.layout.fragment_nicklist,
                container, false).asInstanceOf[ListView]
        if (!adapterProvided && adapter.isEmpty)
            getActivity().asInstanceOf[MainActivity].postOnUiThread(dismiss _)
        adapter.foreach(listview.setAdapter(_))
        listview
    }
}

class ChannelFragment(a: MessageAdapter, c: Channel)
extends MessagesFragment(a) {
    def this() = this(null, null)
    var channel = c
    var nicklist: Option[ListView] = None
    override def onCreate(bundle: Bundle) {
        super.onCreate(bundle)
        setHasOptionsMenu(true)
    }

    private def setNickListAdapter(list: ListView) {
        val activity = getActivity().asInstanceOf[MainActivity]

        val adapter = new NickListAdapter(activity, channel)
        list.setAdapter(adapter)
    }
    override def onCreateView(inflater: LayoutInflater,
            container: ViewGroup, bundle: Bundle) : View = {
        val v = inflater.inflate(R.layout.fragment_channel, container, false)
        val config = getActivity().getResources().getConfiguration()
        // show via dialogfragment if < size_large
        if (channel != null && channel.isInstanceOf[Channel] &&
                (config.screenLayout &
                        Configuration.SCREENLAYOUT_SIZE_LARGE) == 
                                Configuration.SCREENLAYOUT_SIZE_LARGE
                || (config.screenLayout &
                        Configuration.SCREENLAYOUT_SIZE_XLARGE) != 0) {
            val f = new NickListFragment
            f.adapterProvided = true
            val view = f.onCreateView(inflater, null, null)
            nicklist = Some(view.asInstanceOf[ListView])
            if (channel != null)
                setNickListAdapter(nicklist.get)
            findView[ViewGroup](v, R.id.nicklist_container).addView(view)
            // this puts them one atop the other, even though it's horizontal?
            //v.asInstanceOf[ViewGroup].addView(view)
        }
        v
    }

    override def onActivityCreated(bundle: Bundle) {
        super.onActivityCreated(bundle)
        val activity = getActivity().asInstanceOf[MainActivity]
        if (id != -1 && channel != null) {
            activity.service.chans += ((id, channel))
            a.channel = Some(channel)
        }
        if (channel == null && !nicklist.isEmpty) {
            def setAdapter(s: IrcService) {
                val c = s.chans.get(bundle.getInt("id"))
                c.foreach(ch => channel = ch.asInstanceOf[Channel])
                if (channel != null)
                    nicklist.foreach(setNickListAdapter(_))
            }
            if (activity.service != null)
                setAdapter(activity.service)
            else
                activity.serviceConnectionListeners += (setAdapter(_))
        }
    }

    override def onCreateOptionsMenu(menu: Menu, inflater: MenuInflater)  {
        if (menu.findItem(R.id.channel_close) != null) return
        inflater.inflate(R.menu.channel_menu, menu)
        List(/*R.id.channel_leave,*/
             R.id.channel_close).foreach(item =>
                     MenuCompat.setShowAsAction(menu.findItem(item),
                             MenuItem.SHOW_AS_ACTION_IF_ROOM |
                             MenuItem.SHOW_AS_ACTION_WITH_TEXT))
    }

    override def onOptionsItemSelected(item: MenuItem): Boolean = {
        if (R.id.channel_close == item.getItemId()) {
            val activity = getActivity().asInstanceOf[MainActivity]
            def removeChannel() {
                if (channel.state == Channel.State.JOINED) {
                    val c = activity.service.channels(channel)
                    c.part()
                }
                activity.service.messages -= id
                activity.service.chans    -= id
                activity.service.channels -= channel
                activity.adapter.removeTab(
                        activity.adapter.getItemPosition(this))
            }
            if (channel.state == Channel.State.JOINED) {
                var builder = new AlertDialog.Builder(activity)
                builder.setTitle(R.string.channel_close_confirm_title)
                builder.setMessage(getString(R.string.channel_close_confirm))
                builder.setPositiveButton(R.string.yes,
                        (d: DialogInterface, id: Int) => {
                    removeChannel()
                    channel.state = Channel.State.PARTED
                })
                builder.setNegativeButton(R.string.no, 
                        (d: DialogInterface, id: Int) => {
                })
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
            f.setStyle(DialogFragment.STYLE_NO_TITLE, 0)
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
            a.channel = Some(q)
        }
    }

    override def onCreateOptionsMenu(menu: Menu, inflater: MenuInflater)  {
        if (menu.findItem(R.id.query_close) != null) return
        inflater.inflate(R.menu.query_menu, menu)
        List(R.id.query_close).foreach(item =>
                     MenuCompat.setShowAsAction(menu.findItem(item),
                             MenuItem.SHOW_AS_ACTION_IF_ROOM |
                             MenuItem.SHOW_AS_ACTION_WITH_TEXT))
    }

    override def onOptionsItemSelected(item: MenuItem): Boolean = {
        if (R.id.query_close == item.getItemId()) {
            if (true) { // TODO check preference
                val activity = getActivity().asInstanceOf[MainActivity]
                var builder = new AlertDialog.Builder(activity)
                builder.setTitle(R.string.query_close_confirm_title)
                builder.setMessage(getString(R.string.query_close_confirm))
                builder.setPositiveButton(R.string.yes,
                        (d: DialogInterface, resid: Int) => {
                    val query = activity.service.chans(id).asInstanceOf[Query]
                    activity.service.queries  -=
                            ((query.server, query.name.toLowerCase()))
                    activity.service.messages -= id
                    activity.service.chans    -= id
                    activity.service.channels -= query
                    activity.adapter.removeTab(
                            activity.adapter.getItemPosition(this))
                })
                builder.setNegativeButton(R.string.no, 
                        (d: DialogInterface, resid: Int) => {
                })
                builder.create().show()
                return true
            }
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
        findView[CheckedTextView](v, R.id.server_item_text).setChecked(true)
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
            val input = findView[EditText](activity.tabhost, R.id.input)
            input.setVisibility(View.VISIBLE)
            input.setFocusable(true)
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

        if (!serverMessagesFragmentShowing.isEmpty) {
            val f = mgr.findFragmentByTag(serverMessagesFragmentShowing.get)
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
            fragment.show(tx, SERVER_SETUP_FRAGMENT)
        }

        fragment.server = server
        if (honeycombAndNewer)
            thisview.getHandler().post(() =>
                    HoneycombSupport.invalidateActionBar())
    }
    def onIrcServiceDisconnected() {
        service.serverChangedListeners -= changeListener
        service.serverAddedListeners   -= addListener
        service.serverRemovedListeners -= removeListener
    }

    def changeListener(server: Server) {
        if (!_server.isEmpty && _server.get == server &&
                server.state == Server.State.CONNECTED && getActivity() != null)
            findView[View](getActivity(),
                    R.id.input).setVisibility(View.VISIBLE)
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
            case R.id.server_delete     => {
                var builder = new AlertDialog.Builder(getActivity())
                val mgr = getActivity().getSupportFragmentManager()
                clearServerMessagesFragment(mgr)
                builder.setTitle(R.string.server_confirm_delete)
                builder.setMessage(getActivity().getString(
                        R.string.server_confirm_delete_message,
                        server.get.name))
                builder.setPositiveButton(R.string.yes,
                        (d: DialogInterface, id: Int) => {
                    service.deleteServer(server.get)
                })
                builder.setNegativeButton(R.string.no, 
                        (d: DialogInterface, id: Int) => {
                    // noop
                })
                builder.create().show()
                true
            }
            case R.id.server_connect    => {
                service.connect(server.get)
                true
            }
            case R.id.server_disconnect => {
                service.disconnect(server.get)
                true
            }
            case R.id.server_options    => {
                addServerSetupFragment(server)
                true
            }
            case _ => false
        }
    }
    override def onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.servers_menu, menu)
        val item = menu.findItem(R.id.add_server)
        MenuCompat.setShowAsAction(item, MenuItem.SHOW_AS_ACTION_IF_ROOM |
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

        val c = m.getBackStackEntryCount()
        var found = false
        if (page == 0) {
            for (i <- 0 until c) {
                if (m.getBackStackEntryAt(i).getName() == SERVER_SETUP_STACK)
                    found = true
            }
        }

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
        val img = findView[ImageView](v, R.id.server_item_status)

        findView[View](v, R.id.server_item_progress).setVisibility(
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

        findView[CheckedTextView](v, R.id.server_item_text).setChecked(
                pos == checked)
        v
    }
}

class DummyTabFactory(c : Context) extends TabHost.TabContentFactory {
    override def createTabContent(tag : String) : View = {
        val v = new View(c)
        v.setMinimumWidth(0)
        v.setMinimumHeight(0)
        v
    }
}
