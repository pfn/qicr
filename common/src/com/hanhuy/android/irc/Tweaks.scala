package com.hanhuy.android.irc

import android.graphics.Color
import android.view.{View, ViewGroup}
import android.widget.{TextView, ImageView, LinearLayout}

import macroid._
import macroid.FullDsl._

/**
 * @author pfnguyen
 */
object Tweaks {
  import ViewGroup.LayoutParams._

  lazy val llMatchParent = lp[LinearLayout](MATCH_PARENT, MATCH_PARENT, 0)
  lazy val llMatchWidth = lp[LinearLayout](MATCH_PARENT, WRAP_CONTENT, 0)

  def image(resid: Int) = Tweak[ImageView](_.setImageResource(resid))
  def bg(color: Int) = Tweak[View](_.setBackgroundColor(color))
  def bg(color: String) = Tweak[View](
    _.setBackgroundColor(Color.parseColor(color)))

  def inputType(types: Int) = Tweak[TextView](_.setInputType(types))
  def hint(s: CharSequence) = Tweak[TextView](_.setHint(s))
  def hint(resid: Int) = Tweak[TextView](_.setHint(resid))

  def tweak[A <: View,B](f: A => B) = Tweak[A](a => f(a))

}

