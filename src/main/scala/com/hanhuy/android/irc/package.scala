package com.hanhuy.android

/**
  * @author pfnguyen
  */
package object irc {
  implicit class AnyRefAsOptionExtension[T <: AnyRef](val any: T) extends AnyVal {
    def ? = Option(any)
  }
}
