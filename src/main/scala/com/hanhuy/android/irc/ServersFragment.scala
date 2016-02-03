package com.hanhuy.android.irc

import android.app.{Activity, AlertDialog}
import android.support.v7.widget.Toolbar.OnMenuItemClickListener
import com.hanhuy.android.common._
import com.hanhuy.android.conversions._
import com.hanhuy.android.extensions._

import android.os.Bundle
import android.support.v4.app.{FragmentTransaction, FragmentManager, Fragment}
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

class ServersFragment extends Fragment
  with EventBus.RefOwner {
  def activity = getActivity.asInstanceOf[MainActivity]
  val manager = IrcManager.init()
  lazy val adapter = ServersAdapter(getActivity)
  var serverMessagesFragmentShowing: Option[String] = None

  def server = selectedItem.map(manager.getServers)

  lazy val emptyView = new LinearLayout(getActivity)
  lazy val recycler = new RecyclerView(getActivity)
  lazy val layout = {
    l[FrameLayout](
      emptyView.!(
        w[TextView] >>= text(R.string.server_none) >>= lpK(MATCH_PARENT, WRAP_CONTENT, 0)(
          margins(all = getResources.getDimensionPixelSize(R.dimen.standard_margin))) >>= textGravity(Gravity.CENTER),
        w[Button] >>= id(R.id.add_server) >>= text(R.string.add_server) >>=
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
    case e: BusEvent.ServerAdded   => addListener(e.server)
    case e: BusEvent.ServerChanged => changeListener(e.server)
    case e: BusEvent.ServerRemoved => removeListener(e.server)
  }

  override def onCreate(bundle: Bundle) {
    super.onCreate(bundle)
    setHasOptionsMenu(true)
  }

  override def onResume() {
    super.onResume()
    HoneycombSupport.menuItemListener = Option(onServerMenuItemClicked)
  }

  override def onCreateView(inflater: LayoutInflater,
                            container: ViewGroup, bundle: Bundle) = {
    val v = layout.perform()
    v
  }

  var selectedItem = Option.empty[Int]

  def clearServerMessagesFragment(mgr: FragmentManager,
                                  tx: FragmentTransaction = null) {
    def withTx(txn: FragmentTransaction) = {
      for {
        name <- serverMessagesFragmentShowing
        f    <- mgr.findFragmentByTag(name).?
      } {
        txn.hide(f)
      }
      txn
    }
    tx.?.fold(withTx(mgr.beginTransaction()).commit(): Any)(withTx)
  }

  def changeListener(server: Server) {
    adapter.notifyItemChanged(manager.getServers.indexOf(server))
  }

  def removeListener(server: Server) {
    if (Config.servers.now.isEmpty) {
      emptyView.setVisibility(View.VISIBLE)
      recycler.setVisibility(View.GONE)
    }
    selectedItem = None
    adapter.notifyDataSetChanged()
  }

  def addListener(server: Server) {
    emptyView.setVisibility(View.GONE)
    recycler.setVisibility(View.VISIBLE)
    adapter.notifyItemInserted(manager.getServers.indexOf(server))
  }

  def onServerMenuItemClicked(item: MenuItem, server: Option[Server]):
  Boolean = {
    item.getItemId match {
      case R.id.server_delete =>
        server match {
          case Some(s) =>
            val builder = new AlertDialog.Builder(getActivity)
            val mgr = getActivity.getSupportFragmentManager
            clearServerMessagesFragment(mgr)
            builder.setTitle(R.string.server_confirm_delete)
            builder.setMessage(getActivity.getString(
              R.string.server_confirm_delete_message,
              s.name))
            builder.setPositiveButton(R.string.yes,
              () => {
                manager.deleteServer(s)
                ()
              })
            builder.setNegativeButton(R.string.no, null)
            builder.create().show()
          case None =>
            Toast.makeText(getActivity,
              R.string.server_not_selected, Toast.LENGTH_SHORT).show()
        }
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
        }(activity.adapter.addServer)
        true
      case _ => false
    }
  }

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
      (serverItem.! >>= text(server.name)).perform()
      (progressBar.! >>= condK(
        (server.state.now == Server.CONNECTING) ? visible
          | gone)).perform()

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

      toolbar.setOnMenuItemClickListener(new OnMenuItemClickListener {
        override def onMenuItemClick(menuItem: MenuItem) = {
          menuItem.getItemId match {
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
              clearServerMessagesFragment(mgr)
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
          }
        }
      })

      (IO(status) >>= imageResource(server.state.now match {
        case Server.INITIAL      => android.R.drawable.presence_offline
        case Server.DISCONNECTED => android.R.drawable.presence_busy
        case Server.CONNECTED    => android.R.drawable.presence_online
        case Server.CONNECTING   => android.R.drawable.presence_away
      }) >>= condK((server.state.now != Server.CONNECTING) ? visible | gone)).perform()

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
              p.setIndeterminate(true) } >>= gone,
            holder.status.! >>= lp(64 dp, 64 dp, Gravity.CENTER) >>=
              imageResource(android.R.drawable.presence_offline) >>=
              imageScale(ImageView.ScaleType.CENTER_INSIDE) >>= padding(left = 6 dp, right = 6 dp) >>=
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
        ) >>= kestrel(_.setGravity(Gravity.CENTER)) >>= lp(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP),
        holder.expandedInfo.!(
          holder.toolbar.! >>= lp(MATCH_PARENT, actionBarHeight) >>= kestrel { t =>
            t.setPopupTheme(resolveAttr(R.attr.qicrToolbarPopupTheme, _.resourceId))
          },
          w[View] >>= lp(MATCH_PARENT, 0, 1),
          holder.input.! >>=
            lpK(MATCH_PARENT, 48.dp)(margins(all = 8.dp)) >>=
            hint(R.string.input_placeholder) >>= inputTweaks >>= invisible >>=
            padding(left = 8 dp, right = 8 dp) >>=
            backgroundDrawable(inputBackground) >>= kestrel { e =>
            e.setOnEditorActionListener(activity.proc.onEditorActionListener _)
            e.setOnKeyListener(activity.proc.onKeyListener _)
            e.addTextChangedListener(activity.proc.TextListener)
          }
        ) >>= lp(MATCH_PARENT, 0) >>= vertical
      ) >>= lp(MATCH_PARENT, WRAP_CONTENT)).perform()
      holder
    }

  }
  def inputBackground = themeAttrs(R.styleable.AppTheme, _.getDrawable(R.styleable.AppTheme_inputBackground))
  private[this] var obss = List.empty[Obs]

  override def onPause() = {
    super.onPause()
    obss.foreach(_.kill())
    obss = Nil
  }
}
