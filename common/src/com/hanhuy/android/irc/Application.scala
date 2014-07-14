package com.hanhuy.android.irc

import org.acra.ACRA
import org.acra.annotation.ReportsCrashes

/**
 * @author pfnguyen
 */
@ReportsCrashes(formKey = "", formUri = "http://hanhuy-acra.appspot.com/api/crashreport")
class Application extends android.app.Application {
  override def onCreate() {
    super.onCreate()
    ACRA.init(this)
  }
}
