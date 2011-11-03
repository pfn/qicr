package com.hanhuy.android.irc

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.res.Configuration
import android.os.{Bundle, Build, IBinder, Parcelable}

import android.view.LayoutInflater;

import android.content.DialogInterface
import android.app.AlertDialog
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
import scala.collection.mutable.ArrayBuffer

import com.hanhuy.android.irc.config.Server

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
    val MAIN_FRAGMENT = "mainfrag"
    val SERVER_SETUP_FRAGMENT = "serversetupfrag"
    val SERVER_SETUP_STACK = "serversetup"

    val honeycombAndNewer =
            Build.VERSION.SDK_INT >=  Build.VERSION_CODES.HONEYCOMB
}
class MainActivity extends FragmentActivity with ServiceConnection
{
    var fragment: MainFragment = _
    var serversMenuFragment: ServersMenuFragment = _
    var serverMenuFragment: ServerMenuFragment = _

    var service : IrcService = _
    override def onCreate(savedInstanceState : Bundle) {
        if (!honeycombAndNewer)
            setTheme(android.R.style.Theme_NoTitleBar)

        super.onCreate(savedInstanceState);

        val manager = getSupportFragmentManager()
        fragment = manager.findFragmentByTag(MAIN_FRAGMENT).asInstanceOf[MainFragment]
        val tx = manager.beginTransaction()
        if (fragment == null) {
            fragment = new MainFragment
            //tx.add(R.id.main_container, fragment, MAIN_FRAGMENT)
            tx.add(android.R.id.content, fragment, MAIN_FRAGMENT)
        }

        serversMenuFragment = manager.findFragmentByTag("serversmenu").asInstanceOf[ServersMenuFragment]
        if (serversMenuFragment == null) {
            serversMenuFragment = new ServersMenuFragment
            tx.add(serversMenuFragment, "serversmenu")
        }

        if (honeycombAndNewer)
            HoneycombSupport.init(this)
        else {
            serverMenuFragment = manager.findFragmentByTag("servermenu").asInstanceOf[ServerMenuFragment]
            if (serverMenuFragment == null) {
                serverMenuFragment = new ServerMenuFragment
                tx.add(serverMenuFragment, "servermenu")
                tx.hide(serverMenuFragment)
            }
        }

        tx.commit()
    }

    override def onStart() {
        super.onStart()
        bindService(new Intent(this, classOf[IrcService]), this,
                Context.BIND_AUTO_CREATE)
    }

    override def onServiceConnected(name : ComponentName, binder : IBinder) {
        service = binder.asInstanceOf[IrcService#LocalService].getService()
        if (!service.running)
            service.connect()
        fragment.servers.onIrcServiceConnected(service)
    }
    override def onServiceDisconnected(name : ComponentName) {
        fragment.servers.onIrcServiceDisconnected()
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
    def setServersMenuVisibility(visible: Boolean) =
        setFragmentVisibility(null, serversMenuFragment, visible)
    def setServerMenuVisibility(visible: Boolean, server: Server = null) {
        if (honeycombAndNewer) {
            if (visible)
                HoneycombSupport.startActionMode(server)
        } else
            setFragmentVisibility(null, serverMenuFragment, visible)
    }

    def updateMenuVisibility(idx: Int) {
        setServersMenuVisibility(idx == 0)

        if (honeycombAndNewer)
            HoneycombSupport.stopActionMode()
        if (idx != 0) setFragmentVisibility(null, serverMenuFragment, false)
    }

    // can't make it lazy, it might go away
    def serversFragment = {
        val mgr = getSupportFragmentManager()
        val pager = findView[ViewPager](fragment.getView(), R.id.pager)
        val tag = "android:switcher:" + pager.getId() + ":0"
        mgr.findFragmentByTag(tag).asInstanceOf[ServersFragment]
    }
}

class ViewPagerAdapter(manager: FragmentManager,
        tabhost: TabHost, pager: ViewPager)
extends FragmentPagerAdapter(manager)
with TabHost.OnTabChangeListener with ViewPager.OnPageChangeListener
with FragmentManager.OnBackStackChangedListener {
    manager.addOnBackStackChangedListener(this)
    tabhost.setOnTabChangedListener(this)
    pager.setOnPageChangeListener(this)
    pager.setAdapter(this)

    val tabs = new ArrayBuffer[TabInfo]()
    var page = 0

    // PagerAdapter
    override def getCount() : Int = tabs.length
    override def getItem(pos: Int) : Fragment = tabs(pos).fragment

    // OnTabChangeListener
    override def onTabChanged(tabId: String) {
        val idx = tabhost.getCurrentTab()
        val activity = tabhost.getContext().asInstanceOf[MainActivity]
        val input = findView[EditText](activity.fragment.thisview, R.id.input)
        input.setVisibility(if (idx == 0) View.GONE else View.VISIBLE)
        pager.setCurrentItem(idx)
        activity.updateMenuVisibility(idx)
        page = idx
    }

    // OnPageChangeListener
    override def onPageScrolled(pos: Int, posOff: Float, posOffPix: Int) = Unit
    override def onPageSelected(pos: Int) {
        page = pos
        val activity = tabhost.getContext().asInstanceOf[MainActivity]
        val input = findView[EditText](activity.fragment.thisview, R.id.input)
        input.setVisibility(if (pos == 0) View.GONE else View.VISIBLE)

        tabhost.setCurrentTab(pos)
        val v = tabhost.getTabWidget().getChildTabViewAt(pos)
        val hsv = findView[HorizontalScrollView](
                activity, R.id.tab_scroller)
        val display = activity.getWindowManager().getDefaultDisplay()
        val offset = v.getLeft() - display.getWidth() / 2 + v.getWidth() / 2
        hsv.smoothScrollTo(if (offset < 0) 0 else offset, 0)
        activity.updateMenuVisibility(pos)
    }
    override def onPageScrollStateChanged(state: Int) = Unit


    private def addTab(title: String) {
        var spec : TabHost#TabSpec = null

        if (!honeycombAndNewer) {
            val inflater = tabhost.getContext().getSystemService(
                    Context.LAYOUT_INFLATER_SERVICE)
                    .asInstanceOf[LayoutInflater]
            val ind = inflater.inflate(R.layout.tab_indicator,
                    tabhost.getTabWidget(), false)
            findView[TextView](ind, R.id.title).setText(title)

            spec = tabhost.newTabSpec("tabspec" + tabnum).setIndicator(ind)
        }
        else
            spec = tabhost.newTabSpec("tabspec" + tabnum).setIndicator(title)

        tabnum += 1
        spec.setContent(new DummyTabFactory(tabhost.getContext()))
        tabhost.addTab(spec)

        if (!honeycombAndNewer)
            tabhost.getTabWidget().setStripEnabled(true)
    }
    var tabnum = 0
    def createTab(title: String, fragment: Fragment) {
        tabs += new TabInfo(title, fragment)
        addTab(title)
        notifyDataSetChanged()
    }

    def insertTab(title: String, fragment: Fragment, pos: Int) {
        tabs.insert(pos, new TabInfo(title, fragment))
        addTab(title)

        val tw = tabhost.getTabWidget()
        for (i <- 0 until tw.getTabCount()) {
            val v = tw.getChildTabViewAt(i)
            findView[TextView](v, R.id.title).setText(tabs(i).title)
        }
        notifyDataSetChanged()
    }

    def removeTab(pos: Int) {
        tabhost.setCurrentTab(0)
        tabhost.clearAllTabs()
        tabs.remove(pos)
        for (i <- 0 until tabs.length)
            addTab(tabs(i).title)
        notifyDataSetChanged()
    }

    class TabInfo(_title: String, _fragment: Fragment) {
        def title    = _title
        def fragment = _fragment
    }
    override def onBackStackChanged() {
        val c = manager.getBackStackEntryCount()
        if (page == 0) {
            var found = false
            for (i <- 0 until c) {
                if (manager.getBackStackEntryAt(i).getName() == SERVER_SETUP_STACK)
                    found = true
            }
            tabhost.getContext().asInstanceOf[MainActivity]
                    .setServersMenuVisibility(!found)
        }
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
        var item = menu.findItem(R.id.save_server)
        MenuCompat.setShowAsAction(item, MenuItem.SHOW_AS_ACTION_ALWAYS |
                                         MenuItem.SHOW_AS_ACTION_WITH_TEXT)
        item = menu.findItem(R.id.cancel_server)
        MenuCompat.setShowAsAction(item, MenuItem.SHOW_AS_ACTION_IF_ROOM |
                                         MenuItem.SHOW_AS_ACTION_WITH_TEXT)
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

class ServersFragment extends ListFragment {
    var thisview: View = _
    var service: IrcService = _
    var adapter: ServerArrayAdapter = _
    override def onCreate(bundle: Bundle) {
        super.onCreate(bundle)
        setRetainInstance(true)
        if (honeycombAndNewer)
            HoneycombSupport.menuItemListener = onServerMenuItemClicked
        adapter = new ServerArrayAdapter(getActivity(),
                R.layout.server_item, R.id.server_item_text)
    }

    override def onCreateView(inflater: LayoutInflater,
            container: ViewGroup, bundle: Bundle) : View = {
        
        thisview = inflater.inflate(R.layout.fragment_servers, container, false)
        thisview
    }

    override def onActivityCreated(bundle: Bundle) {
        super.onActivityCreated(bundle)
        setListAdapter(adapter)
    }
    override def onListItemClick(list: ListView, v: View, pos: Int, id: Long) {
        findView[CheckedTextView](v, R.id.server_item_text).setChecked(true)
        val activity = getActivity().asInstanceOf[MainActivity]
        val manager = activity.getSupportFragmentManager()
        val fragment = manager.findFragmentByTag(SERVER_SETUP_FRAGMENT)
        if (fragment != null && fragment.isVisible()) {
            manager.popBackStack()
        }
        val server = adapter.getItem(pos)
        if (activity.serverMenuFragment != null)
            activity.serverMenuFragment.server = server
        activity.setServerMenuVisibility(false)
        activity.setServerMenuVisibility(true, server)
    }

    def onIrcServiceConnected(_service: IrcService) {
        service = _service
        val servers = service.getServers()
        adapter.clear()
        for (s <- servers)
            adapter.add(s)
        adapter.notifyDataSetChanged()
        service.serverChangedListeners += changeListener
        service.serverAddedListeners   += addListener
        service.serverRemovedListeners += removeListener
    }

    def addServerSetupFragment(_server: Server = null) {
        var server = _server
        val main = getActivity().asInstanceOf[MainActivity]
        val mgr = main.getSupportFragmentManager()
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
    }
    def onIrcServiceDisconnected() {
        service.serverChangedListeners -= changeListener
        service.serverAddedListeners   -= addListener
        service.serverRemovedListeners -= removeListener
    }

    def changeListener(server: Server) {
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
            case R.id.server_connect    => return true
            case R.id.server_disconnect => return true
            case R.id.server_options    => addServerSetupFragment(server)
        }
        true
    }
}

class ServerArrayAdapter(context: Context, resource: Int, textViewId: Int)
extends ArrayAdapter[Server](context, resource, textViewId) {
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
    override def onCreate(bundle: Bundle) {
        super.onCreate(bundle)
        setHasOptionsMenu(true)
        setRetainInstance(true)
    }

    override def onCreateView(inflater: LayoutInflater,
            container: ViewGroup, bundle: Bundle) : View = {
        thisview = inflater.inflate(R.layout.fragment_main, container, false)

        tabhost = findView[TabHost](thisview, android.R.id.tabhost)
        tabhost.setup()

        val pager = findView[ViewPager](thisview, R.id.pager)
        val adapter = new ViewPagerAdapter(
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
            getActivity().finish()
            return true
        }
        false
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

class ServersMenuFragment extends Fragment {
    override def onCreate(bundle: Bundle) {
        super.onCreate(bundle)
        setHasOptionsMenu(true)
    }

    override def onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.servers_menu, menu)
        val item = menu.findItem(R.id.add_server)
        MenuCompat.setShowAsAction(item, MenuItem.SHOW_AS_ACTION_IF_ROOM |
                                         MenuItem.SHOW_AS_ACTION_WITH_TEXT)
    }
    override def onOptionsItemSelected(item: MenuItem) : Boolean = {
        if (R.id.add_server == item.getItemId()) {
            val main = getActivity().asInstanceOf[MainActivity]
            main.serversFragment.addServerSetupFragment()
            return true
        }
        false
    }
}
class ServerMenuFragment extends Fragment {
    override def onCreate(bundle: Bundle) {
        super.onCreate(bundle)
        setHasOptionsMenu(true)
    }

    var _server:Server = _
    def server = _server
    def server_=(s: Server) = {
        _server = s
    }

    override def onPrepareOptionsMenu(menu: Menu) {
        val connected = server.state match {
        case Server.State.INITIAL => false
        case Server.State.DISCONNECTED => false
        case _ => true
        }

        menu.findItem(R.id.server_connect).setVisible(!connected)
        menu.findItem(R.id.server_disconnect).setVisible(connected)
    }
    override def onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.server_menu, menu)
        List(R.id.server_connect,
             R.id.server_disconnect,
             R.id.server_options).foreach(item =>
                     MenuCompat.setShowAsAction(menu.findItem(item),
                             MenuItem.SHOW_AS_ACTION_IF_ROOM |
                             MenuItem.SHOW_AS_ACTION_WITH_TEXT))
    }
    override def onOptionsItemSelected(item: MenuItem) : Boolean = {
        return getActivity().asInstanceOf[MainActivity]
                .serversFragment.onServerMenuItemClicked(item, server)
    }
}
