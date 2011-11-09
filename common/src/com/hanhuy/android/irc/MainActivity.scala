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
import android.view.LayoutInflater;
import android.view.{View, ViewGroup}
import android.view.Display
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

import com.hanhuy.android.irc.model.QueueAdapter
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
    var fragment: MainFragment = _

    val serviceConnectionListeners    = new HashSet[(IrcService) => Any]
    val serviceDisconnectionListeners = new HashSet[() => Any]

    var _service: IrcService = _
    def service = _service
    def service_=(s: IrcService) {
        _service = s
        serviceConnectionListeners.foreach(_(s))
    }

    override def onCreate(savedInstanceState: Bundle) {
        if (!honeycombAndNewer)
            setTheme(android.R.style.Theme_NoTitleBar)

        super.onCreate(savedInstanceState);

        val manager = getSupportFragmentManager()
        //FragmentManager.enableDebugLogging(true)
        fragment = manager.findFragmentByTag(MAIN_FRAGMENT)
                .asInstanceOf[MainFragment]
        val tx = manager.beginTransaction()
        if (fragment == null) {
            fragment = new MainFragment
            tx.add(android.R.id.content, fragment, MAIN_FRAGMENT)
        }


        if (honeycombAndNewer)
            HoneycombSupport.init(this)

        tx.commit()
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
        serviceConnectionListeners +=
                fragment.servers.onIrcServiceConnected
        serviceDisconnectionListeners +=
                fragment.servers.onIrcServiceDisconnected _

        bindService(new Intent(this, classOf[IrcService]), this,
                Context.BIND_AUTO_CREATE)
    }

    override def onServiceConnected(name : ComponentName, binder : IBinder) {
        service = binder.asInstanceOf[IrcService#LocalService].getService()
        service.bind(this)
        service.showing = true
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
    def setServerMenuVisibility(visible: Boolean, server: Server = null) {
        if (honeycombAndNewer) {
            if (visible)
                HoneycombSupport.startActionMode(server)
        }
    }

    def updateMenuVisibility(idx: Int) {
        if (honeycombAndNewer) {
            HoneycombSupport.invalidateActionBar()
            HoneycombSupport.stopActionMode()
        }
    }

    // can't make it lazy, it might go away
    def serversFragment = {
        val mgr = getSupportFragmentManager()
        mgr.findFragmentByTag(fragment.adapter.tabs(0).tag)
                .asInstanceOf[ServersFragment]
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
                return true
            }
            case R.id.cancel_server => {
                val activity = getActivity().asInstanceOf[MainActivity]
                val manager = activity.getSupportFragmentManager()
                manager.popBackStack()
                return true
            }
        }
        false
    }
    override def onCreateView(inflater: LayoutInflater,
            container: ViewGroup, bundle: Bundle) : View = {
        thisview = inflater.inflate(R.layout.fragment_server_setup,
                container, false)
        server = _server
        thisview
    }
}

class MessagesFragment(_a: QueueAdapter[_<:Object]) extends ListFragment {
    var _adapter = _a
    def adapter = _adapter
    def adapter_=(a: QueueAdapter[_<:Object]) = {
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
            android.util.Log.w("MessagesFragment", "uuids: " + service.messages)
            adapter = service.messages(uuid)
            adapter.context = getActivity()
            setListAdapter(adapter)
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
        if (honeycombAndNewer)
            HoneycombSupport.invalidateActionBar()

        if (server.state == Server.State.CONNECTED) {
            findView[EditText](activity.fragment.thisview, R.id.input)
                    .setVisibility(View.VISIBLE)
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
        }
        if (fragment.isVisible()) return

        mgr.popBackStack(SERVER_MESSAGES_STACK,
                FragmentManager.POP_BACK_STACK_INCLUSIVE)

        val tx = mgr.beginTransaction()
        main.setServerMenuVisibility(false)
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
        main.setServerMenuVisibility(false)
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
            }
            case R.id.server_connect    => service.connect(server)
            case R.id.server_disconnect => service.disconnect(server)
            case R.id.server_options    => addServerSetupFragment(server)
        }
        true
    }
    override def onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.servers_menu, menu)
        val item = menu.findItem(R.id.add_server)
        MenuCompat.setShowAsAction(item, MenuItem.SHOW_AS_ACTION_IF_ROOM |
                                         MenuItem.SHOW_AS_ACTION_WITH_TEXT)

        inflater.inflate(R.menu.server_menu, menu)
        List(R.id.server_connect, R.id.server_disconnect, R.id.server_options)
                .foreach(item =>
                     MenuCompat.setShowAsAction(menu.findItem(item),
                             MenuItem.SHOW_AS_ACTION_IF_ROOM |
                             MenuItem.SHOW_AS_ACTION_WITH_TEXT))
    }
    override def onOptionsItemSelected(item: MenuItem) : Boolean = {
        val activity = getActivity().asInstanceOf[MainActivity]
        if (R.id.add_server == item.getItemId()) {
            activity.serversFragment.addServerSetupFragment()
            return true
        }
        return getActivity().asInstanceOf[MainActivity]
                .serversFragment.onServerMenuItemClicked(item, _server)
    }
    override def onPrepareOptionsMenu(menu: Menu) {
        val activity = getActivity().asInstanceOf[MainActivity]
        val m = activity.getSupportFragmentManager()

        var page = 0
        if (activity.fragment != null && activity.fragment.adapter != null)
            page = activity.fragment.adapter.page

        val c = m.getBackStackEntryCount()
        var found = false
        if (page == 0) {
            for (i <- 0 until c) {
                if (m.getBackStackEntryAt(i).getName() == SERVER_SETUP_STACK)
                    found = true
            }
        }

        menu.findItem(R.id.add_server).setVisible(!found)

        val connected = _server != null && { _server.state match {
            case Server.State.INITIAL      => false
            case Server.State.DISCONNECTED => false
            case _                         => true
        }}

        menu.findItem(R.id.server_connect).setVisible(
                !connected && _server != null)
        menu.findItem(R.id.server_disconnect).setVisible(connected)
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

class MainFragment extends Fragment {
    var thisview: View = _
    var tabhost: TabHost = _
    var servers: ServersFragment = _
    var adapter: MainPagerAdapter = _
    var page = -1
    override def onCreate(bundle: Bundle) {
        super.onCreate(bundle)
        setHasOptionsMenu(true)
        setRetainInstance(true)
    }

    def onServiceConnected(service: IrcService) {
        // terrible hack, setCurrentTab needs to happen on the next event
        // so that MainPagerAdapter can fill in all the fragments
        if (page != -1) {
            new Thread(() => getActivity().runOnUiThread(() =>
                    tabhost.setCurrentTab(page))) .start()
        }
    }
    override def onActivityCreated(bundle: Bundle) {
        super.onActivityCreated(bundle)
        if (bundle != null) {
            page = bundle.getInt("page")
            Log.i(TAG, "Loaded page as: " + page)
        }
        val main = getActivity().asInstanceOf[MainActivity]
        if (main.service != null)
            onServiceConnected(main.service)
        else
            main.serviceConnectionListeners += onServiceConnected
    }

    override def onCreateView(inflater: LayoutInflater,
            container: ViewGroup, bundle: Bundle) : View = {
        thisview = inflater.inflate(R.layout.fragment_main, container, false)

        tabhost = findView[TabHost](thisview, android.R.id.tabhost)
        tabhost.setup()

        val pager = findView[ViewPager](thisview, R.id.pager)
        adapter = new MainPagerAdapter(
                getActivity().getSupportFragmentManager(), tabhost, pager)

        servers = new ServersFragment
        adapter.createTab("Servers", servers)
        thisview
    }
    override def onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.main_menu, menu)
        val item = menu.findItem(R.id.exit)
        MenuCompat.setShowAsAction(item, MenuItem.SHOW_AS_ACTION_IF_ROOM |
                                         MenuItem.SHOW_AS_ACTION_WITH_TEXT)
    }
    override def onOptionsItemSelected(item: MenuItem) : Boolean = {
        if (R.id.exit == item.getItemId()) {
            val main = getActivity().asInstanceOf[MainActivity]
            main.service.quit()
            main.finish()
            return true
        }
        false
    }

    override def onSaveInstanceState(state: Bundle) {
        super.onSaveInstanceState(state)
        page = tabhost.getCurrentTab()
        Log.i(TAG, "Saving page as: " + page)
        state.putInt("page", page)
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
