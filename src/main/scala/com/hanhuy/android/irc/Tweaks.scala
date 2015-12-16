package com.hanhuy.android.irc

import android.app.Activity
import android.content.Context
import android.content.res.TypedArray
import android.os.Build
import android.text.InputType
import android.util.TypedValue
import android.view.View.MeasureSpec
import android.view.inputmethod.EditorInfo
import android.view._
import android.widget._
import android.support.v7.widget.Toolbar

import iota._

/**
 * @author pfnguyen
 */
object Tweaks {
  import InputType._
  def textPassword[A <: TextView]: Kestrel[A] = inputType(TYPE_TEXT_VARIATION_PASSWORD | TYPE_CLASS_TEXT)
  def number[A <: TextView]: Kestrel[A] = inputType(TYPE_CLASS_NUMBER)
  def textUri[A <: TextView]: Kestrel[A] = inputType(TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_URI)
  def textCapWords[A <: TextView]: Kestrel[A] = inputType(TYPE_CLASS_TEXT | TYPE_TEXT_FLAG_CAP_WORDS)
  lazy val hasSystemNav = {
    val res = Application.context.getResources
    val id = res.getIdentifier("config_showNavigationBar", "bool", "android")
    Build.HARDWARE == "goldfish" || (if (id != 0) res.getBoolean(id) else false)
  }
  def navBarWidth(implicit ctx: Context) = {
    val id = ctx.getResources.getIdentifier(
      "navigation_bar_width", "dimen", "android")
    if (id != 0 && hasSystemNav)
      ctx.getResources.getDimensionPixelSize(id) else 0
  }
  def navBarHeight(implicit ctx: Context) = {
    val id = ctx.getResources.getIdentifier(
      "navigation_bar_height", "dimen", "android")
    if (id != 0 && hasSystemNav)
      ctx.getResources.getDimensionPixelSize(id) else 0
  }
  def phone(implicit c: Context) = !sw(600 dp)
  def tablet(implicit c: Context) = sw(600 dp)
  def statusBarHeight(implicit ctx: Context) = {
    val id = ctx.getResources.getIdentifier(
      "status_bar_height", "dimen", "android")
    if (id != 0) ctx.getResources.getDimensionPixelSize(id) else 0
  }
  def actionBarHeight(implicit ctx: Context) = resolveAttr(R.attr.actionBarSize,
    tv => TypedValue.complexToDimensionPixelSize(tv.data, ctx.getResources.getDisplayMetrics))

  def kitkatPadding[V <: View](implicit ctx: Context): Kestrel[V] =
    condK(v(19) ? padding(
      top    = statusBarHeight + actionBarHeight,
      bottom = if (tablet | portrait) navBarHeight else 0,
      right  = if (phone & landscape) navBarWidth else 0) | padding(top = actionBarHeight))
  def kitkatPadding[V <: View](padTop: Boolean)(implicit ctx: Context): Kestrel[V] =
    condK(v(19) ? padding(
      top    = if (padTop) statusBarHeight + actionBarHeight else 0,
      bottom = if (tablet || portrait) navBarHeight else 0,
      right  = if (phone && landscape) navBarWidth else 0) | padding(top = if (padTop) actionBarHeight else 0))

  def kitkatPaddingBottom[V <: View](implicit ctx: Context): Kestrel[V] =
    padding(bottom = kitkatBottomPadding)

  def kitkatStatusTopPadding(implicit c: Context) =
    if (v(19)) statusBarHeight else 0

  def kitkatBottomPadding(implicit c: Context) =
    if ((tablet || portrait) && v(19)) navBarHeight else 0

  def kitkatStatusMargin[V <: View](implicit c: Context) =
    margins(top = if (v(19)) statusBarHeight else 0)
  def kitkatStatusPadding[V <: View](implicit c: Context): Kestrel[V] =
    condK(v(19) ? padding(top = statusBarHeight))
  def kitkatPaddingTop[V <: View](implicit c: Context): Kestrel[V] =
    padding(top = actionBarHeight + (if (v(19)) statusBarHeight else 0))

  def kitkatInputMargin[A <: View](implicit ctx: Context): Kestrel[A] = kestrel { a =>
    val lp = a.getLayoutParams.asInstanceOf[ViewGroup.MarginLayoutParams]
    if(v(19)) margins(
      bottom = if (tablet || portrait) navBarHeight else 0,
      right = if (phone && landscape) navBarWidth else 0)(lp)
  }

  val horizontal: Kestrel[LinearLayout] =
    kestrel { l => l.setOrientation(LinearLayout.HORIZONTAL) }
  val vertical: Kestrel[LinearLayout] =
    kestrel { l => l.setOrientation(LinearLayout.VERTICAL) }
  def buttonTweaks(implicit cx: Context): Kestrel[ImageButton] = c[LinearLayout](kestrel { b: ImageButton =>
    b.setFocusable(false)
    b.setFocusableInTouchMode(false)
    b.setScaleType(ImageView.ScaleType.CENTER_INSIDE)
  } >=> lp(48 dp, 48 dp))

  def inputTweaks[V <: EditText]: Kestrel[V] = kestrel { e =>
    import InputType._
    import EditorInfo._
    e.setInputType(TYPE_CLASS_TEXT | TYPE_TEXT_FLAG_AUTO_CORRECT)
    e.setImeOptions(IME_ACTION_SEND | IME_FLAG_NO_FULLSCREEN)
  }
  def newToolbar(daynight: Boolean)(implicit ctx: Context) = {
    c[ViewGroup](IO(new Toolbar(new ContextThemeWrapper(
      ctx, R.style.ThemeOverlay_AppCompat_ActionBar))) >>= id(Id.toolbar) >>=
      kestrel { t =>
        t.setPopupTheme(if (daynight) R.style.ThemeOverlay_AppCompat_Light else
          R.style.ThemeOverlay_AppCompat_Dark)
        t.setBackgroundColor(resolveAttr(R.attr.colorPrimary, _.data))
      }
      // setting lpK here doesn't carry margin, why??
      // >>= lpK(MATCH_PARENT, WRAP_CONTENT)(kitkatStatusMargin)
    )
  }
  def checkbox(implicit ctx: Context) = if (Build.VERSION.SDK_INT >= 21)
    new CheckBox(ctx) else new android.support.v7.widget.AppCompatCheckBox(ctx)
  def checkedText(implicit ctx: Context): CheckedTextView =
    if (Build.VERSION.SDK_INT >= 21)
      new CheckedTextView(ctx)
    else
      new android.support.v7.widget.AppCompatCheckedTextView(ctx)

  def resolveAttr[A](attr: Int, f: TypedValue => A)(implicit ctx: Context) = {
    val tv = new TypedValue
    val r = ctx.getTheme.resolveAttribute(attr, tv, true)
    if (r) {
      f(tv)
    } else throw new IllegalStateException("attribute not found: " + attr)
  }
  def themeAttrs[A](theme: Array[Int], f: TypedArray => A)(implicit activity: Activity): A = {
    val themeAttrs = activity.getTheme.obtainStyledAttributes(R.styleable.AppTheme)
    val c = f(themeAttrs)
    themeAttrs.recycle()
    c
  }
}
class SquareImageButton(c: Context) extends ImageButton(c) {
  override def onMeasure(mw: Int, mh: Int) = {
    val w = MeasureSpec.getSize(mw)
    val h = MeasureSpec.getSize(mh)
    val m = if (w > h) mw else mh
    super.onMeasure(m, m)
  }
}
