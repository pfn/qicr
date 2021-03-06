package com.hanhuy.android.irc

import java.util.UUID

import android.annotation.TargetApi
import android.app.AlertDialog
import android.content.{ClipData, ClipboardManager}
import android.os.Bundle
import android.text.{SpannableString, Spannable, Selection}
import android.view._
import android.widget.AbsListView.OnScrollListener
import android.widget._

import android.support.v4.app._

import com.hanhuy.android.irc.model.Channel
import com.hanhuy.android.irc.model.Query
import com.hanhuy.android.irc.model.Server
import com.hanhuy.android.irc.model.MessageAdapter

import com.hanhuy.android.common._
import com.hanhuy.android.conversions._

import MainActivity._

import Tweaks._

import iota._

abstract class MessagesFragment
extends Fragment with EventBus.RefOwner {

  def adapter: Option[MessageAdapter]

  var lookupId: String = ""

  private[this] var listView: ListView = _

  def tag: String

  import ViewGroup.LayoutParams._
  @TargetApi(11)
  def layout = c[FrameLayout](w[ListView] >>= id(android.R.id.list) >>= lp(MATCH_PARENT, MATCH_PARENT) >>=
    kitkatPadding(getActivity.tabs.getVisibility == View.GONE) >>=
    kestrel { l =>
      l.setDividerHeight(0)
      l.setChoiceMode(AbsListView.CHOICE_MODE_NONE)
      l.setTranscriptMode(AbsListView.TRANSCRIPT_MODE_NORMAL)
      l.setSelector(R.drawable.message_selector)
      if (v(11)) {
        l.setOnItemLongClickListener(single[AdapterView.OnItemLongClickListener] {
          (_: AdapterView[_], view: View, pos: Int, id: Long) => {
            val text = view.asInstanceOf[TextView].getText
            getActivity.systemService[ClipboardManager].setPrimaryClip(
              ClipData.newPlainText("", text))
            Selection.selectAll(text match {
              case s: Spannable => s
              case cs => new SpannableString(cs)
            })
            true
          }
        })
      }
      if (v(19)) l.setClipToPadding(false)
      l.setDrawSelectorOnTop(true)
      if (!getActivity.isFinishing)
        adapter.foreach(l.setAdapter)
      l.scrollStateChanged((v, s) => {
        import OnScrollListener._
        if (s == SCROLL_STATE_TOUCH_SCROLL || s == SCROLL_STATE_FLING) {
          hideIME()
        }
      })
    })

  val manager = IrcManager.init()

  override def onCreate(bundle: Bundle) {
    super.onCreate(bundle)
    bundle.?.foreach(b => lookupId = b.getString("channel.key"))

    if (adapter.isEmpty) {
      manager.queueCreateActivity(0)
      if (!getActivity.isFinishing)
        getActivity.finish()
    }
  }


  override def onSaveInstanceState(outState: Bundle) = {
    lookupId = UUID.randomUUID.toString
    outState.putString("channel.key", lookupId)
    super.onSaveInstanceState(outState)
  }

  override def onResume() {
    super.onResume()
    adapter foreach (_.context = getActivity)
    scrollToEnd()
  }

  def scrollToEnd() {
    for {
      a <- adapter
      l <- listView.?
    } l.setSelection(a.getCount - 1)
  }

  override def onCreateView(i: LayoutInflater, c: ViewGroup, b: Bundle) = {
    adapter foreach (_.context = getActivity)
    val v = layout.perform()
    listView = v
    def inputHeight = for {
      a <- MainActivity.instance
      h <- a.inputHeight
    } yield h

    inputHeight.fold {
      v.onPreDraw { l =>
        inputHeight exists { h =>
          v.getViewTreeObserver.removeOnPreDrawListener(l)
          val p = v.getPaddingBottom + h
          v.setPadding(
            v.getPaddingLeft, v.getPaddingTop, v.getPaddingRight, p)
          true
        }
      }
    }{ h =>
      val p = v.getPaddingBottom + h
      v.setPadding(v.getPaddingLeft, v.getPaddingTop, v.getPaddingRight, p)
    }
    v
  }

}

class ChannelFragment(_channel: Option[Channel])
  extends MessagesFragment with EventBus.RefOwner {

  def this() = this(None)

  lazy val channel = _channel orElse {
    manager.getChannel[Channel](lookupId)
  }

  override lazy val adapter = channel map (_.messages)

  lazy val tag = getFragmentTag(channel)
  override def onCreate(bundle: Bundle) {
    super.onCreate(bundle)

    setHasOptionsMenu(true)
  }

  override def onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    channel foreach { c =>
      manager.saveChannel(lookupId, c)
    }
  }

  override def onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) = {
    inflater.inflate(R.menu.channel_menu, menu)
    if (!Settings.get(Settings.IRC_LOGGING)) {
      val item = menu.findItem(R.id.channel_log)
      item.setVisible(false)
    }

    channel foreach { chan =>
      val favorite = menu.findItem(R.id.channel_favorite)
      if (Config.Favorites(chan)) {
        favorite.setIcon(resolveAttr(R.attr.qicrFavoriteOnIcon, _.resourceId))
        favorite.setChecked(true)
      } else {
        favorite.setIcon(resolveAttr(R.attr.qicrFavoriteOffIcon, _.resourceId))
        favorite.setChecked(false)
      }
    }
  }

  override def onOptionsItemSelected(item: MenuItem): Boolean = {
    if (R.id.nicklist == item.getItemId) {
      MainActivity.instance foreach { _.toggleNickList() }
    }
    if (R.id.channel_favorite == item.getItemId) {
      val favorite = !item.isChecked
      item.setIcon(resolveAttr(
        if (favorite) R.attr.qicrFavoriteOnIcon
        else R.attr.qicrFavoriteOffIcon, _.resourceId))
      channel foreach { ch =>
        if (favorite) {
          Config.Favorites += ch
        } else {
          Config.Favorites -= ch
        }
      }
      item.setChecked(favorite)
      true
    } else if (R.id.channel_log == item.getItemId) {
      channel foreach { ch =>
        startActivity(MessageLogActivity.createIntent(ch))
        getActivity.overridePendingTransition(
          R.anim.slide_in_left, R.anim.slide_out_right)
      }
      true
    } else if (R.id.channel_close == item.getItemId) {
      val activity = getActivity
      val prompt = Settings.get(Settings.CLOSE_TAB_PROMPT)

      channel foreach { c =>
        def removeChannel() {
          if (c.state == Channel.State.JOINED) {
            manager.channels.get(c) foreach {
              _.part()
            }
          }
          manager.remove(c)
          activity.adapter.removeTab(activity.adapter.getItemPosition(this))
        }
        if (c.state == Channel.State.JOINED && prompt) {
          val builder = new AlertDialog.Builder(activity)
          builder.setTitle(R.string.channel_close_confirm_title)
          builder.setMessage(getString(R.string.channel_close_confirm))
          builder.setPositiveButton(R.string.yes, () => {
            removeChannel()
            c.state = Channel.State.PARTED
          })
          builder.setNegativeButton(R.string.no, null)
          builder.create().show()
        } else {
          removeChannel()
        }
      }
      true
    } else false
  }
}

class QueryFragment(_query: Option[Query]) extends MessagesFragment {
  def this() = this(None)
  lazy val query = _query orElse {
    manager.getChannel[Query](lookupId)
  }
  override lazy val adapter = query map (_.messages)
  lazy val tag = getFragmentTag(query)

  override def onCreate(bundle: Bundle) {
    super.onCreate(bundle)
    setHasOptionsMenu(true)
  }

  override def onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    query foreach { q =>
      manager.saveChannel(lookupId, q)
    }
  }
  override def onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) = {
    inflater.inflate(R.menu.query_menu, menu)
    if (!Settings.get(Settings.IRC_LOGGING)) {
      val item = menu.findItem(R.id.channel_log)
      item.setVisible(false)
    }
  }

  override def onOptionsItemSelected(item: MenuItem): Boolean = {
    if (R.id.query_close == item.getItemId) {
      val activity = getActivity
      val prompt = Settings.get(Settings.CLOSE_TAB_PROMPT)
      def removeQuery() {
        manager.remove(query.get)
        activity.adapter.removeTab(activity.adapter.getItemPosition(this))
      }
      if (prompt) {
        val builder = new AlertDialog.Builder(activity)
        builder.setTitle(R.string.query_close_confirm_title)
        builder.setMessage(getString(R.string.query_close_confirm))
        builder.setPositiveButton(R.string.yes, removeQuery _)
        builder.setNegativeButton(R.string.no, null)
        builder.create().show()
      } else
        removeQuery()
      true
    } else if (R.id.channel_log == item.getItemId) {
      startActivity(MessageLogActivity.createIntent(query.get))
      getActivity.overridePendingTransition(
        R.anim.slide_in_left, R.anim.slide_out_right)
      true
    } else false
  }

}

class ServerMessagesFragment(_server: Option[Server]) extends MessagesFragment {
  def this() = this(None)
  lazy val server = _server orElse {
    manager.getChannel[Server](lookupId)
  }
  override lazy val adapter = server map (_.messages)
  lazy val tag = getFragmentTag(server)
  override def onCreate(bundle: Bundle) {
    super.onCreate(bundle)
    setHasOptionsMenu(true)
  }

  override def onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    server foreach { s =>
      manager.saveChannel(lookupId, s)
    }
  }
  override def onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
    inflater.inflate(R.menu.server_messages_menu, menu)
    server.foreach { s =>
      val connected = s.state.now match {
        case Server.INITIAL => false
        case Server.DISCONNECTED => false
        case _ => true
      }

      menu.findItem(R.id.server_connect).setVisible(!connected)
      menu.findItem(R.id.server_disconnect).setVisible(connected)
    }
  }

  override def onOptionsItemSelected(item: MenuItem) : Boolean = {
    item.getItemId match {
      case R.id.server_close =>
        getActivity.adapter.removeTab(getActivity.adapter.getItemPosition(this))
        true
      case R.id.server_connect =>
        server.fold{
          Toast.makeText(getActivity, R.string.server_not_selected,
            Toast.LENGTH_SHORT).show()
        }(manager.connect)
        true
      case R.id.server_disconnect =>
        server.fold {
          Toast.makeText(getActivity, R.string.server_not_selected,
            Toast.LENGTH_SHORT).show()
        }(manager.disconnect(_))
        true
      case R.id.server_options =>
        ServerSetupActivity.start(getActivity, server)
        true
      case R.id.server_messages =>
        server.fold {
          Toast.makeText(getActivity, R.string.server_not_selected,
            Toast.LENGTH_SHORT).show()
        }(getActivity.adapter.addServer)
        true
      case _ => false
    }
  }
}
