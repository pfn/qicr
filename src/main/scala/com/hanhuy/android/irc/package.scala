package com.hanhuy.android

import android.app.Activity
import android.view.inputmethod.InputMethodManager

/**
  * @author pfnguyen
  */
package object irc {
  implicit class AnyRefAsOptionExtension[T <: AnyRef](val any: T) extends AnyVal {
    def ? = Option(any)
  }

  import iota.std.Contexts._
  def hideIME()(implicit c: Activity): Unit = {
    systemService[InputMethodManager].hideSoftInputFromWindow(
      c.getWindow.getDecorView.getWindowToken, 0)
  }
}
