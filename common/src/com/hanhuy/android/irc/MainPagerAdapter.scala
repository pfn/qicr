package com.hanhuy.android.irc

import com.hanhuy.android.irc.model.Channel
import com.hanhuy.android.irc.model.ChannelComparator
import com.hanhuy.android.irc.model.FragmentPagerAdapter

import android.content.Context
import android.util.Log
import android.view.View
import android.view.LayoutInflater;
import android.widget.HorizontalScrollView
import android.widget.TextView
import android.widget.TabHost
import android.widget.EditText

import android.support.v4.view.{ViewPager, PagerAdapter}
import android.support.v4.app.{Fragment, FragmentActivity, FragmentManager}

import scala.collection.mutable.ArrayBuffer
import scala.collection.JavaConversions._

import java.util.Collections

import ViewFinder._
import MainPagerAdapter._
import AndroidConversions._

object MainPagerAdapter {
    val TAG = "MainPagerAdapter"
}
class MainPagerAdapter(manager: FragmentManager,
        tabhost: TabHost, pager: ViewPager)
extends FragmentPagerAdapter(manager)
with TabHost.OnTabChangeListener with ViewPager.OnPageChangeListener {
    val channels = new ArrayBuffer[Channel]
    val comp = new ChannelComparator

    tabhost.setOnTabChangedListener(this)
    pager.setOnPageChangeListener(this)
    pager.setAdapter(this)

    val tabs = new ArrayBuffer[TabInfo]()
    var page = 0

    {
        var activity = tabhost.getContext().asInstanceOf[MainActivity]
        if (activity.service != null)
            onServiceConnected(activity.service)
        else
            activity.serviceConnectionListeners += onServiceConnected
    }

    def onServiceConnected(service: IrcService) {
        if (service.channels.size > channels.size)
            (service.channels.keySet -- channels).foreach(addChannel(_))
    }

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

        if (!MainActivity.honeycombAndNewer) {
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

        if (!MainActivity.honeycombAndNewer)
            tabhost.getTabWidget().setStripEnabled(true)
    }
    var tabnum = 0
    def createTab(title: String, fragment: Fragment) {
        tabs += new TabInfo(title, fragment)
        addTab(title)
        notifyDataSetChanged()
    }

    def insertTab(title: String, fragment: Fragment, pos: Int) {
        tabs.insert(pos + 1, new TabInfo(title, fragment))
        addTab(title)
        if (tabs.size == 1) return

        val tw = tabhost.getTabWidget()
        for (i <- 0 until tw.getTabCount()) {
            val v = tw.getChildTabViewAt(i)
            val titleId = if (MainActivity.honeycombAndNewer)
                    android.R.id.title else R.id.title
            findView[TextView](v, titleId).setText(tabs(i).title)
        }
        notifyDataSetChanged()
    }

    def addChannel(c: Channel) {
        var idx = Collections.binarySearch(channels, c, comp)
        if (idx < 0) {
            idx = idx * -1
            channels.insert(idx - 1, c)
            insertTab(c.name, new MessagesFragment(c.messages), idx - 1)
        } else {
            // tab already exists, TODO update tab indicator
        }
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
        var tag: String = _
    }

    override def getItemPosition(item: Object): Int = {
        val pos = tabs.indexWhere(_.fragment == item)
        return if (pos == -1) PagerAdapter.POSITION_NONE else pos
    }
    override def instantiateItem(container: View, pos: Int): Object = {
        if (mCurTransaction == null)
            mCurTransaction = mFragmentManager.beginTransaction()
        val f = getItem(pos).asInstanceOf[Fragment]
        val name = makeFragmentTag(f)
        tabs(pos).tag = name
        if (f.isDetached())
            mCurTransaction.attach(f)
        else if (!f.isAdded())
            mCurTransaction.add(container.getId(), f, name)
        if (f != mCurrentPrimaryItem)
            f.setMenuVisibility(false)
        f
    }

    def makeFragmentTag(f: Fragment): String =
        "android:switcher:" + System.identityHashCode(f)
}
