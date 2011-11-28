package com.hanhuy.android.irc

import com.hanhuy.android.irc.model.Server
import com.hanhuy.android.irc.model.Channel
import com.hanhuy.android.irc.model.Query
import com.hanhuy.android.irc.model.ChannelLike
import com.hanhuy.android.irc.model.ChannelLikeComparator
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
import scala.math.Numeric.{IntIsIntegral => Math}

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
    val channels = new ArrayBuffer[ChannelLike]
    val comp = new ChannelLikeComparator

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

    def refreshTabs(service: IrcService) {
        if (service.channels.size > channels.size)
            (service.channels.keySet -- channels).foreach(addChannel(_))
    }

    def onServiceConnected(service: IrcService) {
        refreshTabs(service)
        // TODO figure out how to remove the listener
        service.serverChangedListeners += serverStateChanged
    }

    def serverStateChanged(server: Server) {
        // TODO FIXME appears to not work
        server.state match {
        case Server.State.DISCONNECTED => {
            // iterate channels and flag them as disconnected
            for (i <- 0 until channels.size) {
                if (channels(i).server == server) {
                    tabs(i+1).flags |= TabInfo.FLAG_DISCONNECTED
                    refreshTabTitle(i+1)
                }
            }
        }
        case _ => Unit
        }
    }

    def refreshTabTitle(c: ChannelLike) {
        var idx = Collections.binarySearch(channels, c, comp)
        if (idx == -1) return
        val t = tabs(idx + 1)
        if (page == idx + 1) {
            t.flags &= ~TabInfo.FLAG_NEW_MESSAGES
            t.flags &= ~TabInfo.FLAG_NEW_MENTIONS
            return
        }

        if (c.newMessages)
            t.flags |= TabInfo.FLAG_NEW_MESSAGES
        if (c.newMentions)
            t.flags |= TabInfo.FLAG_NEW_MENTIONS
        refreshTabTitle(idx + 1)
    }

    def refreshTabTitle(pos: Int) {
        val t = tabs(pos)
        var title = t.title

        if ((t.flags & TabInfo.FLAG_NEW_MENTIONS) > 0) title = "*" + title
        else if ((t.flags & TabInfo.FLAG_NEW_MESSAGES) > 0) title = "+" + title

        if ((t.flags & TabInfo.FLAG_DISCONNECTED) > 0) title = "(" + title + ")"

        setTabTitle(title, pos)
    }

    // PagerAdapter
    override def getCount() : Int = tabs.length
    override def getItem(pos: Int) : Fragment = tabs(pos).fragment

    // OnTabChangeListener
    override def onTabChanged(tabId: String) {
        val idx = tabhost.getCurrentTab()
        pager.setCurrentItem(idx)
        val activity = tabhost.getContext().asInstanceOf[MainActivity]
        activity.updateMenuVisibility(idx)
        page = idx
        pageChanged(page)
    }

    def pageChanged(pos: Int) {
        val t = tabs(pos)
        t.flags &= ~TabInfo.FLAG_NEW_MESSAGES
        t.flags &= ~TabInfo.FLAG_NEW_MENTIONS
        if (!t.channel.isEmpty) {
            t.channel.get.newMessages = false
            t.channel.get.newMentions = false
        }

        refreshTabTitle(pos)
    }

    // OnPageChangeListener
    override def onPageScrolled(pos: Int, posOff: Float, posOffPix: Int) = Unit
    override def onPageSelected(pos: Int) {
        page = pos
        val activity = tabhost.getContext().asInstanceOf[MainActivity]

        tabhost.setCurrentTab(pos)
        val v = tabhost.getTabWidget().getChildTabViewAt(pos)
        val hsv = findView[HorizontalScrollView](
                activity, R.id.tab_scroller)
        val display = tabhost
        val offset = v.getLeft() - display.getWidth() / 2 + v.getWidth() / 2
        hsv.smoothScrollTo(if (offset < 0) 0 else offset, 0)

        activity.updateMenuVisibility(pos)
        pageChanged(pos)
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

    def insertTab(title: String, fragment: Fragment, pos: Int): TabInfo = {
        val info = new TabInfo(title, fragment)
        tabs.insert(pos + 1, info)
        addTab(title)
        if (tabs.size == 1) return info

        val tw = tabhost.getTabWidget()
        for (i <- 0 until tw.getTabCount())
            refreshTabTitle(i)
        notifyDataSetChanged()
        info
    }

    def setTabTitle(title: String, pos: Int) {
        val tw = tabhost.getTabWidget()
        val v = tw.getChildTabViewAt(pos)
        val titleId = if (MainActivity.honeycombAndNewer)
                android.R.id.title else R.id.title
        findView[TextView](v, titleId).setText(title)
    }

    def addChannel(c: ChannelLike) {
        var idx = Collections.binarySearch(channels, c, comp)
        if (idx < 0) {
            idx = idx * -1
            channels.insert(idx - 1, c)
            val frag = c match {
                case ch: Channel => new ChannelFragment(ch.messages, ch)
                case qu: Query   => new QueryFragment(qu.messages, qu)
            }
            val info = insertTab(c.name, frag, idx - 1)
            refreshTabTitle(idx)
            info.channel = Some(c)
        } else {
            tabs(idx+1).flags &= ~TabInfo.FLAG_DISCONNECTED
            refreshTabTitle(idx+1)
        }
    }

    def removeTab(pos: Int) {
        if (pos < 0) {
            Log.w(TAG, "Invalid position for removeTab: " + pos,
                    new IllegalArgumentException)
            return
        }
        tabhost.setCurrentTab(0)
        tabhost.clearAllTabs()
        channels.remove(pos-1)
        tabs.remove(pos)
        for (i <- 0 until tabs.length)
            addTab(tabs(i).title)
        tabhost.setCurrentTab(Math.max(0, pos-1))
        notifyDataSetChanged()
    }

    object TabInfo {
        val FLAG_NONE         = 0
        val FLAG_DISCONNECTED = 1
        val FLAG_NEW_MESSAGES = 2
        val FLAG_NEW_MENTIONS = 4
    }
    class TabInfo(t: String, _fragment: Fragment) {
        var title    = t
        def fragment = _fragment
        var tag: Option[String] = None
        var channel: Option[ChannelLike] = None
        var flags = TabInfo.FLAG_NONE
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
        tabs(pos).tag = Some(name)
        if (f.isDetached())
            mCurTransaction.attach(f)
        else if (!f.isAdded())
            mCurTransaction.add(container.getId(), f, name)
        // because the ordering of instantiateItem vs. insertTab can't be
        // guaranteed, always make the menu invisible
        //if (f != mCurrentPrimaryItem)
        f.setMenuVisibility(false)
        f
    }

    def makeFragmentTag(f: Fragment): String =
        "android:switcher:" + System.identityHashCode(f)
}
