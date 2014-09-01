package com.hanhuy.android.irc

import android.view.inputmethod.InputMethodManager
import com.hanhuy.android.irc.model.Server
import com.hanhuy.android.irc.model.ServerComparator
import com.hanhuy.android.irc.model.Channel
import com.hanhuy.android.irc.model.Query
import com.hanhuy.android.irc.model.ChannelLike
import com.hanhuy.android.irc.model.ChannelLikeComparator
import com.hanhuy.android.irc.model.FragmentPagerAdapter
import com.hanhuy.android.irc.model.BusEvent

import android.app.NotificationManager
import android.util.Log
import android.view.{View, ViewGroup}
import android.view.LayoutInflater
import android.widget.TextView
import android.widget.BaseAdapter

import android.support.v4.view.{ViewPager, PagerAdapter}
import android.support.v4.app.Fragment

import scala.collection.JavaConversions._
import scala.math.Numeric.{IntIsIntegral => Math}

import java.util.Collections

import MainPagerAdapter._
import com.hanhuy.android.common._
import AndroidConversions._
import com.hanhuy.android.irc.model.BusEvent.ChannelStatusChanged
import TypedResource.activityToTyped

object MainPagerAdapter {
  val TAG = "MainPagerAdapter"

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
    var server: Option[Server] = None
    var flags = TabInfo.FLAG_NONE
    override def toString =
      title + " fragment=" + fragment + " channel=" + channel
  }
}
class MainPagerAdapter(activity: MainActivity)
extends FragmentPagerAdapter(activity.getSupportFragmentManager)
with ViewPager.OnPageChangeListener
with EventBus.RefOwner {
  val manager = IrcManager.start()
  private var channels = List.empty[ChannelLike]
  private var servers  = List.empty[Server]
  private var tabs = List.empty[TabInfo]
  lazy val channelcomp = ChannelLikeComparator
  lazy val servercomp  = ServerComparator
  lazy val tabindicators = activity.findView(TR.tabs)

  def channelBase = servers.size + 1
  def currentTab = tabs(page)

  pager.setAdapter(this)
  tabindicators.setViewPager(pager)
  tabindicators.setOnPageChangeListener(this)
  lazy val fm = activity.getSupportFragmentManager
  lazy val pager = activity.pager
  lazy val nm = activity.systemService[NotificationManager]

  var page = 0

  UiBus += {
  case BusEvent.ServerChanged(server)   => serverStateChanged(server)
  case BusEvent.ChannelMessage(c, m)    => refreshTabTitle(c)
  case BusEvent.ChannelAdded(c)         => addChannel(c)
  case BusEvent.PrivateMessage(q, m)    => addChannel(q)
  case BusEvent.StartQuery(q)           => pager.setCurrentItem(addChannel(q))
  case BusEvent.ChannelStatusChanged(_) => channels foreach refreshTabTitle
  }

  def refreshTabs() {
    if (manager.servs.size > servers.size)
      (manager.servs.values.toSet -- servers) foreach addServer

    if (manager.channels.size > channels.size)
      (manager.channels.keySet -- channels) foreach addChannel
    channels foreach refreshTabTitle
    channels foreach (_.messages.context = activity)
  }

  def serverStateChanged(server: Server) {
    server.state match {
    case Server.State.DISCONNECTED =>
      // iterate channels and flag them as disconnected
      (0 until channels.size) foreach { i =>
        if (channels(i).server == server) {
          tabs(i + channelBase).flags |= TabInfo.FLAG_DISCONNECTED
          refreshTabTitle(i + channelBase)
        }
      }
    case Server.State.CONNECTED =>
      (0 until channels.size) foreach { i =>
        if (channels(i).server == server) {
          tabs(i + channelBase).flags &= ~TabInfo.FLAG_DISCONNECTED
          refreshTabTitle(i + channelBase)
        }
      }
    case _ => ()
    }
  }

  def refreshTabTitle(c: ChannelLike) {
    val idx = Collections.binarySearch(channels, c, channelcomp)
    if (idx < 0) return
    val t = tabs(idx + channelBase)

    // disconnected flag needs to be set before returning because page ==
    if (c.server.state == Server.State.DISCONNECTED)
      t.flags |= TabInfo.FLAG_DISCONNECTED

    if (page == idx + channelBase) {
      // make sure they're cleared when coming back
      c.newMentions = false
      c.newMessages = false
      t.flags &= ~TabInfo.FLAG_NEW_MESSAGES
      t.flags &= ~TabInfo.FLAG_NEW_MENTIONS
      return
    }
    if (c.newMentions)
      activity.newmessages.setVisibility(View.VISIBLE)

    if (c.newMessages)
      t.flags |= TabInfo.FLAG_NEW_MESSAGES
    if (c.newMentions)
      t.flags |= TabInfo.FLAG_NEW_MENTIONS
    refreshTabTitle(idx + channelBase)
  }

  val refreshTabRunnable: Runnable = () => {
    tabindicators.notifyDataSetChanged()
    DropDownAdapter.notifyDataSetChanged()
  }
  def refreshTabTitle(pos: Int) {
    UiBus.handler.removeCallbacks(refreshTabRunnable)
    UiBus.handler.postDelayed(refreshTabRunnable, 100)
  }

  def makeTabTitle(pos: Int) = {
    val t = tabs(pos)
    var title: CharSequence = t.title

    if ((t.flags & TabInfo.FLAG_NEW_MENTIONS) > 0)
      title = SpannedGenerator.textColor(0xffff0000, title)
    else if ((t.flags & TabInfo.FLAG_NEW_MESSAGES) > 0)
      title = SpannedGenerator.textColor(0xff007f7f, title)

    if ((t.flags & TabInfo.FLAG_DISCONNECTED) > 0)
      title = "(%1)" formatSpans title

    title
  }

  def actionBarNavigationListener(pos: Int, id: Long) = {
    pager.setCurrentItem(pos)
    pageChanged(pos)
    true
  }

  // PagerAdapter
  override def getCount = tabs.length
  override def getItem(pos: Int): Fragment = tabs(pos).fragment

  private def pageChanged(pos: Int) {
    page = pos

    activity.pageChanged(pos)
    val t = tabs(pos)
    t.flags &= ~TabInfo.FLAG_NEW_MESSAGES
    t.flags &= ~TabInfo.FLAG_NEW_MENTIONS
    t.channel.foreach(c => {
      if (c.newMentions) {
        nm.cancel(c match {
        case _: Channel => IrcManager.MENTION_ID
        case _: Query   => IrcManager.PRIVMSG_ID
        })
      }
      c.newMessages = false
      c.newMentions = false
      ServiceBus.send(ChannelStatusChanged(c))
    })
    manager.lastChannel = t.channel

    activity.newmessages.setVisibility(
      if (channels.find(_.newMentions).isEmpty) View.GONE else View.VISIBLE)

    HoneycombSupport.setSelectedNavigationItem(pos)
    HoneycombSupport.setSubtitle(t.channel map { _.server } orElse
      t.server map { s =>
        val chan = if (activity.settings.get(Settings.NAVIGATION_MODE) ==
          Settings.NAVIGATION_MODE_DRAWER) " " + t.title else ""
        " - %s%s: %s" format(s.name, chan, Server.intervalString(s.currentLag))
      } orNull)

    refreshTabTitle(pos)
    val imm = activity.systemService[InputMethodManager]
    Option(activity.getCurrentFocus) foreach { f =>
      imm.hideSoftInputFromWindow(f.getWindowToken, 0)
    }
  }

  def selectTab(cname: String, sname: String) {
    val tab = tabs indexWhere {
      _.channel map {
        c => cname == c.name && sname == c.server.name
      } getOrElse { false }
    }
    // onpagechanged will scroll the tab(?)
    pager.setCurrentItem(tab)
  }

  def goToNewMessages() {
    val pos = channels.indexWhere(_.newMentions)
    if (pos != -1)
      pager.setCurrentItem(pos + channelBase)
  }

  // OnPageChangeListener
  override def onPageScrolled(pos: Int, posOff: Float, posOffPix: Int) = ()
  override def onPageSelected(pos: Int) {
    pageChanged(pos)
  }

  override def onPageScrollStateChanged(state: Int) = ()

  def createTab(title: String, fragment: Fragment) {
    tabs = tabs :+ new TabInfo(title, fragment)
    notifyDataSetChanged()
  }

  private def insertTab(title: String, fragment: Fragment, pos: Int) = {
    val info = new TabInfo(title, fragment)
    val base = fragment match {
    case _: QueryFragment | _: ChannelFragment => channelBase
    case _: ServerMessagesFragment => 1
    }
    tabs = insert(tabs, pos + base, info)
    if (tabs.size > 1) {
      notifyDataSetChanged()
    }
    info
  }

  private def insert[A](list: List[A], idx: Int, item: A): List[A] = {
    val (prefix,suffix) = list.splitAt(idx)
    prefix ++ List(item) ++ suffix
  }
  private def addChannel(c: ChannelLike) = {
    var idx = Collections.binarySearch(channels, c, channelcomp)
    if (idx < 0) {
      idx = idx * -1
      channels = insert(channels, idx - 1, c)
      val tag = MainActivity.getFragmentTag(c)
      val f = fm.findFragmentByTag(tag)
      val frag = if (f != null) f else c match {
        case ch: Channel => new ChannelFragment(ch)
        case qu: Query   => new QueryFragment(qu)
      }
      val info = insertTab(c.name, frag, idx - 1)
      refreshTabTitle(idx + channelBase - 1) // why -1?
      info.channel = Some(c)
    } else {
      tabs(idx + channelBase).flags &= ~TabInfo.FLAG_DISCONNECTED
      refreshTabTitle(idx + channelBase)
    }
    idx
  }

  def addServer(s: Server) {
    var idx = Collections.binarySearch(servers, s, servercomp)
    if (idx < 0) {
      idx = idx * -1
      servers = insert(servers, idx - 1, s)
      val tag = MainActivity.getFragmentTag(s)
      val f = fm.findFragmentByTag(tag)
      val frag = if (f != null) f else new ServerMessagesFragment(s)
      val info = insertTab(s.name, frag, idx - 1)
      refreshTabTitle(idx)
      pager.setCurrentItem(idx)
      info.server = Some(s)
    } else {
      tabs(idx).flags &= ~TabInfo.FLAG_DISCONNECTED
      refreshTabTitle(idx + 1)
    }
  }

  def removeTab(pos: Int) {
    if (pos < 0) {
      Log.d(TAG, "Available tabs: " + tabs)
      Log.w(TAG, "Invalid position for removeTab: " + pos,
        new IllegalArgumentException)
      return
    }
    pager.setCurrentItem(0)
    val i = pos - 1
    if (i < servers.size)
      servers = servers filterNot (_ == servers(i))
    else
      channels = channels filterNot (_ == channels(i-servers.size))
    tabs = tabs filterNot (_== tabs(pos))
    val idx = Math.max(0, i)
    notifyDataSetChanged()
    pager.setCurrentItem(idx)
  }

  override def getItemPosition(item: Object): Int = {
    val pos = tabs.indexWhere(_.fragment == item)
    if (pos == -1) PagerAdapter.POSITION_NONE else pos
  }

  override def instantiateItem(container: ViewGroup, pos: Int) = {
    if (mCurTransaction == null)
      mCurTransaction = mFragmentManager.beginTransaction()
    val f = getItem(pos)
    val name = makeFragmentTag(f)
    tabs(pos).tag = Some(name)
    if (f.isDetached)
      mCurTransaction.attach(f)
    else if (!f.isAdded)
      mCurTransaction.add(container.getId, f, name)
    // because the ordering of instantiateItem vs. insertTab can't be
    // guaranteed, always make the menu invisible (true?)
    //if (f != mCurrentPrimaryItem)
    f.setMenuVisibility(false)
    f
  }

  private def makeFragmentTag(f: Fragment) = {
    f match {
    case m: MessagesFragment => m.tag
    case _: ServersFragment => MainActivity.SERVERS_FRAGMENT
    case _ => "viewpager:" + System.identityHashCode(f)
    }
  }

  object DropDownAdapter extends BaseAdapter {
    lazy val inflater = activity.systemService[LayoutInflater]
    override def getItem(pos: Int) = tabs(pos)
    override def getCount = tabs.length
    override def getItemId(pos: Int) = tabs(pos).fragment.getId

    override def getItemViewType(pos: Int): Int = {
      val tab = tabs(pos)
      if  (tab.channel.isDefined || tab.server.isDefined) 0 else 1
    }

    override def getViewTypeCount: Int = 2

    override def getView(pos: Int, convert: View, container: ViewGroup) = {
      val tab = tabs(pos)

      val t = getItemViewType(pos)
      val view = if (convert == null ||
        t != convert.getTag(R.id.dropdown_view_type)) {
        val layout = getItemViewType(pos) match {
          case 0 => R.layout.simple_dropdown_item_2line
          case 1 => R.layout.simple_dropdown_item_1line
        }
        inflater.inflate(layout, container, false)
      } else convert
      view.setTag(R.id.dropdown_view_type, t)

      view.findView[TextView](android.R.id.text1).setText(makeTabTitle(pos))

      val line2 = view.findView[TextView](android.R.id.text2)
      tab.channel map { c =>
        val s = c.server

        if (pos == page) { // show lag for the selected item
          line2.setText(" - %s: %s" format(
            s.name, Server.intervalString(s.currentLag)))
        } else {
          line2.setText(" - %s: %s" format (s.name, s.currentNick))
        }
      } getOrElse (tab.server map { s =>

        if (pos == page) {
          line2.setText(" - %s (%s)" format (
            s.currentNick, Server.intervalString(s.currentLag)))
        } else {
          line2.setText(" - %s" format s.currentNick)
        }
      })
      view
    }
  }

  override def getPageTitle(position: Int) = makeTabTitle(position)

  override def notifyDataSetChanged() {
    tabindicators.notifyDataSetChanged()
    super.notifyDataSetChanged()
  }
}
