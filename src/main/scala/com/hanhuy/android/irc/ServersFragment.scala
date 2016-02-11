package com.hanhuy.android.irc

import android.app.{Activity, AlertDialog}
import com.hanhuy.android.common._
import com.hanhuy.android.conversions._
import com.hanhuy.android.extensions._
import com.hanhuy.android.appcompat.extensions.ExtensionOfToolbar

import android.os.Bundle
import android.support.v4.app.Fragment
import android.view._
import android.support.v7.widget.{CardView, LinearLayoutManager, RecyclerView, Toolbar}
import android.widget._
import com.hanhuy.android.common.{UiBus, EventBus}
import com.hanhuy.android.irc.model.{BusEvent, Server}
import iota._
import rx.Obs
import ViewGroup.LayoutParams._
import Tweaks._
import Futures._

import scala.concurrent.{Promise, Future}

/**
  * @author pfnguyen
  */

class ServersFragment extends Fragment with EventBus.RefOwner {
  def activity = getActivity.asInstanceOf[MainActivity]
  val manager = IrcManager.init()
  lazy val adapter = ServersAdapter(getActivity)
  def server = selectedItem.map(manager.getServers)

  lazy val emptyView = new LinearLayout(getActivity)
  lazy val recycler = new RecyclerView(getActivity)
  lazy val layout = {
    l[FrameLayout](
      emptyView.!(
        w[TextView] >>= k.text(R.string.server_none) >>= lpK(MATCH_PARENT, WRAP_CONTENT, 0)(
          margins(all = getResources.getDimensionPixelSize(R.dimen.standard_margin))) >>= k.gravity(Gravity.CENTER),
        w[Button] >>= id(R.id.add_server) >>= k.text(R.string.add_server) >>=
          hook0.onClick(IO {
            ServerSetupActivity.start(getActivity)
          }) >>= lpK(MATCH_PARENT, WRAP_CONTENT)(margins(
          all = getResources.getDimensionPixelSize(R.dimen.standard_margin)))
      ) >>= id(android.R.id.empty) >>= vertical >>= lpK(MATCH_PARENT, MATCH_PARENT)(margins(all = 16.dp)) >>= kitkatPadding(activity.tabs.getVisibility == View.GONE),
      recycler.! >>= id(android.R.id.list) >>= kestrel { l =>
        l.setLayoutManager(new LinearLayoutManager(getActivity))
        l.setAdapter(adapter)
        if (Config.servers.now.nonEmpty)
          emptyView.setVisibility(View.GONE)
        //        if (v(19)) l.setClipToPadding(false)
      } >>= lp(MATCH_PARENT, MATCH_PARENT) >>= kitkatPadding(activity.tabs.getVisibility == View.GONE)
    ) >>= id(Id.servers_container)
  }

  UiBus += {
    case BusEvent.ServerAdded(server)   =>
      emptyView.setVisibility(View.GONE)
      recycler.setVisibility(View.VISIBLE)
      if (getActivity != null)
        adapter.notifyItemInserted(manager.getServers.indexOf(server))
    case BusEvent.ServerChanged(server) =>
      if (getActivity != null)
        adapter.notifyItemChanged(manager.getServers.indexOf(server))
    case BusEvent.ServerRemoved(server) =>
      if (Config.servers.now.isEmpty) {
        emptyView.setVisibility(View.VISIBLE)
        recycler.setVisibility(View.GONE)
      }
      selectedItem = None
      if (getActivity != null)
        adapter.notifyDataSetChanged()
  }

  override def onCreate(bundle: Bundle) {
    super.onCreate(bundle)
    setHasOptionsMenu(true)
  }

  override def onCreateView(inflater: LayoutInflater, container: ViewGroup, bundle: Bundle) = layout.perform()

  private[this] var selectedItem = Option.empty[Int]

  override def onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) =
    inflater.inflate(R.menu.servers_menu, menu)

  override def onOptionsItemSelected(item: MenuItem) : Boolean = {
    if (R.id.add_server == item.getItemId) {
      ServerSetupActivity.start(getActivity)
      true
    } else false
  }

  def getLayoutProperty[A](view: View, pred: => Boolean, result: => A): Future[A] = {
    if (pred) Future.successful(result)
    else {
      val p = Promise[A]()
      view.onPreDraw(l => {
        if (pred) {
          p.success(result)
          view.getViewTreeObserver.removeOnPreDrawListener(l)
          true
        } else false
      })
      p.future
    }
  }
  def getHeight(view: View): Future[Int] =
    getLayoutProperty(view, view.getHeight != 0, view.getHeight)

  case class ServerItemHolder(view: CardView,
                              infoRow: LinearLayout,
                              expandedInfo: LinearLayout,
                              toolbar: Toolbar,
                              input: EditText,
                              progressBar: ProgressBar,
                              status: ImageView,
                              serverItem: TextView,
                              serverLag: TextView
                             ) extends RecyclerView.ViewHolder(view) {
    def bind(server: Server, pos: Int): Unit = {
      implicit val ctx = view.getContext
      (serverItem.! >>= k.text(server.name)).perform()
      (progressBar.! >>= visGone(server.state.now == Server.CONNECTING)).perform()

      val menu = toolbar.getMenu
      menu.clear()
      toolbar.inflateMenu(R.menu.server_menu)
      input.setVisibility(if (server.state.now == Server.CONNECTED) View.VISIBLE else View.INVISIBLE)
      if (server.state.now == Server.DISCONNECTED || server.state.now == Server.INITIAL) {
        menu.removeItem(R.id.server_disconnect)
      }
      if (server.messages.isEmpty) {
        menu.removeItem(R.id.server_messages)
      }

      toolbar.onMenuItemClick(_.getItemId match {
        case R.id.server_messages =>
          activity.adapter.addServer(server)
          true
        case R.id.server_connect =>
          if (server.state.now == Server.DISCONNECTED || server.state.now == Server.INITIAL) {
            manager.connect(server)
          } else {
            manager.disconnect(server, None, false).onComplete { case _ =>
              manager.connect(server)
            }
          }
          true
        case R.id.server_disconnect =>
          manager.disconnect(server)
          true
        case R.id.server_options =>
          ServerSetupActivity.start(getActivity, server.?)
          true
        case R.id.server_delete =>
          val builder = new AlertDialog.Builder(getActivity)
          val mgr = getActivity.getSupportFragmentManager
          builder.setTitle(R.string.server_confirm_delete)
          builder.setMessage(getActivity.getString(
            R.string.server_confirm_delete_message,
            server.name))
          builder.setPositiveButton(R.string.yes,
            () => {
              manager.deleteServer(server)
              ()
            })
          builder.setNegativeButton(R.string.no, null)
          builder.create().show()
          true
        case _ => false
      })

      (IO(status) >>= k.imageResource(server.state.now match {
        case Server.INITIAL      => android.R.drawable.presence_offline
        case Server.DISCONNECTED => android.R.drawable.presence_busy
        case Server.CONNECTED    => android.R.drawable.presence_online
        case Server.CONNECTING   => android.R.drawable.presence_away
      }) >>= visGone(server.state.now != Server.CONNECTING)).perform()

      (IO(serverLag) >>=
        kestrel { tv =>
          if (iota.v(12)) {
            // early honeycomb and gingerbread will leak the obs
            tv.onDetachedFromWindow(
              tv.getTag(Id.obs).asInstanceOf[Obs].?.foreach(_.kill()))
          }
          tv.getTag(Id.obs).asInstanceOf[Obs].?.foreach(_.kill())
          // any thread may update currentLag, must run on correct thread
          val obs = server.currentLag.trigger(UiBus.post {
            val lag = if (server.state.now == Server.CONNECTED) {
              val l = server.currentPing flatMap { p =>
                if (server.currentLag.now == 0) None
                else Some((System.currentTimeMillis - p).toInt)
              } getOrElse server.currentLag.now
              Server.intervalString(l)
            } else ""
            tv.setText(lag)
          })
          obss = obs :: obss
          tv.setTag(Id.obs, obs)
        }).perform()
      selectedItem match {
        case Some(selected) if selected == pos =>
          val lp = expandedInfo.getLayoutParams.asInstanceOf[ViewGroup.MarginLayoutParams]
          val h = infoRow.getHeight
          // add an arbitrary 16.dp for margins
          def connectedHeight = if (server.state.now == Server.CONNECTED) 48.dp + 16.dp else 0
          if (h == 0) getHeight(infoRow).onSuccessMain { case height =>
            view.getLayoutParams.height = h + actionBarHeight + connectedHeight + 16.dp
            lp.topMargin = height
            lp.height = MATCH_PARENT
            adapter.notifyItemChanged(pos)
          } else {
            view.getLayoutParams.height = h + actionBarHeight + connectedHeight + 16.dp
            lp.topMargin = h
            lp.height = MATCH_PARENT
          }
        case _ =>
          view.getLayoutParams.height = WRAP_CONTENT
          val lp = expandedInfo.getLayoutParams.asInstanceOf[ViewGroup.MarginLayoutParams]
          lp.height = 0
          lp.topMargin = 0
      }
      view.onClick0 {
        if (selectedItem.contains(pos)) {
          selectedItem = None
          adapter.notifyItemChanged(pos)
        } else {
          selectedItem foreach (i => adapter.notifyItemChanged(i))
          selectedItem = Some(pos)
          adapter.notifyItemChanged(pos)
        }
      }
    }
  }
  case class ServersAdapter(override val context: Activity) extends RecyclerView.Adapter[ServerItemHolder] with HasContext {

    override def onBindViewHolder(holder: ServerItemHolder, pos: Int) = {
      val server = manager.getServers(pos)
      holder.bind(server, pos)
    }

    override def onCreateViewHolder(viewGroup: ViewGroup, i: Int) = layout

    override def getItemCount = manager.getServers.size

    override def getItemId(p1: Int) = p1

    def layout = {
      val holder = ServerItemHolder(
        cardView.perform(),
        new LinearLayout(context),
        new LinearLayout(context),
        new Toolbar(context),
        new EditText(context),
        new ProgressBar(context, null, R.attr.qicrProgressSpinnerStyle),
        new ImageView(context),
        new TextView(context),
        new TextView(context))
      c[AbsListView](holder.view.!(
        holder.infoRow.!(
          l[FrameLayout](
            holder.progressBar.! >>=
              lp(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER) >>=
              padding(left = 6 dp, right = 6 dp) >>= kestrel { p: ProgressBar =>
              p.setIndeterminate(true) } >>= k.visibility(View.GONE),
            holder.status.! >>= lp(64 dp, 64 dp, Gravity.CENTER) >>=
              k.imageResource(android.R.drawable.presence_offline) >>=
              k.scaleType(ImageView.ScaleType.CENTER_INSIDE) >>= padding(left = 6 dp, right = 6 dp) >>=
              id(Id.server_item_status)
          ) >>= kestrel { v: FrameLayout => v.setMeasureAllChildren(true) },
          holder.serverItem.! >>= lp(0, 64 dp, 1) >>=
            padding(left = 6 dp, right = 6 dp) >>= kestrel { tv =>
            tv.setGravity(Gravity.CENTER_VERTICAL)
            tv.setTextAppearance(context, android.R.style.TextAppearance_Large)
          },
          holder.serverLag.! >>= lp(WRAP_CONTENT, WRAP_CONTENT) >>=
            padding(right = 16.dp) >>=
            kestrel { tv =>
              tv.setGravity(Gravity.CENTER_VERTICAL)
              tv.setTextAppearance(context, android.R.style.TextAppearance_Small)
            }
        ) >>= k.gravity(Gravity.CENTER) >>= lp(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP),
        holder.expandedInfo.!(
          holder.toolbar.! >>= lp(MATCH_PARENT, actionBarHeight) >>= kestrel { t =>
            t.setPopupTheme(resolveAttr(R.attr.qicrToolbarPopupTheme, _.resourceId))
          },
          w[View] >>= lp(MATCH_PARENT, 0, 1),
          holder.input.! >>=
            lpK(MATCH_PARENT, 48.dp)(margins(all = 8.dp)) >>=
            k.hint(R.string.input_placeholder) >>= inputTweaks >>= k.visibility(View.INVISIBLE) >>=
            padding(left = 8 dp, right = 8 dp) >>=
            k.backgroundDrawable(inputBackground) >>= kestrel { e =>
            e.setOnEditorActionListener(activity.proc.onEditorActionListener _)
            e.setOnKeyListener(activity.proc.onKeyListener _)
            e.addTextChangedListener(activity.proc.TextListener)
          }
        ) >>= lp(MATCH_PARENT, 0) >>= vertical
      ) >>= lp(MATCH_PARENT, WRAP_CONTENT)).perform()
      holder
    }

  }
  def inputBackground = styleableAttrs(R.styleable.AppTheme, _.getDrawable(R.styleable.AppTheme_inputBackground))
  private[this] var obss = List.empty[Obs]

  override def onPause() = {
    super.onPause()
    obss.foreach(_.kill())
    obss = Nil
  }
}
