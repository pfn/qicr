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
import android.widget.LinearLayout
import android.widget.{TabHost, TabWidget}
import android.widget.TextView

import android.support.v4.app.{Fragment, FragmentActivity}
import android.support.v4.view.{PagerAdapter, ViewPager}

trait ViewFinder {
    def findView[T](container: Activity, id : Int) : T =
            container.findViewById(id).asInstanceOf[T]
    def findView[T](container: View, id : Int) : T =
            container.findViewById(id).asInstanceOf[T]
}
class MainActivity extends FragmentActivity
with ServiceConnection with ViewFinder
{
    var service : IrcService = _
    override def onCreate(savedInstanceState : Bundle) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.HONEYCOMB)
            setTheme(android.R.style.Theme_NoTitleBar)

        super.onCreate(savedInstanceState);

        if (savedInstanceState == null)
            getSupportFragmentManager().beginTransaction().add(
                    android.R.id.content, new MainFragment()).commit()

        Preferences.load(this)
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
}

class ViewPagerAdapter(tabhost: TabHost, pager: ViewPager) extends PagerAdapter
with TabHost.OnTabChangeListener with ViewPager.OnPageChangeListener {
    // PagerAdapter
    override def destroyItem(container: View, pos: Int, obj: Object) = Unit
    override def finishUpdate(container: View) = Unit
    override def getCount() : Int = 0
    override def instantiateItem(container: View, pos: Int) : Object = null
    override def isViewFromObject(view: View, obj: Object) : Boolean = false
    override def restoreState(state: Parcelable, loader: ClassLoader) = Unit
    override def saveState() : Parcelable = null
    override def startUpdate(container: View) = Unit

    override def onTabChanged(tabId: String) =
            pager.setCurrentItem(tabhost.getCurrentTab())
    override def onPageScrolled(pos: Int, posOff: Float, posOffPix: Int) = Unit
    override def onPageSelected(pos: Int) = tabhost.setCurrentTab(pos)
    override def onPageScrollStateChanged(state: Int) = Unit
}
class MainFragment extends Fragment with ViewFinder {
    var view: View = _
    var tabhost: TabHost = _
    override def onCreate(bundle: Bundle) {
        super.onCreate(bundle)
        setRetainInstance(true)
    }

    private def createTab(container: View, title : String) {
        val context = getActivity()
        val inflater = context.getSystemService(
                Context.LAYOUT_INFLATER_SERVICE).asInstanceOf[LayoutInflater]
        val indicator = inflater.inflate(R.layout.tab_indicator,
                findView[TabWidget](container, android.R.id.tabs), false)
        val tv = findView[TextView](indicator, R.id.title)
        tv.setText(title)

        var spec : TabHost#TabSpec = null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.HONEYCOMB)
            spec = tabhost.newTabSpec(title).setIndicator(indicator)
        else
            spec = tabhost.newTabSpec(title).setIndicator(title)
        spec.setContent(new DummyTabFactory(context))
        tabhost.addTab(spec)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.HONEYCOMB)
            findView[TabWidget](container, android.R.id.tabs)
                    .setStripEnabled(true)
    }

    override def onConfigurationChanged(c: Configuration) {
        val pager = findView[android.support.v4.view.ViewPager](view, R.id.pager)
        pager.setLayoutParams(new LinearLayout.LayoutParams(0, 0))
    }
    override def onCreateView(inflater: LayoutInflater,
            container: ViewGroup, bundle: Bundle) : View = {
        view = inflater.inflate(R.layout.main, container, false)
        tabhost = findView[TabHost](view, android.R.id.tabhost)
        tabhost.setup()
        createTab(view, "Hello1")
        createTab(view, "Hello2")
        createTab(view, "Hello3")
        createTab(view, "Hello4")
        createTab(view, "Hello5")
        createTab(view, "Hello6")
        createTab(view, "Hello7")
        createTab(view, "Hello8")
        createTab(view, "Hello9")
        createTab(view, "HelloA")
        createTab(view, "HelloB")
        createTab(view, "HelloC")
        createTab(view, "HelloD")
        createTab(view, "HelloE")
        createTab(view, "HelloF")
        view
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
