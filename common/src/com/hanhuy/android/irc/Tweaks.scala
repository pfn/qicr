package com.hanhuy.android.irc

import android.content.Context
import android.graphics.{Point, Color}
import android.graphics.drawable.Drawable
import android.os.Build
import android.text.InputType
import android.util.TypedValue
import android.view.View.MeasureSpec
import android.view.inputmethod.EditorInfo
import android.view._
import android.widget._
import com.hanhuy.android.common.{SystemService, AndroidConversions}

import macroid._
import macroid.FullDsl._

import scala.reflect.ClassTag

/**
 * @author pfnguyen
 */
object Tweaks {
  implicit val wm = SystemService[WindowManager](Context.WINDOW_SERVICE)
  import ViewGroup.LayoutParams._

  lazy val llMatchParent = lp[LinearLayout](MATCH_PARENT, MATCH_PARENT, 0)
  lazy val llMatchWidth = lp[LinearLayout](MATCH_PARENT, WRAP_CONTENT, 0)

  def margin(left: Int = 0, top: Int = 0, right: Int = 0, bottom: Int = 0, all: Int = -1) = Tweak[View] {
    _.getLayoutParams match {
      case m: ViewGroup.MarginLayoutParams =>
        if (all >= 0) {
          m.topMargin = all
          m.bottomMargin = all
          m.rightMargin = all
          m.leftMargin = all
        } else {
          m.topMargin = top
          m.bottomMargin = bottom
          m.rightMargin = right
          m.leftMargin = left
        }
    }
  }
  def image(resid: Int) = Tweak[ImageView](_.setImageResource(resid))

  def bgres(resid: Int) = Tweak[View](_.setBackgroundResource(resid))

  def bg(drawable: Drawable) = Tweak[View](_.setBackgroundDrawable(drawable))
  def bg(color: Int) = Tweak[View](_.setBackgroundColor(color))
  def bg(color: String) = Tweak[View](
    _.setBackgroundColor(Color.parseColor(color)))

  def inputType(types: Int) = Tweak[TextView](_.setInputType(types))
  def hint(s: CharSequence) = Tweak[TextView](_.setHint(s))
  def hint(resid: Int) = Tweak[TextView](_.setHint(resid))

  def hidden = Tweak[View](_.setVisibility(View.INVISIBLE))

  def tweak[A <: View,B](f: A => B) = Tweak[A](a => f(a))

  def sw(w: Int)(implicit ctx: AppContext) = {
    import AndroidConversions._
    val p = new Point
    val d = ctx.get.systemService[WindowManager].getDefaultDisplay
    if (Build.VERSION.SDK_INT >= 17) {
      d.getRealSize(p)
    } else if (Build.VERSION.SDK_INT >= 14) {
      type RawSizeHack = {
        def getRawWidth: Int
        def getRawHeight: Int
      }
      val d2 = d.asInstanceOf[RawSizeHack]
      p.x = d2.getRawWidth
      p.y = d2.getRawHeight
    } else {
      p.x = d.getWidth
      p.y = d.getHeight
    }
    MediaQuery(w <= p.x && w <= p.y)
  }

  def phone(implicit c: AppContext) = !sw(600 dp)
  def tablet(implicit c: AppContext) = sw(600 dp)

  def newerThan(v: Int) = MediaQuery(Build.VERSION.SDK_INT >= v)

  def actionBarHeight(implicit ctx: ActivityContext) = {
    val tv = new TypedValue
    val r = ctx.get.getTheme.resolveAttribute(R.attr.actionBarSize, tv, true)
    if (r) {
      TypedValue.complexToDimensionPixelSize(
        tv.data, ctx.get.getResources.getDisplayMetrics)
    } else 0
  }
  def statusBarHeight(implicit ctx: ActivityContext) = {
    val id = ctx.get.getResources.getIdentifier(
      "status_bar_height", "dimen", "android")
    if (id != 0) ctx.get.getResources.getDimensionPixelSize(id) else 0
  }

  def navBarHeight(implicit ctx: AppContext) = {
    val id = ctx.get.getResources.getIdentifier(
      "navigation_bar_height", "dimen", "android")
    if (id != 0 && hasSystemNav)
      ctx.get.getResources.getDimensionPixelSize(id) else 0
  }
  def kitkatPaddingTop(implicit ctx: ActivityContext) =
    newerThan(19) ? padding(top = statusBarHeight + actionBarHeight)
  def kitkatPaddingBottom(implicit ctx: ActivityContext, c2: AppContext) =
    ((tablet | portrait) & newerThan(19)) ? padding(bottom = navBarHeight)

  def kitkatPadding(padTop: Boolean)(implicit ctx: ActivityContext, c2: AppContext) =
    newerThan(19) ? padding(
      top    = if (padTop) statusBarHeight + actionBarHeight else 0,
      bottom = (tablet | portrait) ? navBarHeight | 0,
      right  = (phone & landscape) ? navBarHeight | 0)
  def kitkatPadding(implicit ctx: ActivityContext, c2: AppContext) =
    newerThan(19) ? padding(
      top    = statusBarHeight + actionBarHeight,
      bottom = (tablet | portrait) ? navBarHeight | 0,
      right  = (phone & landscape) ? navBarHeight | 0)

  def kitkatInputMargin(implicit ctx: ActivityContext, c2: AppContext) =
    newerThan(19) ? margin(
      bottom = (tablet | portrait) ? navBarHeight | 0,
      right  = (phone & landscape) ? navBarHeight | 0)

  def buttonTweaks(implicit c: AppContext) = tweak { b: ImageButton =>
    b.setFocusable(false)
    b.setFocusableInTouchMode(false)
    b.setScaleType(ImageView.ScaleType.CENTER_INSIDE)
  } + lp[LinearLayout](48 dp, 48 dp)

  lazy val inputTweaks = tweak { e: EditText =>
    import InputType._
    import EditorInfo._
    e.setInputType(TYPE_CLASS_TEXT | TYPE_TEXT_FLAG_AUTO_CORRECT)
    e.setImeOptions(IME_ACTION_SEND | IME_FLAG_NO_FULLSCREEN)
  }

  val textCapWords = tweak { e: EditText =>
    import InputType._
    e.setInputType(TYPE_CLASS_TEXT | TYPE_TEXT_FLAG_CAP_WORDS)
  }

  val textUri = tweak { e: EditText =>
    import InputType._
    e.setInputType(TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_URI)
  }

  val number = tweak { e: EditText =>
    import InputType._
    e.setInputType(TYPE_CLASS_NUMBER)
  }

  val textPassword = tweak { e: EditText =>
    import InputType._
    e.setInputType(TYPE_TEXT_VARIATION_PASSWORD | TYPE_CLASS_TEXT)
  }

  private lazy val primitiveMap: Map[Class[_],Class[_]] = Map(
    classOf[java.lang.Integer]   -> java.lang.Integer.TYPE,
    classOf[java.lang.Double]    -> java.lang.Double.TYPE,
    classOf[java.lang.Short]     -> java.lang.Short.TYPE,
    classOf[java.lang.Float]     -> java.lang.Float.TYPE,
    classOf[java.lang.Character] -> java.lang.Character.TYPE,
    classOf[java.lang.Byte]      -> java.lang.Byte.TYPE
  )
  abstract class LpRelation[V <: ViewGroup, LP <: ViewGroup.LayoutParams : ClassTag] {
    def lpType = implicitly[ClassTag[LP]].runtimeClass
    def lp(args: Any*) = lpType.getConstructor(
      args map { a =>
        val c = a.getClass
        primitiveMap.getOrElse(c, c)
      }:_*).newInstance(args map (_.asInstanceOf[AnyRef]): _*).asInstanceOf[LP]
  }
  implicit object LLRelation extends LpRelation[LinearLayout, LinearLayout.LayoutParams]
  implicit object TRRelation extends LpRelation[TableRow, TableRow.LayoutParams]

  def lp2[V <: ViewGroup,LP <: ViewGroup.LayoutParams, C](args: Any*)
                                                         (p: LP => C)
                                                         (implicit r: LpRelation[V,LP]) = tweak {
    v: View =>
      val lp = r.lp(args: _*)
      p(lp)
      v.setLayoutParams(lp)
  }

  lazy val hasSystemNav = !KeyCharacterMap.deviceHasKey(KeyEvent.KEYCODE_BACK)
}

class SquareImageButton(c: Context) extends ImageButton(c) {
  override def onMeasure(mw: Int, mh: Int) = {
    val w = MeasureSpec.getSize(mw)
    val h = MeasureSpec.getSize(mh)
    val m = if (w > h) mw else mh
    super.onMeasure(m, m)
  }
}
