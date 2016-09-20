package com.hanhuy.android.irc

import com.hanhuy.android.common._
import java.text.SimpleDateFormat
import java.util.Date

import android.content.Context
import android.support.v4.graphics.drawable.DrawableCompat
import android.view.{ContextThemeWrapper, Gravity, View, ViewGroup}
import android.widget._
import com.hanhuy.android.common.UiBus
import com.hanhuy.android.irc.model.{BusEvent, RingBuffer}
import iota._

/**
  * @author pfnguyen
  */
object NotificationCenter extends TrayAdapter[NotificationMessage] {
  val NNil: List[NotificationMessage] = Nil
  private[this] val notifications = RingBuffer[NotificationMessage](64)

  def sortedNotifications = {
    val (is, ns) = notifications.foldRight((NNil, NNil)) {
      case (n, (important, normal)) =>
        if (n.isNew && n.important)
          (n :: important, normal)
        else
          (important, n :: normal)
    }
    ns ++ is
  }

  private[this] var sorted = sortedNotifications
  private[this] var newest = 0l

  override def itemId(position: Int) = sorted(position).hashCode

  override def size = sorted.size

  override def emptyItem = R.string.no_notifications

  type LP = RelativeLayout.LayoutParams

  import ViewGroup.LayoutParams._
  import RelativeLayout._

  case class NotificationHolder(icon: ImageView,
                                arrow: ImageView,
                                channelServer: TextView,
                                timestamp: TextView,
                                text: TextView)

  def notificationLayout(implicit c: Context) = {
    val holder = NotificationHolder(
      new ImageView(c),
      new ImageView(c),
      new TextView(c),
      new TextView(c),
      new TextView(c))
    l[RelativeLayout](
      holder.icon.! >>= id(Id.notif_icon) >>=
        k.scaleType(ImageView.ScaleType.CENTER_INSIDE) >>=
        lpK(24.dp, 24.dp) { p: LP =>
          p.addRule(ALIGN_PARENT_LEFT, 1)
          p.addRule(BELOW, Id.channel_server)
          margins(all = 8.dp)(p)
        },
      holder.arrow.! >>= id(Id.notif_arrow) >>=
        k.imageResource(R.drawable.ic_navigate_next_white_24dp) >>=
        k.scaleType(ImageView.ScaleType.CENTER_INSIDE) >>=
        lpK(24.dp, 24.dp) { p: LP =>
          p.addRule(ALIGN_PARENT_RIGHT, 1)
          p.addRule(BELOW, Id.timestamp)
          margins(all = 8.dp)(p)
        } >>= k.visibility(View.GONE) >>= kestrel { iv =>
        DrawableCompat.setTint(iv.getDrawable.mutate(), resolveAttr(R.attr.qicrNotificationIconTint, _.data))
      },
      holder.channelServer.! >>= id(Id.channel_server) >>=
        k.text("#channel / server") >>= lpK(WRAP_CONTENT, WRAP_CONTENT) { p: LP =>
        p.addRule(ALIGN_PARENT_LEFT, 1)
        p.addRule(RIGHT_OF, Id.notif_icon)
        p.addRule(ALIGN_PARENT_TOP, 1)
        margins(left = 8.dp)(p)
      },
      holder.timestamp.! >>= id(Id.timestamp) >>= k.text("9:09pm") >>= lpK(WRAP_CONTENT, WRAP_CONTENT) { p: LP =>
        p.addRule(ALIGN_PARENT_RIGHT, 1)
        p.addRule(ALIGN_PARENT_TOP, 1)
        margins(right = 8.dp)(p)
      },
      holder.text.! >>= id(Id.text) >>= lpK(WRAP_CONTENT, WRAP_CONTENT) { p: LP =>
        p.addRule(ALIGN_TOP, Id.notif_icon)
        p.alignWithParent = true
        p.addRule(RIGHT_OF, Id.notif_icon)
        p.addRule(LEFT_OF, Id.notif_arrow)
      } >>= k.gravity(Gravity.CENTER_VERTICAL)
    ) >>= padding(top = 12.dp, bottom = 12.dp, right = 8.dp, left = 8.dp) >>= kestrel(_.setTag(Id.holder, holder))
  }

  override def onGetView(position: Int, convertView: View, parent: ViewGroup) = {
    implicit val ctx = parent.getContext
    val view = convertView match {
      case vg: ViewGroup => vg
      case _ => notificationLayout.perform()
    }
    val holder = view.getTag(Id.holder).asInstanceOf[NotificationHolder]
    getItem(position) foreach { n =>
      holder.text.setText(n.message)
      holder.icon.setImageResource(n.icon)
      val sdf = new SimpleDateFormat("h:mma")
      holder.timestamp.setText(sdf.format(n.ts).toLowerCase)
      if (n.isNew && n.important) {
        DrawableCompat.setTint(DrawableCompat.wrap(holder.icon.getDrawable.mutate()), 0xff26a69a)
      } else {
        DrawableCompat.setTint(DrawableCompat.wrap(holder.icon.getDrawable.mutate()), resolveAttr(R.attr.qicrNotificationIconTint, _.data))
      }
      n match {
        case NotifyNotification(ts, server, sender, msg) =>
          holder.channelServer.setText(server)
          holder.arrow.setVisibility(View.GONE)
        case UserMessageNotification(ts, server, sender, msg) =>
          holder.arrow.setVisibility(View.VISIBLE)
          holder.channelServer.setText(server)
        case ChannelMessageNotification(ts, server, chan, sender, msg) =>
          holder.arrow.setVisibility(View.VISIBLE)
          holder.channelServer.setText(s"$chan / $server")
        case ServerNotification(ic, server, msg) =>
          holder.arrow.setVisibility(View.GONE)
          holder.channelServer.setText(server)
      }
    }
    view
  }

  override def getItem(position: Int): Option[NotificationMessage] =
    if (size == 0) None else sorted(position).?

  def hasImportantNotifications = sorted.exists(n => n.isNew && n.important)

  def +=(msg: NotificationMessage) = {
    val msgtime = msg.ts.getTime
    if (msgtime > newest) {
      UiBus.run {
        notifications += msg
        if (msg.important) {
          UiBus.send(BusEvent.NewNotification)
        }
        notifyDataSetChanged()
      }
    }

    newest = math.max(newest, msg match {
      case _: ChannelMessageNotification[_] =>
        msgtime
      case _: UserMessageNotification[_] =>
        msgtime
      case _ => 0
    })
  }

  def markAllRead(): Unit = UiBus.run {
    notifications foreach { _.markRead() }
    notifyDataSetChanged()
  }

  def markRead(channel: String, server: String): Unit = {
    notifications collect {
      case n@ChannelMessageNotification(_,s,c,_,_) =>
        if (server == s && channel == c)
          n.markRead()
      case n@UserMessageNotification(_,s,c,_) =>
        if (server == s && channel == c)
          n.markRead()
    }
    UiBus.run(notifyDataSetChanged())
    if (!hasImportantNotifications)
      UiBus.send(BusEvent.ReadNotification)
  }

  override def notifyDataSetChanged() = {
    sorted = sortedNotifications
    super.notifyDataSetChanged()
  }
}

sealed trait NotificationMessage {
  private[this] var newmessage = true
  def message: CharSequence
  def icon: Int
  val ts: Date
  def isNew = newmessage
  def important: Boolean
  def markRead() = newmessage = false
}

case class ServerNotification(icon: Int,
                              server: String,
                              message: CharSequence)
  extends NotificationMessage {
  val important = false
  val ts = new Date
}

case class NotifyNotification(ts: Date,
                              server: String,
                              sender: String,
                              message: CharSequence) extends NotificationMessage {
  val important = false
  val icon = R.drawable.ic_info_outline_black_24dp
}

case class ChannelMessageNotification[A](ts: Date,
                                         server: String,
                                         channel: String,
                                         nick: String,
                                         message: CharSequence)
extends NotificationMessage {
  def action(adapter: MainPagerAdapter) = adapter.selectTab(channel, server)
  val important = true
  val icon = R.drawable.ic_message_black_24dp
}

case class UserMessageNotification[A](ts: Date,
                                      server: String,
                                      nick: String,
                                      message: CharSequence)
  extends NotificationMessage {
  def action(adapter: MainPagerAdapter) = adapter.selectTab(nick, server)
  val icon = R.drawable.ic_chat_black_24dp
  val important = true
}

abstract class TrayAdapter[A] extends BaseAdapter {
  import iota._
  import ViewGroup.LayoutParams._
  final override def getView(position: Int, convertView: View, parent: ViewGroup) = {
    if (size == 0) {
      implicit val context = parent.getContext
      convertView.?.getOrElse {
        // do nothing with onClick so that onItemClick doesn't execute
        c[AbsListView](w[TextView] >>= k.text(emptyItem) >>= k.gravity(Gravity.CENTER) >>=
          hook0.onClick(IO(())) >>= lp(MATCH_PARENT, 128.dp)).perform()
      }
    } else {
      onGetView(position, convertView, parent)
    }
  }

  final override def getItemViewType(position: Int) = if (size == 0 && position == 0) 1 else 0
  final override def getViewTypeCount = 2
  final override def getCount = math.max(1, size)
  final override def getItemId(position: Int) = if (size == 0) -1 else itemId(position)
  final override def hasStableIds = false

  def itemId(position: Int): Int
  def onGetView(position: Int, convertView: View, parent: ViewGroup): View
  def size: Int
  def emptyItem: Int
}

object Notifications {
  import android.app.Notification
  import android.app.NotificationManager
  import android.app.PendingIntent
  import android.app.RemoteInput
  import android.content.Intent
  import android.support.v4.app.NotificationCompat
  import android.os.Build
  import android.text.TextUtils
  import android.text.style.TextAppearanceSpan
  import android.text.TextPaint
  import android.graphics.Typeface
  import android.util.DisplayMetrics
  import android.view.WindowManager
  import android.text.StaticLayout
  import android.text.Layout
  import android.net.Uri
  import model._

  sealed trait NotificationType
  case class ServerDisconnected(server: Server) extends NotificationType
  case class ChannelMention(c: ChannelLike, msg: MessageLike, ts: Long = System.currentTimeMillis) extends NotificationType
  case class PrivateMessage(query: Query, msg: MessageLike, ts: Long = System.currentTimeMillis) extends NotificationType
  trait Summary {
    val id: Int
  }
  case object ServerDisconnectedSummary extends NotificationType with Summary {
    val id = 2
  }
  case object ChannelMentionSummary extends NotificationType with Summary {
    val id = 3
  }
  case object PrivateMessageSummary extends NotificationType with Summary {
    val id = 4
  }
  val themed = new ContextThemeWrapper(Application.context, R.style.AppTheme_Light)
  val nm = themed.systemService[NotificationManager]
  val RUNNING_ID = IrcManager.RUNNING_ID
  val EXTRA_SUBJECT = IrcManager.EXTRA_SUBJECT
  val EXTRA_MESSAGE = IrcManager.EXTRA_MESSAGE
  val ACTION_NEXT_CHANNEL = IrcManager.ACTION_NEXT_CHANNEL
  val ACTION_PREV_CHANNEL = IrcManager.ACTION_PREV_CHANNEL
  val ACTION_QUICK_SEND = IrcManager.ACTION_QUICK_CHAT
  val ACTION_CANCEL_MENTION = IrcManager.ACTION_CANCEL_MENTION
  val DISCONNECT_NOTIFICATIONS = "qicr.group.disconnected"
  val MENTION_NOTIFICATIONS = "qicr.group.mention"
  val PRIVMSG_NOTIFICATIONS = "qicr.group.privmsg"
  val STARTING_NOTIFICATION_ID = RUNNING_ID + 20
  var currentNotification = STARTING_NOTIFICATION_ID
  def nextNotificationId() = {
    val n = math.max(currentNotification, STARTING_NOTIFICATION_ID)
    currentNotification = n + 1
    n
  }

  /** generate a pending intent ID */
  def pid(id: Int, id2: Int) = (android.os.Process.myPid() + id) * 100 + id2

  var currentNotifications = Map.empty[NotificationType,Int]

  def runningNotification(text: CharSequence, first: Option[ChannelLike], lastChannel: Option[ChannelLike]): Notification = {
    import iota.{c => _,_}
    val intent = new Intent(themed, classOf[MainActivity])

    lastChannel orElse first foreach { c =>
      intent.putExtra(EXTRA_SUBJECT, Widgets.toString(c))
    }
    val pending = PendingIntent.getActivity(themed, pid(RUNNING_ID, 0),
      intent, PendingIntent.FLAG_UPDATE_CURRENT)

    val builder = new NotificationCompat.Builder(themed)
      .setSmallIcon(R.drawable.ic_notify_mono)
      .setColor(resolveAttr(R.attr.colorPrimary, _.data)(themed))
      .setWhen(System.currentTimeMillis())
      .setContentIntent(pending)
      .setContentText(text)
      .setContentTitle(themed.getString(R.string.notif_title))

    lastChannel orElse first map { c =>
      val MAX_LINES = if (v(24)) 6 else 9

      val chatIntent = new Intent(themed, classOf[WidgetChatActivity])
      chatIntent.putExtra(IrcManager.EXTRA_SUBJECT, Widgets.toString(c))
      chatIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
        Intent.FLAG_ACTIVITY_MULTIPLE_TASK |
        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)

      val n = builder
        .setContentIntent(PendingIntent.getActivity(themed,
          pid(RUNNING_ID, 1),
          if (Build.VERSION.SDK_INT < 11) intent
          else if (v(24)) intent
          else chatIntent,
          PendingIntent.FLAG_UPDATE_CURRENT)).build

      if (Build.VERSION.SDK_INT >= 16 && Settings.get(Settings.RUNNING_NOTIFICATION)) {
        val title = c.name
        val msgs = if (c.messages.filteredMessages.nonEmpty) {
          TextUtils.concat(
            c.messages.filteredMessages.takeRight(MAX_LINES).map { m =>
              MessageAdapter.formatText(themed, m)(c)
            }.flatMap (m => Seq(m, "\n")).init:_*)
        } else {
          themed.getString(R.string.no_messages)
        }

        // TODO account for height of content text view (enable font-sizes)
        val context = themed
        val tas = new TextAppearanceSpan(
          themed, android.R.style.TextAppearance_Small)
        val paint = new TextPaint
        paint.setTypeface(Typeface.create(tas.getFamily, tas.getTextStyle))
        paint.setTextSize(tas.getTextSize)
        val d = context.getResources.getDimension(
          R.dimen.notification_panel_width)
        val metrics = new DisplayMetrics
        context.systemService[WindowManager].getDefaultDisplay.getMetrics(metrics)
        // pre-24 has 8dp margins on each side, 16 total
        // 24+ has 16dp margins on each side from system ui
        val margin = if (v(24)) 32.dp(context) else 16.dp(context)
        // api21 has non-maxwidth notification panels on phones
        val width = math.min(metrics.widthPixels,
          if (d < 0) metrics.widthPixels else d.toInt) - margin

        val layout = new StaticLayout(msgs, paint, width.toInt,
          Layout.Alignment.ALIGN_NORMAL, 1.0f, 0.0f, true)
        val lines = layout.getLineCount
        val startOffset = if (lines > MAX_LINES) {
          layout.getLineStart(lines - MAX_LINES)
        } else 0
        n.priority = Notification.PRIORITY_HIGH
        val view = new RemoteViews(
          themed.getPackageName, R.layout.notification_content)
        view.setTextViewText(R.id.title, title)
        view.setTextViewText(R.id.content, msgs.subSequence(
          startOffset, msgs.length))
        view.setOnClickPendingIntent(R.id.go_prev,
          PendingIntent.getBroadcast(themed,
            pid(RUNNING_ID, 2),
            new Intent(ACTION_PREV_CHANNEL),
            PendingIntent.FLAG_UPDATE_CURRENT))
        if (!v(24))
          view.setOnClickPendingIntent(R.id.widget_input, pending)
        view.setOnClickPendingIntent(R.id.go_next,
          PendingIntent.getBroadcast(themed,
            pid(RUNNING_ID, 3),
            new Intent(ACTION_NEXT_CHANNEL),
            PendingIntent.FLAG_UPDATE_CURRENT))

        n.bigContentView = view

        if (Build.VERSION.SDK_INT >= 24 && Settings.get(Settings.RUNNING_NOTIFICATION)) {
          import android.graphics.drawable.Icon
          val input = new RemoteInput.Builder(EXTRA_MESSAGE)
            .setLabel(themed.getString(R.string.send_message))
            .build()
          val sendIntent = PendingIntent.getBroadcast(themed,
            pid(RUNNING_ID, 4),
            new Intent(ACTION_QUICK_SEND),
            PendingIntent.FLAG_UPDATE_CURRENT)
          val resId = iota.resolveAttr(R.attr.qicrSendIcon, _.resourceId)(themed)
          val icon = Icon.createWithResource(themed, resId)
          val send = new Notification.Action.Builder(
            icon, themed.getString(R.string.send_message), sendIntent)
            .addRemoteInput(input)
            .build()
          val bldr = Notification.Builder.recoverBuilder(themed, n)
          bldr
            .setStyle(new Notification.DecoratedCustomViewStyle)
            .setContentTitle(null)
            .setCustomBigContentView(view)
            .addAction(send)
            .build()
        } else n
      } else n
    } getOrElse builder.build
  }

  def cancel(n: NotificationType): Unit = {
    currentNotifications.get(n).foreach(nm.cancel)
    currentNotifications -= n
    n match {
      case ChannelMention(_,_,_) => summarize(ChannelMentionSummary)
      case PrivateMessage(_,_,_) => summarize(PrivateMessageSummary)
      case ServerDisconnected(_) => summarize(ServerDisconnectedSummary)
      case _ =>
    }
  }
  def cancelAll(): Unit = {
    nm.cancelAll()
  }

  def running(text: CharSequence, first: Option[ChannelLike], lastChannel: Option[ChannelLike]) = {
    val n = runningNotification(text, first, lastChannel)
    nm.notify(RUNNING_ID, n)
  }

  def mention(c: ChannelLike, m: MessageLike): Unit = {
    summarize(ChannelMentionSummary)
    showNotification(ChannelMention(c, m), R.drawable.ic_notify_mono_star,
      themed.getString(R.string.notif_mention_template, c.name, m.toString), ChannelMentionSummary, Some(c))
  }
  def pm(query: Query, m: MessageLike) {
    summarize(PrivateMessageSummary)
    showNotification(PrivateMessage(query, m), R.drawable.ic_notify_mono_star,
      m.toString, PrivateMessageSummary, Some(query))
  }

  def markAllRead(): Unit = {

  }

  def markRead(c: ChannelLike): Unit = {
    currentNotifications.foreach {
      case (a@ChannelMention(c2, _, _), id) if c == c2 => cancel(a)
      case (a@PrivateMessage(q, _, _), id)  if c == q  => cancel(a)
      case _ =>
    }
  }

  def connected(server: Server): Unit = {
    cancel(ServerDisconnected(server))
  }

  def disconnected(server: Server): Unit = {
    summarize(ServerDisconnectedSummary)
    showNotification(ServerDisconnected(server), R.drawable.ic_notify_mono_bang,
      themed.getString(R.string.notif_server_disconnected, server.name), ServerDisconnectedSummary)
  }

  def summarize(tpe: NotificationType with Summary): Unit = {
    if (v(21)) {
      val (summTpe, icon, m, c, ms) = tpe match {
        case PrivateMessageSummary =>
          val cnt = currentNotifications.keys.collect {
            case p@PrivateMessage(_, m, _) => m
          }
          (PrivateMessageSummary, R.drawable.ic_notify_mono_star, themed.getString(R.string.notif_unread_private_messages, cnt.size.asInstanceOf[Integer]), cnt.size, cnt.toList)
        case ChannelMentionSummary =>
          val cnt = currentNotifications.keys.collect {
            case c@ChannelMention(_, m, _) => m
          }
          (ChannelMentionSummary, R.drawable.ic_notify_mono_star, themed.getString(R.string.notif_unread_messages, cnt.size.asInstanceOf[Integer]), cnt.size, cnt.toList)
        case ServerDisconnectedSummary =>
          val cnt = currentNotifications.keys.collect {
            case s@ServerDisconnected(_) => s
          }.size
          (ServerDisconnectedSummary, R.drawable.ic_notify_mono_bang, themed.getString(R.string.notif_disconnected_servers, cnt.asInstanceOf[Integer]), cnt.size, Nil)
      }
      if (c > 0) {
        showSummary(summTpe, icon, m, summTpe.toString, ms)
        currentNotifications += summTpe -> summTpe.id
      } else {
        cancel(summTpe)
      }
    }
  }

  def showSummary(tpe: NotificationType with Summary, icon: Int, text: String, group: String, ms: List[MessageLike]): Unit = {
    val style = new NotificationCompat.InboxStyle().setBigContentTitle(text)
    ms.foreach(m => style.addLine(m.toString))
    val id = tpe.id
    val summaryNotification = new NotificationCompat.Builder(themed)
      .setColor(resolveAttr(R.attr.colorPrimary, _.data)(themed))
      .setPriority(Notification.PRIORITY_HIGH)
      .setCategory(Notification.CATEGORY_MESSAGE)
      .setContentTitle(text)
      .setSmallIcon(icon)
      .setStyle(style)
      .setGroup(tpe.toString)
      .setGroupSummary(true)
      .build()
    nm.notify(id, summaryNotification)
  }

  private def showNotification(tpe: NotificationType, icon: Int, text: String,
                               group: NotificationType,
                               channel: Option[ChannelLike] = None): Int = {
    val id = nextNotificationId()
    val intent = new Intent(themed, classOf[MainActivity])
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    channel foreach { c =>
      intent.putExtra(EXTRA_SUBJECT, Widgets.toString(c))
    }

    val pending = PendingIntent.getActivity(themed, pid(id, 1), intent,
      PendingIntent.FLAG_UPDATE_CURRENT)
    val builder = new NotificationCompat.Builder(themed)
      .setColor(resolveAttr(R.attr.colorPrimary, _.data)(themed))
      .setSmallIcon(icon)
      .setWhen(System.currentTimeMillis())
      .setContentIntent(pending)
      .setGroup(group.toString)
      .setContentText(text)

    if (!v(21))
      builder.setContentTitle(themed.getString(R.string.notif_title))

    val notif = if (channel.isDefined) {
      builder.setPriority(Notification.PRIORITY_HIGH)
        .setCategory(Notification.CATEGORY_MESSAGE)
        .setSound(Uri.parse(Settings.get(Settings.NOTIFICATION_SOUND)))
        .setVibrate(if (Settings.get(Settings.NOTIFICATION_VIBRATE))
          Array(0l, 100l, 100l, 100l) else Array(0l)) // required to make heads-up show on lollipop
        .setStyle(new NotificationCompat.BigTextStyle()
        .bigText(text))
        .build
    } else builder.build

    notif.flags |= Notification.FLAG_AUTO_CANCEL
    channel foreach { c =>
      val cancel = new Intent(ACTION_CANCEL_MENTION)
      cancel.putExtra(EXTRA_SUBJECT, Widgets.toString(c))
      notif.deleteIntent = PendingIntent.getBroadcast(themed,
        pid(id, 2), cancel,
        PendingIntent.FLAG_UPDATE_CURRENT)
    }
    if (Build.VERSION.SDK_INT >= 21)
      notif.headsUpContentView = notif.bigContentView
    nm.notify(id, notif)
    currentNotifications += tpe -> id
    id
  }

  def exit(): Unit = {
    cancelAll()
  }
}
