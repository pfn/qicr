package com.hanhuy.android.irc

import android.app.{Notification, PendingIntent, Service}
import android.content.Intent
import android.support.v4.app.NotificationCompat
import android.text.TextUtils
import com.hanhuy.android.common.{ServiceBus, EventBus}
import com.hanhuy.android.irc.model.BusEvent.{ExitApplication, MainActivityStop, MainActivityStart}
import com.hanhuy.android.irc.model.MessageAdapter

/**
 * @author pfnguyen
 */
class LifecycleService extends Service with EventBus.RefOwner {
  import IrcManager._
  override def onBind(intent: Intent) = null

  ServiceBus += {
    case ExitApplication =>
      stopForeground(true)
      stopSelf()
    case MainActivityStart =>
      stopForeground(true)
    case MainActivityStop =>
      IrcManager.instance foreach { m =>
        if (m.running) {
          startForeground(RUNNING_ID, m.runningNotification(m.runningString))
        }
      }
  }


  override def onStartCommand(i: Intent, flags: Int, id: Int): Int =
    Service.START_NOT_STICKY
}
