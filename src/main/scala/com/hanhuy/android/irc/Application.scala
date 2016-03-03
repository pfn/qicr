package com.hanhuy.android.irc

import android.content.Context
import android.support.v7.widget.AppCompatDrawableManager
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

  lazy val drawableManager = AppCompatDrawableManager.get
  def getDrawable(c: Context, resid: Int) = drawableManager.getDrawable(c, resid)
}
