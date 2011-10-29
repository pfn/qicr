package com.hanhuy.android.irc

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.res.Configuration
import android.os.{Bundle, Build, IBinder, Parcelable}

import android.view.LayoutInflater;

import android.view.{View, ViewGroup}
import android.view.Display
import android.view.{Menu, MenuItem, MenuInflater}
import android.widget.LinearLayout
import android.widget.{TabHost, TabWidget}
import android.widget.TextView
import android.widget.EditText
import android.widget.CheckBox
import android.widget.Toast
import android.widget.HorizontalScrollView
import android.widget.{ListView, ArrayAdapter}

import android.support.v4.app.{Fragment, FragmentActivity, FragmentManager}
import android.support.v4.app.{ListFragment, FragmentPagerAdapter}
import android.support.v4.view.{ViewPager, MenuCompat}

import scala.collection.JavaConversions._
import scala.collection.mutable.ArrayBuffer

import com.hanhuy.android.irc.config.Config
import com.hanhuy.android.irc.config.Server

import ViewFinder._

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

    def findView[T](container: Activity, id : Int) : T =
            container.findViewById(id).asInstanceOf[T]
    def findView[T](container: View, id : Int) : T =
            container.findViewById(id).asInstanceOf[T]
}
class MainActivity extends FragmentActivity with ServiceConnection
{
    var fragment: MainFragment = _
    var serversMenuFragment: ServersMenuFragment = _

    var service : IrcService = _
    override def onCreate(savedInstanceState : Bundle) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.HONEYCOMB)
            setTheme(android.R.style.Theme_NoTitleBar)

        super.onCreate(savedInstanceState);
        setContentView(R.layout.main)

        val manager = getSupportFragmentManager()
        fragment = manager.findFragmentByTag("mainfrag").asInstanceOf[MainFragment]
        val tx = manager.beginTransaction()
        if (fragment == null) {
            fragment = new MainFragment
            tx.add(R.id.main_container, fragment, "mainfrag")
            //tx.add(android.R.id.content, fragment, "mainfrag")
        }

        serversMenuFragment = manager.findFragmentByTag("serversmenu").asInstanceOf[ServersMenuFragment]
        if (serversMenuFragment == null) {
            serversMenuFragment = new ServersMenuFragment
            tx.add(serversMenuFragment, "serversmenu")
        }

        tx.commit()

        bindService(new Intent(this, classOf[IrcService]), this,
                Context.BIND_AUTO_CREATE)
    }

    override def onServiceConnected(name : ComponentName, binder : IBinder) {
        service = binder.asInstanceOf[IrcService#LocalService].getService()
        if (!service.running)
            service.connect()
    }
    override def onServiceDisconnected(name : ComponentName) {
    }

    override def onDestroy() {
        super.onDestroy()
        unbindService(this)
    }

    def setServersMenuVisibility(visible: Boolean) {
        val tx = getSupportFragmentManager().beginTransaction()
        if (visible) {
            tx.show(serversMenuFragment)
        } else {
            tx.hide(serversMenuFragment)
        }
        tx.commit()
    }
    def updateMenuVisibility(idx: Int) {
        setServersMenuVisibility(idx == 0)
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

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.HONEYCOMB) {
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

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.HONEYCOMB)
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
                if (manager.getBackStackEntryAt(i).getName() == "serversetup")
                    found = true
            }
            tabhost.getContext().asInstanceOf[MainActivity].setServersMenuVisibility(!found)
        }
    }
}

class ServerSetupFragment extends Fragment {
    android.util.Log.i("ServerSetupFragment", "constructed")
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
        if (thisview != null) {
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
    }

    override def onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.server_setup_menu, menu)
        var item = menu.findItem(R.id.save_server)
        MenuCompat.setShowAsAction(item, MenuItem.SHOW_AS_ACTION_IF_ROOM |
                                         MenuItem.SHOW_AS_ACTION_WITH_TEXT)
        item = menu.findItem(R.id.cancel_server)
        MenuCompat.setShowAsAction(item, MenuItem.SHOW_AS_ACTION_ALWAYS |
                                         MenuItem.SHOW_AS_ACTION_WITH_TEXT)
    }
    override def onOptionsItemSelected(item: MenuItem) : Boolean = {
        if (R.id.save_server == item.getItemId()) {
            val activity = getActivity().asInstanceOf[MainActivity]
            val manager = activity.getSupportFragmentManager()
            val s = server
            if (s.valid) {
                val config = new Config(getActivity())
                if (s.id == -1)
                    config.addServer(s)
                else
                    config.updateServer(s)
                manager.popBackStack()
            } else {
                Toast.makeText(getActivity(), R.string.server_incomplete,
                        Toast.LENGTH_SHORT).show()
            }
            return true
        }
        if (R.id.cancel_server == item.getItemId()) {
            val activity = getActivity().asInstanceOf[MainActivity]
            val manager = activity.getSupportFragmentManager()
            manager.popBackStack()
            return true
        }
        false
    }
    override def onCreateView(inflater: LayoutInflater,
            container: ViewGroup, bundle: Bundle) : View = {
        thisview = inflater.inflate(R.layout.add_server, container, false)
        server = _server
        thisview
    }
}

class ServersFragment(label: String) extends ListFragment {
    var thisview: View = _
    override def onCreate(bundle: Bundle) {
        super.onCreate(bundle)
        setRetainInstance(true)
    }
    override def onCreateView(inflater: LayoutInflater,
            container: ViewGroup, bundle: Bundle) : View = {
        
        thisview = inflater.inflate(R.layout.servers, container, false)


        thisview
    }
    override def onActivityCreated(bundle: Bundle) {
        super.onActivityCreated(bundle)
        val config = new Config(getActivity())
        val servers = config.getServers()
        setListAdapter(new ArrayAdapter[Server](getActivity(),
                android.R.layout.simple_list_item_single_choice, servers))
    }
    override def onListItemClick(list: ListView, view: View, pos: Int, id: Long) = Unit
}

class MainFragment extends Fragment {
    var thisview: View = _
    var tabhost: TabHost = _
    override def onCreate(bundle: Bundle) {
        super.onCreate(bundle)
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

        adapter.createTab("Servers", new ServersFragment("First"))
        thisview
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
            val mgr = main.getSupportFragmentManager()
            var fragment: ServerSetupFragment = null
            fragment = mgr.findFragmentByTag("serversetupfrag").asInstanceOf[ServerSetupFragment]
            if (fragment == null)
                fragment = new ServerSetupFragment
            if (fragment.isVisible()) return true


            val tx = mgr.beginTransaction()
            tx.add(R.id.servers_container, fragment, "serversetupfrag")
            tx.addToBackStack("serversetup")
            tx.commit()
            fragment.server = new Server
            return true
        }
        false
    }
}
