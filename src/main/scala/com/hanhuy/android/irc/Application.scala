package com.hanhuy.android.irc

import org.acra.ACRA
import org.acra.annotation.ReportsCrashes

/**
 * @author pfnguyen
 */
@ReportsCrashes(formUri = "http://hanhuy-acra.appspot.com/api/crashreport")
class Application extends android.app.Application {
  Application._instance = this

  override def onCreate() {
    super.onCreate()
    ACRA.init(this)

    LifecycleService.start()
  }
}

object Application {
  // this should absolutely always be non-null
  private var _instance: Application = _

  def context = _instance
}
