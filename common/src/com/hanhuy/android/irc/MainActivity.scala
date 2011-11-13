package com.hanhuy.android.irc

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.res.Configuration
import android.os.{Bundle, Build, IBinder, Parcelable}
import android.util.Log
import android.content.DialogInterface
import android.app.AlertDialog
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

import android.support.v4.app.{Fragment, FragmentActivity, FragmentManager}
import android.support.v4.app.FragmentTransaction
import android.support.v4.app.{ListFragment, FragmentPagerAdapter}
import android.support.v4.view.{ViewPager, MenuCompat}

import scala.collection.JavaConversions._
import scala.collection.mutable.HashSet

import com.hanhuy.android.irc.model.MessageAdapter
import com.hanhuy.android.irc.model.Server

import java.util.UUID

import ViewFinder._
import MainActivity._

import AndroidConversions._

object ViewFinder {
    implicit def toString(c: CharSequence) : String =
            if (c == null) null else c.toString()
    implicit def toString(t: TextView) : String = t.getText()
    implicit def toInt(t: TextView) : Int = {
        val s: String = t.getText()
        if (s == null || s == "")
            -1
        else
            Integer.parseInt(t.getText().toString())
    }
    implicit def toBoolean(c: CheckBox) : Boolean = c.isChecked()

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
}
class MainActivity extends FragmentActivity with ServiceConnection {
    var tabhost: TabHost = _
    var servers: ServersFragment = _
    var adapter: MainPagerAdapter = _
    var page = -1

    val serviceConnectionListeners    = new HashSet[(IrcService) => Any]
    val serviceDisconnectionListeners = new HashSet[() => Any]

    var _service: IrcService = _
    def service = _service
    def service_=(s: IrcService) {
        _service = s
        serviceConnectionListeners.foreach(_(s))
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

        findView[EditText](tabhost, R.id.input).setOnKeyListener(
            (v: View, key: Int, e: KeyEvent) => {
                Log.i(TAG, "Got key: " + key)
                false
            }
        )

        servers = new ServersFragment
        adapter.createTab("Servers", servers)

        val manager = getSupportFragmentManager()

        if (honeycombAndNewer)
            HoneycombSupport.init(this)
    }

    override def onPause() {
        super.onPause()
        service.showing = false
    }
    override def onResume() {
        super.onResume()
        if (service != null)
            service.showing = true
        if (honeycombAndNewer)
            HoneycombSupport.init(this)
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
            new Thread(() =>
                    runOnUiThread(() => tabhost.setCurrentTab(page))).start()
        }
    }
    override def onServiceDisconnected(name : ComponentName) {
        serviceDisconnectionListeners.foreach(_())
    }

    override def onDestroy() {
        super.onDestroy()
        if (honeycombAndNewer)
            HoneycombSupport.close()
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

    def updateMenuVisibility(idx: Int) {
        // called by pager adapter, there's an implicit invalidate there
        //HoneycombSupport.invalidateActionBar()
        if (honeycombAndNewer)
            HoneycombSupport.stopActionMode()
    }

    // can't make it lazy, it might go away
    def serversFragment = {
        val mgr = getSupportFragmentManager()
        mgr.findFragmentByTag(adapter.tabs(0).tag).asInstanceOf[ServersFragment]
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
            if (service.running) {
                var builder = new AlertDialog.Builder(this)
                builder.setTitle(R.string.quit_confirm_title)
                builder.setMessage(getString(R.string.quit_confirm))
                builder.setPositiveButton(R.string.yes,
                        (d: DialogInterface, id: Int) => {
                    service.quit()
                    finish()
                })
                builder.setNegativeButton(R.string.no, 
                        (d: DialogInterface, id: Int) => {
                })
                builder.create().show()
                return true
            }
            service.quit()
            finish()
            return true
        }
        false
    }
    override def onSaveInstanceState(state: Bundle) {
        super.onSaveInstanceState(state)
        page = tabhost.getCurrentTab()
        state.putInt("page", page)
    }
}

class ServerSetupFragment extends Fragment {
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
        thisview = inflater.inflate(R.layout.fragment_server_setup,
                container, false)
        server = _server
        thisview
    }
}

class MessagesFragment(_a: MessageAdapter) extends ListFragment {
    var _adapter = _a
    def adapter = _adapter
    def adapter_=(a: MessageAdapter) = {
        _adapter = a
        _adapter.context = getActivity()
        setListAdapter(_adapter)
        val service = getActivity().asInstanceOf[MainActivity].service
        service.messages += ((uuid, _adapter))
    }
    var uuid: String = _
    def this() = this(null)

    override def onCreateView(inflater: LayoutInflater,
            container: ViewGroup, bundle: Bundle) : View =
        inflater.inflate(R.layout.fragment_messages, container, false)

    override def onActivityCreated(bundle: Bundle) {
        super.onActivityCreated(bundle)
        if (uuid == null && bundle != null) {
            uuid = bundle.getString("uuid")
        }
        val activity = getActivity().asInstanceOf[MainActivity]
        if (adapter != null) {
            val service = activity.service
            adapter.context = activity
            uuid = UUID.randomUUID().toString()
            service.messages += ((uuid, adapter))
            setListAdapter(adapter)
        }
        if (activity.service == null)
            activity.serviceConnectionListeners += onServiceConnected
        else
            onServiceConnected(activity.service)
    }

    override def onSaveInstanceState(bundle: Bundle) {
        super.onSaveInstanceState(bundle)
        bundle.putString("uuid", uuid)
    }
    def onServiceConnected(service: IrcService) {
        if (adapter == null && uuid != null) {
            service.messages.get(uuid) match {
                case Some(a) => adapter = a
                case None    => Unit
            }
        }
    }
}

class ServersFragment extends ListFragment {
    var thisview: View = _
    var service: IrcService = _
    var adapter: ServerArrayAdapter = _
    var _server: Server = _ // currently selected server
    override def onCreate(bundle: Bundle) {
        super.onCreate(bundle)
        setHasOptionsMenu(true)
        setRetainInstance(true)
        adapter = new ServerArrayAdapter(getActivity())
        setListAdapter(adapter)
        if (service != null) {
            for (s <- service.getServers())
                adapter.add(s)
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
        _server = server
    }

    def onIrcServiceConnected(_service: IrcService) {
        service = _service
        if (adapter != null) {
            adapter.clear()
            for (s <- service.getServers())
                adapter.add(s)
            adapter.notifyDataSetChanged()
        }
        service.serverChangedListeners += changeListener
        service.serverAddedListeners   += addListener
        service.serverRemovedListeners += removeListener
    }

    def addServerMessagesFragment(server: Server) {
        val main = getActivity().asInstanceOf[MainActivity]
        val mgr = main.getSupportFragmentManager()
        var fragment = mgr.findFragmentByTag(
                SERVER_MESSAGES_FRAGMENT_PREFIX + server.name)
                .asInstanceOf[MessagesFragment]
        if (fragment == null) {
            fragment = new MessagesFragment(server.messages)
        } else
            fragment.adapter = server.messages
        if (fragment.isVisible()) return

        mgr.popBackStack(SERVER_MESSAGES_STACK,
                FragmentManager.POP_BACK_STACK_INCLUSIVE)

        val tx = mgr.beginTransaction()
        tx.add(R.id.servers_container, fragment,
                SERVER_MESSAGES_FRAGMENT_PREFIX + server.name)
        tx.addToBackStack(SERVER_MESSAGES_STACK)
        tx.commit()
    }

    def addServerSetupFragment(_server: Server = null) {
        var server = _server
        val main = getActivity().asInstanceOf[MainActivity]
        val mgr = main.getSupportFragmentManager()
        mgr.popBackStack(SERVER_MESSAGES_STACK,
                FragmentManager.POP_BACK_STACK_INCLUSIVE)
        var fragment: ServerSetupFragment = null
        fragment = mgr.findFragmentByTag(SERVER_SETUP_FRAGMENT)
                .asInstanceOf[ServerSetupFragment]
        if (fragment == null)
            fragment = new ServerSetupFragment
        if (fragment.isVisible()) return

        if (server == null) {
            val listview = getListView()
            val checked = listview.getCheckedItemPosition()
            if (AdapterView.INVALID_POSITION != checked)
                listview.setItemChecked(checked, false)
            server = new Server
        }
        val tx = mgr.beginTransaction()
        tx.add(R.id.servers_container, fragment, SERVER_SETUP_FRAGMENT)
        tx.addToBackStack(SERVER_SETUP_STACK)
        tx.commit()
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

    def onServerMenuItemClicked(item: MenuItem, server: Server): Boolean = {
        item.getItemId() match {
            case R.id.server_delete     => {
                var builder = new AlertDialog.Builder(getActivity())
                val mgr = getActivity().getSupportFragmentManager()
                mgr.popBackStack(SERVER_MESSAGES_STACK,
                        FragmentManager.POP_BACK_STACK_INCLUSIVE)
                builder.setTitle(R.string.server_confirm_delete)
                builder.setMessage(getActivity().getString(
                        R.string.server_confirm_delete_message, server.name))
                builder.setPositiveButton(R.string.yes,
                        (d: DialogInterface, id: Int) => {
                    service.deleteServer(server)
                })
                builder.setNegativeButton(R.string.no, 
                        (d: DialogInterface, id: Int) => {
                    // noop
                })
                builder.create().show()
                true
            }
            case R.id.server_connect    => {service.connect(server);true}
            case R.id.server_disconnect => {service.disconnect(server);true}
            case R.id.server_options    => {addServerSetupFragment(server);true}
            case _ => false
        }
    }
    override def onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.servers_menu, menu)
        val item = menu.findItem(R.id.add_server)
        MenuCompat.setShowAsAction(item, MenuItem.SHOW_AS_ACTION_IF_ROOM |
                                         MenuItem.SHOW_AS_ACTION_WITH_TEXT)

        Log.i(TAG, "createoptions menu")
        if (!honeycombAndNewer) {
            inflater.inflate(R.menu.server_menu, menu)
            List(R.id.server_connect,
                 R.id.server_disconnect,
                 R.id.server_options).foreach(item =>
                         MenuCompat.setShowAsAction(menu.findItem(item),
                                 MenuItem.SHOW_AS_ACTION_IF_ROOM |
                                 MenuItem.SHOW_AS_ACTION_WITH_TEXT))
        }
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

        Log.i(TAG, "setting add server menu item visibility: " + !found)
        menu.findItem(R.id.add_server).setVisible(!found)

        if (!honeycombAndNewer) {
            if (getListView().getCheckedItemPosition() == -1)
                _server = null

            val connected = _server != null && { _server.state match {
                case Server.State.INITIAL      => false
                case Server.State.DISCONNECTED => false
                case _                         => true
            }}

            menu.findItem(R.id.server_options).setVisible(_server != null)
            menu.findItem(R.id.server_delete).setVisible(_server != null)
            menu.findItem(R.id.server_connect).setVisible(
                    !connected && _server != null)
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
