package com.hanhuy.android.irc

import android.annotation.TargetApi
import android.media.RingtoneManager
import android.net.Uri
import android.preference.Preference.{OnPreferenceChangeListener, OnPreferenceClickListener}
import android.text.{Editable, TextWatcher}
import android.provider.{Settings => ASettings}
import android.util.AttributeSet
import android.widget.{Toast, EditText}
import com.hanhuy.android.irc.model.{MessageAdapter, BusEvent}
import Tweaks._

import android.app.{Activity, AlertDialog, Fragment}
import android.support.v7.app.{AppCompatActivity, ActionBarActivity}
import android.content.{DialogInterface, Context, SharedPreferences}
import android.os.{Build, Bundle}
import android.preference._
import com.hanhuy.android.common._
import com.hanhuy.android.conversions._
import org.acra.ACRA
import iota._

import scala.reflect.ClassTag

object Setting {
  private var settings = Map.empty[String,Setting[_]]
  def unapply(key: String): Option[Setting[_]] = settings get key
  def apply[A](key: String, default: A) = new Setting(key, default, None)
  def apply[A](key: String, res: Int) = new Setting(key,
    null.asInstanceOf[A], Some(res))
}
class Setting[A](val key: String, val default: A, val defaultRes: Option[Int]) {
  Setting.settings = Setting.settings + ((key, this))

  override def toString = {
    "Setting: " + key
  }
}

object Settings {
  val NAVIGATION_MODE_TABS = "Tabs"
  val NAVIGATION_MODE_DRAWER = "Drawer"

  val CHARSET = Setting[String]("irc_charset", "UTF-8")
  val FONT_SIZE = Setting[Int]("font_size", default = -1)
  val FONT_NAME = Setting[String]("font_name", null)
  val IRC_LOGGING = Setting[Boolean]("irc_logging", true)
  val RUNNING_NOTIFICATION = Setting[Boolean]("notification_running_enable", true)
  val NOTIFICATION_SOUND = Setting[String]("notification_sound",
    ASettings.System.DEFAULT_NOTIFICATION_URI.toString)
  val NOTIFICATION_VIBRATE = Setting[Boolean]("notification_vibrate_enable", false)
  val WIDGET_IDS = Setting[String]("internal_widget_ids", "")
  val HIDE_KEYBOARD = Setting[Boolean]("ui_hide_kbd_after_send", false)
  val IRC_DEBUG = Setting[Boolean]("irc_debug_log", false)
  val NAVIGATION_MODE = Setting[String]("ui_selector_mode2",
    NAVIGATION_MODE_DRAWER)
  val QUIT_MESSAGE = Setting[String]("irc_quit_message",
    R.string.pref_quit_message_default)
  val SPEECH_REC_EOL = Setting[String]("speech_cmd_eol",
    R.string.pref_speech_rec_eol_default)
  val SPEECH_REC_CLEAR_LINE = Setting[String]("speech_cmd_clear_line",
    R.string.pref_speech_rec_clearline_default)
  val ROTATE_LOCK = Setting[Boolean]("ui_rotate_lock", false)
  val QUIT_PROMPT = Setting[Boolean]("ui_quit_prompt", true)
  val SHOW_TIMESTAMP = Setting[Boolean]("ui_show_timestamp", false)
  val CLOSE_TAB_PROMPT = Setting[Boolean]("ui_close_tab_prompt", true)
  val MESSAGE_LINES = Setting[String]("ui_message_lines",
    MessageAdapter.DEFAULT_MAXIMUM_SIZE.toString)
  val SHOW_JOIN_PART_QUIT = Setting[Boolean]("irc_show_join_part_quit", false)
  val SHOW_SPEECH_REC = Setting[Boolean]("ui_show_speech_rec", true)
  val SHOW_NICK_COMPLETE = Setting[Boolean]("ui_show_nick_complete",
    honeycombAndNewer)
  val DAYNIGHT_MODE = Setting[Boolean]("ui_daynight_mode", false)


  private lazy val instance = new Settings

  def get[A](setting: Setting[A])(implicit m: ClassTag[A]): A =
    instance.get(setting)
  def set[A](setting: Setting[A], value: A)(implicit m: ClassTag[A]): Unit = {
    instance.set(setting, value)
  }
}

class Settings private()
extends SharedPreferences.OnSharedPreferenceChangeListener {
    val p = PreferenceManager.getDefaultSharedPreferences(Application.context)
    p.registerOnSharedPreferenceChangeListener(this)

    override def onSharedPreferenceChanged(p: SharedPreferences, key: String) {
      Setting.unapply(key) foreach { s =>
        val e = BusEvent.PreferenceChanged(this, s)
        UiBus.send(e) // already on main thread
        ServiceBus.send(e)
      }
    }

  def get[A](setting: Setting[A])(implicit m: ClassTag[A]): A = {
    val result = if (classOf[String] == m.runtimeClass) {
      val default: String = setting.defaultRes map {
        Application.context.getString
      } getOrElse setting.default.asInstanceOf[String]
      p.getString(setting.key, default)
    } else if (classOf[Boolean] == m.runtimeClass) {
      p.getBoolean(setting.key, setting.default.asInstanceOf[Boolean])
    } else if (classOf[Long] == m.runtimeClass) {
      p.getLong(setting.key, setting.default.asInstanceOf[Long])
    } else if (classOf[Int] == m.runtimeClass) {
      // broken otherwise
      return p.getInt(setting.key, setting.default.asInstanceOf[Int]).asInstanceOf[A]
    } else if (classOf[Float] == m.runtimeClass) {
      p.getFloat(setting.key, setting.default.asInstanceOf[Float])
    } else {
      throw new IllegalArgumentException("Unknown type: " + m.runtimeClass)
    }
    result.asInstanceOf[A]
  }
  def set[A](setting: Setting[A], value: A)(implicit m: ClassTag[A]) {
    val editor = p.edit()
    if (classOf[Boolean] == m.runtimeClass) {
      editor.putBoolean(setting.key, value.asInstanceOf[Boolean])
    } else if (classOf[String] == m.runtimeClass) {
        editor.putString(setting.key, value.asInstanceOf[String])
    } else {
      throw new IllegalArgumentException("Unknown type: " + m.runtimeClass)
    }
    editor.commit()
  }
}

// android3.0+
// now actionbaractivity for material on <5.0
@TargetApi(11)
class SettingsFragmentActivity extends AppCompatActivity {
  override def onCreate(bundle: Bundle) {
    super.onCreate(bundle)
    val content = new android.widget.FrameLayout(this)
    content.setId(Id.content)
    setContentView((IO(content) >>= kitkatPadding).perform())
    val f = getFragmentManager.findFragmentByTag("settings fragment")
    if (f == null) {
      val tx = getFragmentManager.beginTransaction()
      tx.add(Id.content, new SettingsFragment, "settings fragment")
      tx.commit()
    }
  }
}

object SettingsFragment {
  def setupNotificationPreference(c: Context, ps: PreferenceScreen): Unit = {

    val p = ps.findPreference(Settings.NOTIFICATION_SOUND.key)
    val notification = Settings.get(Settings.NOTIFICATION_SOUND)
    val r = RingtoneManager.getRingtone(c, Uri.parse(notification))
    Option(p) foreach { pref =>
      pref.setSummary(Option(r).fold("")(_.getTitle(c)))
      pref.setOnPreferenceChangeListener(new OnPreferenceChangeListener {
        override def onPreferenceChange(preference: Preference, newValue: scala.Any) = {
          val r = RingtoneManager.getRingtone(c, Uri.parse(newValue.toString))
          pref.setSummary(Option(r).fold("")(_.getTitle(c)))
          true
        }
      })
    }

  }
}
@TargetApi(11)
class SettingsFragment
extends PreferenceFragment {

  override def onCreate(bundle: Bundle) {
    super.onCreate(bundle)
    addPreferencesFromResource(R.xml.settings)

    SettingsFragment.setupNotificationPreference(getActivity, getPreferenceScreen)

    getPreferenceScreen.findPreference(
      "debug.log").setOnPreferenceClickListener(
        new OnPreferenceClickListener {
          override def onPreferenceClick(pref: Preference) = {
            val b = new AlertDialog.Builder(getActivity)
            b.setTitle("Submit debug logs")
            b.setMessage("Add details about this log")
            val edit = new EditText(getActivity)
            b.setView(edit)
            b.setPositiveButton("Send", { () =>
              Toast.makeText(getActivity, "Debug log sent", Toast.LENGTH_SHORT).show()
              val e = new Exception("User submitted log: " + edit.getText)
              e.setStackTrace(Array(new StackTraceElement(
                Build.BRAND, Build.MODEL, Build.PRODUCT,
                (System.currentTimeMillis / 1000).toInt)))
              ACRA.getErrorReporter.handleSilentException(e)
              ()
            })
            b.setNegativeButton("Cancel", {(d: DialogInterface, i: Int) =>
              d.dismiss()
            })
            val d = b.show()
            val button = d.getButton(DialogInterface.BUTTON_POSITIVE)
            button.setEnabled(false)
            edit.addTextChangedListener(new TextWatcher {
              override def afterTextChanged(p1: Editable) {}
              override def beforeTextChanged(p1: CharSequence,
                                             p2: Int, p3: Int, p4: Int) { }

              override def onTextChanged(p1: CharSequence,
                                         p2: Int, p3: Int, p4: Int) {
                button.setEnabled(p1.length > 0)
              }

            })
            true
          }
        })
  }
}

class SettingsActivity extends PreferenceActivity {
  override def onCreate(b: Bundle) {
    super.onCreate(b)
    addPreferencesFromResource(R.xml.settings)
    SettingsFragment.setupNotificationPreference(this, getPreferenceScreen)
    val p = getPreferenceScreen().findPreference("debug.log")
    getPreferenceScreen.removePreference(p)
  }
}

class CharsetPreference(c: Context, attrs: AttributeSet)
  extends ListPreference(c, attrs) {
  import collection.JavaConversions._
  import java.nio.charset.Charset
  val entries = Charset.availableCharsets.keySet.toSeq.filterNot(c =>
    c.startsWith("x-")
  ).toArray[CharSequence]
  setEntries(entries)
  setEntryValues(entries)
  setValue(Settings.get(Settings.CHARSET))

  override def setValue(value: String) = {
    super.setValue(value)
    setSummary(getEntry)
  }

  override def getSummary = getEntry
}
