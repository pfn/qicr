package com.hanhuy.android.irc

import android.annotation.TargetApi
import android.graphics._
import android.graphics.drawable.{LayerDrawable, Drawable}
import android.support.design.widget.TabLayout
import android.support.v4.view.{ViewPager, PagerAdapter}
import com.hanhuy.android.common._
import com.hanhuy.android.extensions._
import com.hanhuy.android.appcompat.extensions.ExtensionOfToolbar
import SpannedGenerator._

import android.content.Context
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.view.{Gravity, ViewGroup, View, ContextThemeWrapper}
import android.widget.{ScrollView, TextView, LinearLayout}
import com.hanhuy.android.irc.model.BusEvent

import iota._
import Tweaks._

/**
  * @author pfnguyen
  */
object ColorPreferenceActivity {
  val colors = (R.array.material_red, "Red")      ::
    (R.array.material_pink,        "Pink")        ::
    (R.array.material_purple,      "Purple")      ::
    (R.array.material_deep_purple, "Deep Purple") ::
    (R.array.material_indigo,      "Indigo")      ::
    (R.array.material_blue,        "Blue")        ::
    (R.array.material_light_blue,  "Light Blue")  ::
    (R.array.material_cyan,        "Cyan")        ::
    (R.array.material_teal,        "Teal")        ::
    (R.array.material_green,       "Green")       ::
    (R.array.material_light_green, "Light Green") ::
    (R.array.material_lime,        "Lime")        ::
    (R.array.material_yellow,      "Yellow")      ::
    (R.array.material_amber,       "Amber")       ::
    (R.array.material_orange,      "Orange")      ::
    (R.array.material_deep_orange, "Deep Orange") ::
    (R.array.material_brown,       "Brown")       ::
    (R.array.material_blue_grey,   "Blue Grey")   ::
    Nil

  def colorSetting = {
    Settings.get(Settings.NICK_COLORS).?.fold {
      colors.map(r => Application.context.getResources.getIntArray(r._1)(5) | 0xff000000)
    } { list =>
      list.split(",").map(_.toInt | 0xff000000).toList
    }
  }

  def setColors(colors: List[Int]) =
    Settings.set(Settings.NICK_COLORS, colors.map(_ & 0xffffff).mkString(","))

  class DaynightDrawable(val context: Context, dark: Int, light: Int) extends Drawable with HasContext {
    override def draw(canvas: Canvas) = {
      val bounds = getBounds
      val width = bounds.width
      val right = new Rect(bounds)
      val left = new Rect(bounds)

      left.right = left.right - width / 2
      left.left = left.left + 8.dp
      left.top = left.top + 8.dp
      left.bottom = left.bottom - 8.dp

      right.right = right.right - 8.dp
      right.left = right.left + width / 2
      right.top = right.top + 8.dp
      right.bottom = right.bottom - 8.dp

      val paint = new Paint()

      val selected = getState.contains(android.R.attr.state_selected)
      paint.setColor(if (selected)
        resolveAttr(R.attr.colorPrimary, _.data)
      else
        0xff777777)
      val outline = new RectF(bounds)
      outline.inset(4.dp, 4.dp)
      canvas.drawRoundRect(outline, 2.dp, 2.dp, paint)
      paint.setColor(light)
      canvas.drawRect(left, paint)
      paint.setColor(dark)
      canvas.drawRect(right, paint)
    }

    override def isStateful = true
    override def setColorFilter(colorFilter: ColorFilter) = ()
    override def setAlpha(alpha: Int) = ()
    override def getOpacity = PixelFormat.OPAQUE
  }
  case class ColorPreference(context: Context, colors: Int, position: Int) extends ScrollView(context) with HasContext {
    import ViewGroup.LayoutParams._
    val darkTheme = new ContextThemeWrapper(context, R.style.AppTheme_Dark)
    val lightTheme = new ContextThemeWrapper(context, R.style.AppTheme_Light)

    val dark = resolveAttr(android.R.attr.windowBackground, _.data)(darkTheme)
    val light = resolveAttr(android.R.attr.windowBackground, _.data)(lightTheme)
    val colorValues = getResources.getIntArray(colors)
    val container = new LinearLayout(context)
    container.setOrientation(LinearLayout.VERTICAL)
    val res = resolveAttr(android.R.attr.selectableItemBackground, _.resourceId)
    @TargetApi(21)
    def selectable = if (v(21))
      getResources.getDrawable(res, context.getTheme)
    else
      getResources.getDrawable(res)
    lazy val views: Array[View] = colorValues.map(_ | 0xff000000).map { color =>
      val bg = new DaynightDrawable(context, dark, light)
      val layers = new LayerDrawable(Array(bg, selectable))
      val view = (w[TextView] >>= k.gravity(Gravity.CENTER) >>=
        k.backgroundDrawable(layers) >>= k.clickable(true) >>= k.textAppearance(context, R.style.TextAppearance_AppCompat_Large) >>=
        k.text(textColor(color,"abcdefghijklmnopqrstuvwxyz"))).perform()
      view.onClick0 {
        views.foreach(_.setSelected(false))
        setColors(colorSetting.updated(position, color))
        view.setSelected(true)
      }
      if (colorSetting(position) == color) view.setSelected(true)
      view
    }
    container.addView(new View(context), new LinearLayout.LayoutParams(0, 0, 1))
    views.foreach(view =>
      container.addView(view, new LinearLayout.LayoutParams(MATCH_PARENT, 48.dp)))
    container.addView(new View(context), new LinearLayout.LayoutParams(0, 0, 1))
    addView(container)
  }
}
class ColorPreferenceActivity extends AppCompatActivity with EventBus.RefOwner {
  import ViewGroup.LayoutParams._
  import ColorPreferenceActivity._
  lazy val pager = new ViewPager(this)
  lazy val tabs = new TabLayout(this, null, R.attr.qicrColorTabStyle)
  lazy val toolbar = newToolbar
  lazy val layout = l[LinearLayout](
    toolbar.! >>= lp(MATCH_PARENT, WRAP_CONTENT),
    tabs.! >>= kestrel { t =>
      t.setTabMode(TabLayout.MODE_SCROLLABLE)
    } >>= lp(MATCH_PARENT, WRAP_CONTENT),
    pager.! >>= kestrel { p =>
      p.setAdapter(ColorPageAdapter)
      tabs.setupWithViewPager(pager)
    } >>= lp(MATCH_PARENT, 0, 1) >>= id(Id.pager)
  ) >>= vertical

  override def onCreate(savedInstanceState: Bundle) = {
    setTheme(if (Settings.get(Settings.DAYNIGHT_MODE)) R.style.SetupTheme_Light else R.style.SetupTheme_Dark)
    super.onCreate(savedInstanceState)
    setSupportActionBar(toolbar)
    setContentView(layout.perform())
    toolbar.setNavigationIcon(Application.getDrawable(this, resolveAttr(R.attr.qicrCloseIcon, _.resourceId)))
    toolbar.navigationOnClick0(finish())
    getSupportActionBar.setTitle("Select Nick Colors")
  }

  object ColorPageAdapter extends PagerAdapter {
    implicit val c = ColorPreferenceActivity.this

    override def instantiateItem(container: ViewGroup, position: Int) = {
      val p = ColorPreference(ColorPreferenceActivity.this, colors(position)._1, position)
      container.addView(p)
      p
    }

    override def destroyItem(container: ViewGroup, position: Int, o: scala.Any) = {
      container.removeView(o.asInstanceOf[View])
    }

    override def isViewFromObject(view: View, o: scala.Any) = view == o
    override def getCount = colors.size

    override def getPageTitle(position: Int) =
      textColor(colorSetting(position), colors(position)._2.toUpperCase)
  }

  ServiceBus += {
    case BusEvent.PreferenceChanged(_, s) if s == Settings.NICK_COLORS =>
      (0 until tabs.getTabCount).foreach { i =>
        tabs.getTabAt(i).setText(ColorPageAdapter.getPageTitle(i))
      }
  }
}
