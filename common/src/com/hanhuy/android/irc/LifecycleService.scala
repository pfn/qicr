package com.hanhuy.android.irc

import android.app.Service
import android.content.Intent
import com.hanhuy.android.common.{ServiceBus, EventBus}
import com.hanhuy.android.irc.model.BusEvent.{ExitApplication, MainActivityStop, MainActivityStart}

/**
 * @author pfnguyen
 */
object LifecycleService {
  def start() {
    Application.context.startService(
      new Intent(Application.context, classOf[LifecycleService]))
  }
}
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
