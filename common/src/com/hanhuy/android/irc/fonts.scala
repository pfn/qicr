package com.hanhuy.android.irc

import java.io._
import java.nio.ByteBuffer

import android.app.Activity
import android.content.Context
import android.graphics.{Paint, Typeface}
import android.preference.{ListPreference, Preference}
import android.text.{TextPaint, TextUtils}
import android.text.style.MetricAffectingSpan
import android.util.{TypedValue, AttributeSet}
import android.view.{View, Gravity, ViewGroup}
import android.widget._
import com.hanhuy.android.common.AndroidConversions
import macroid.contrib.Layouts.RuleRelativeLayout
import macroid.contrib.Layouts.RuleRelativeLayout.Rule

import scala.util.Try

object FontManager {
  private var _fontsByName = Option.empty[Map[String,String]]
  private var _fontsByFile = Option.empty[Map[String,String]]
  def fontsByFile = _fontsByFile getOrElse listFonts._1
  def fontsByName = _fontsByName getOrElse listFonts._2

  private def listFonts = {
    var fonts = Map.empty[String,String]

    val dir = new File("/system/fonts")

    if (dir.exists) {
      val files = dir.listFiles

      if (files != null) {
        files foreach { file =>
          val fontname = Try(getFontName(file.getAbsolutePath)).toOption.flatten

          fontname foreach { f =>
            fonts += file.getAbsolutePath -> f
          }
        }
      }
    }

    _fontsByFile = Some(fonts)
    val byName = fonts map { case (k,v) => v -> k } toMap

    _fontsByName = Some(byName)

    (fonts, byName)
  }

  // https://developer.apple.com/fonts/TrueType-Reference-Manual/RM06/Chap6.html
  // https://developer.apple.com/fonts/TrueType-Reference-Manual/RM06/Chap6name.html
  private val SUPPORTED_VERSIONS = Set(0x74727565, 0x00010000, 0x4f54544f)
  def getFontName(fontFilename: String): Option[String] = {
    val file = new RandomAccessFile(fontFilename, "r")
    val version = file.readInt()

    val name = if (SUPPORTED_VERSIONS(version)) {

      val tables = file.readShort()
      file.skipBytes(6)

      val result = (Option.empty[String] /: (0 until tables)) { (r, _) =>
        if (r.isDefined) r else {
          val tag = file.readInt()
          file.skipBytes(4)
          val offset = file.readInt()
          val length = file.readInt()

          if (tag == (0 /: "name") { (a, c) => c.toInt & 0xff | (a << 8) }) {
            val table = Array.ofDim[Byte](length)
            val buf   = ByteBuffer.wrap(table)

            file.seek(offset)
            file.readFully(table)

            val count = buf.getShort(2)
            val string_offset = buf.getShort(4)

            (Option.empty[String] /: (0 until count)) { (a, record) =>
              if (a.isDefined) a else {
                val nameid_offset = record * 12 + 6
                val platformID    = buf.getShort(nameid_offset)
                val nameid_value  = buf.getShort(nameid_offset + 6)

                if (nameid_value == 4 && platformID == 1) {
                  val name_length = buf.getShort(nameid_offset + 8)
                  val name_offset = buf.getShort(nameid_offset + 10) + string_offset

                  if (name_offset >= 0 && name_offset + name_length < table.length)
                    Some(new String(table, name_offset, name_length))
                  else a
                } else a
              }
            }
          } else r
        }
      }
      result
    } else None
    file.close()
    name
  }
}
class FontSizePreference(c: Context, attrs: AttributeSet)
extends Preference(c, attrs)
with SeekBar.OnSeekBarChangeListener {
  import android.view.ViewGroup.LayoutParams._
  import com.hanhuy.android.irc.Tweaks._
  import macroid.FullDsl._
  import macroid._

  implicit val appContext = AppContext(Application.context)
  // hopefully this is always true
  implicit val actContext = ActivityContext(c.asInstanceOf[Activity])

  var summary: TextView = _
  var defaultSize: Int = _

  val a = c.obtainStyledAttributes(attrs, R.styleable.FontSizePreference)
  val fontNameKey = a.getString(R.styleable.FontSizePreference_fontNameKey)
  a.recycle()

  override def onBindView(view: View) = {
    super.onBindView(view)
    summary.setText("%d sp" format getPersistedInt(defaultSize))
    summary.setTextSize(
      TypedValue.COMPLEX_UNIT_SP, getPersistedInt(defaultSize))
  }

  override def onCreateView(parent: ViewGroup) = {
    val typeface = for {
      k <- Option(fontNameKey)
      n <- Option(getSharedPreferences.getString(k, null))
      t  = Typeface.createFromFile(n)
    } yield t
    getUi(
      l[RelativeLayout](
        w[TextView] <~ id(android.R.id.title) <~ tweak { tv: TextView =>
          tv.setSingleLine(true)
          tv.setTextAppearance(c, android.R.style.TextAppearance_Medium)
          tv.setEllipsize(TextUtils.TruncateAt.MARQUEE)
          tv.setGravity(Gravity.CENTER)
          tv.setHorizontalFadingEdgeEnabled(true)
        } <~ lp[RelativeLayout](WRAP_CONTENT, 26 sp),
        w[TextView] <~ id(android.R.id.summary) <~ wire(summary) <~
          tweak { tv: TextView =>
            tv.setGravity(Gravity.CENTER)
            tv.setTextAppearance(c, android.R.style.TextAppearance_Small)
            defaultSize = (tv.getTextSize /
              getContext.getResources.getDisplayMetrics.scaledDensity).toInt
            typeface foreach tv.setTypeface
            tv.setMaxLines(4)
            tv.setIncludeFontPadding(false)
          } <~ lp[RuleRelativeLayout](WRAP_CONTENT, 26 sp,
          Rule(RelativeLayout.RIGHT_OF, android.R.id.title)) <~
          margin(left = 8 dp),
        w[SeekBar] <~ tweak { sb: SeekBar =>
          sb.setMax(20)
          sb.setProgress(math.max(0, getPersistedInt(defaultSize) - 4))
          sb.setOnSeekBarChangeListener(this)
        } <~
          lp[RuleRelativeLayout](MATCH_PARENT, WRAP_CONTENT,
            Rule(RelativeLayout.BELOW, android.R.id.title),
            Rule(RelativeLayout.ALIGN_PARENT_LEFT, 1),
            Rule(RelativeLayout.ALIGN_PARENT_RIGHT, 1))
      ) <~ padding(left = 16 dp, right = 8 dp, top = 6 dp, bottom = 6 dp)
    )
  }

  override def onStartTrackingTouch(seekBar: SeekBar) = ()
  override def onStopTrackingTouch(seekBar: SeekBar) = ()
  override def onProgressChanged(seekBar: SeekBar, progress: Int,
                                 fromTouch: Boolean) = {
    if (fromTouch) {
      val size = progress + 4
      persistInt(size)
      summary.setText("%d sp" format size)
      summary.setTextSize( TypedValue.COMPLEX_UNIT_SP, size)
    }
  }
}

class TypefaceSpan(typeface: Typeface) extends MetricAffectingSpan {

  override def updateDrawState(drawState: TextPaint) {
    update(drawState)
  }

  override def updateMeasureState(paint: TextPaint) {
    update(paint)
  }

  def update(paint: Paint) {
    val oldTypeface = paint.getTypeface
    val oldStyle    = if (oldTypeface != null) oldTypeface.getStyle else 0
    val fakeStyle   = oldStyle & ~typeface.getStyle
    if ((fakeStyle & Typeface.BOLD)   != 0) paint.setFakeBoldText(true)
    if ((fakeStyle & Typeface.ITALIC) != 0) paint.setTextSkewX(-0.25f)
    paint.setTypeface(typeface)
  }
}

class FontNamePreference(c: Context, attrs: AttributeSet)
extends ListPreference(c, attrs) {
  import AndroidConversions._
  import SpannedGenerator._
  val (names, paths) = FontManager.fontsByName.toList.sortBy(_._1).unzip

  val entries = names map { n =>
    "%1" formatSpans span(
      new TypefaceSpan(Typeface.createFromFile(FontManager.fontsByName(n))), n)
  }
  setEntries(entries.toArray: Array[CharSequence])
  setEntryValues(paths.toArray: Array[CharSequence])

  override def setValue(value: String) = {
    super.setValue(value)
    setSummary(getEntry)
  }

  override def getSummary = getEntry
}
