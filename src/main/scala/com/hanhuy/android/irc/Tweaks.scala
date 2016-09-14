package com.hanhuy.android.irc

import android.annotation.TargetApi
import android.app.Activity
import android.content.Context
import android.os.Build
import android.text.InputType
import android.util.TypedValue
import android.view.View.MeasureSpec
import android.view.inputmethod.EditorInfo
import android.view._
import android.widget._
import android.support.v7.widget.{CardView, Toolbar}
import iota._

/**
 * @author pfnguyen
 */
object Tweaks {
  import InputType._
  def textPassword[A <: TextView]: Kestrel[A] = k.inputType(TYPE_TEXT_VARIATION_PASSWORD | TYPE_CLASS_TEXT)
  def number[A <: TextView]: Kestrel[A] = k.inputType(TYPE_CLASS_NUMBER)
  def textUri[A <: TextView]: Kestrel[A] = k.inputType(TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_URI)
  def textCapWords[A <: TextView]: Kestrel[A] = k.inputType(TYPE_CLASS_TEXT | TYPE_TEXT_FLAG_CAP_WORDS)
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

  def kitkatPadding[V <: View](implicit ctx: Activity): Kestrel[V] =
    condK(v(19) ? padding[V](
      top    = statusBarHeight + actionBarHeight,
      bottom = if (tablet || portrait) navBarHeight else 0,
      right  = if (phone && landscape && !ctx.isMultiWindow) navBarWidth else 0) | padding(top = actionBarHeight))
  def kitkatPadding[V <: View](padTop: Boolean)(implicit ctx: Activity): Kestrel[V] =
    condK(v(19) ? padding[V](
      top    = if (padTop) statusBarHeight + actionBarHeight else 0,
      bottom = if ((tablet || portrait) && !ctx.isMultiWindow) navBarHeight else 0,
      right  = if (phone && landscape && !ctx.isMultiWindow) navBarWidth else 0) | padding(top = if (padTop) actionBarHeight else 0))
  def kitkatPaddingRight[V <: View](implicit ctx: Activity): Kestrel[V] =
    condK(v(19) ? padding(right  = if (phone && landscape && !ctx.isMultiWindow) navBarWidth else 0))

  def kitkatPaddingBottom[V <: View](implicit ctx: Activity): Kestrel[V] =
    padding(bottom = kitkatBottomPadding)

  def kitkatStatusTopPadding(implicit c: Context) =
    if (v(19)) statusBarHeight else 0

  def kitkatBottomPadding(implicit c: Activity) =
    if ((tablet || portrait) && v(19) && !c.isMultiWindow) navBarHeight else 0

  def kitkatStatusMargin[V <: View](implicit c: Context) =
    margins(top = if (v(19)) statusBarHeight else 0)
  def kitkatStatusPadding[V <: View](implicit c: Context): Kestrel[V] =
    condK(v(19) ? padding(top = statusBarHeight))
  def kitkatPaddingTop[V <: View](implicit c: Context): Kestrel[V] =
    padding(top = actionBarHeight + (if (v(19)) statusBarHeight else 0))

  def kitkatInputMargin[A <: View](implicit ctx: Activity): Kestrel[A] = kestrel { a =>
    val lp = a.getLayoutParams.asInstanceOf[ViewGroup.MarginLayoutParams]
    if(v(19)) margins(
      bottom = if ((tablet || portrait) && !ctx.isMultiWindow) navBarHeight else 0,
      right = if (phone && landscape && !ctx.isMultiWindow) navBarWidth else 0)(lp)
  }

  val horizontal: Kestrel[LinearLayout] =
    kestrel { l => l.setOrientation(LinearLayout.HORIZONTAL) }
  val vertical: Kestrel[LinearLayout] =
    kestrel { l => l.setOrientation(LinearLayout.VERTICAL) }

  def visGone[V <: View](b: Boolean): Kestrel[V] = k.visibility(if (b) View.VISIBLE else View.GONE)
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
  def cardView(implicit ctx: Context) = {
    IO(new CardView(ctx, null, R.attr.qicrCardStyle))
  }
  def newToolbar(implicit ctx: Activity) = {
    (IO(new Toolbar(new ContextThemeWrapper(
      ctx, R.style.ThemeOverlay_AppCompat_ActionBar))) >>=
      kestrel { t =>
        t.setPopupTheme(resolveAttr(R.attr.qicrToolbarPopupTheme, _.resourceId))
        t.setBackgroundColor(resolveAttr(R.attr.colorPrimary, _.data))
      } >>= kitkatPaddingRight[Toolbar]).perform()
//    https://code.google.com/p/android/issues/detail?id=196729
      // setting lpK here doesn't carry margin, why??
      // >>= lpK(MATCH_PARENT, WRAP_CONTENT)(kitkatStatusMargin)
  }
  def checkbox(implicit ctx: Context) = if (Build.VERSION.SDK_INT >= 21)
    new CheckBox(ctx) else new android.support.v7.widget.AppCompatCheckBox(ctx)
  def checkedText(implicit ctx: Context): CheckedTextView =
    if (Build.VERSION.SDK_INT >= 21)
      new CheckedTextView(ctx)
    else
      new android.support.v7.widget.AppCompatCheckedTextView(ctx)

  implicit class NougatActivity(val activity: Activity) extends AnyVal {
    @TargetApi(24)
    @inline def isMultiWindow = v(24) && activity.isInMultiWindowMode
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
