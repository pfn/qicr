package com.hanhuy.android.irc

import android.app.Service
import android.content.Intent
import com.hanhuy.android.common.{Logcat, ServiceBus, EventBus}
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
          startForeground(Notifications.RUNNING_ID, Notifications.runningNotification(m.runningString, m.firstChannel, m.lastChannel))
        }
      }
  }


  override def onStartCommand(i: Intent, flags: Int, id: Int): Int =
    Service.START_NOT_STICKY

  override def onLowMemory() = {
    Logcat("LifecycleService").w("Low memory condition detected")
    super.onLowMemory()
  }

  override def onTrimMemory(level: Int) = {
    Logcat("LifecycleService").w("Trim memory requested, level: " + level)
    super.onTrimMemory(level)
  }
}
