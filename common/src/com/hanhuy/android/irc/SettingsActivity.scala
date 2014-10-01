package com.hanhuy.android.irc

import android.preference.Preference.OnPreferenceClickListener
import android.text.{Editable, TextWatcher}
import android.widget.EditText
import com.hanhuy.android.irc.model.{MessageAdapter, BusEvent}

import android.app.{AlertDialog, Fragment, Activity}
import android.content.{DialogInterface, Context, SharedPreferences}
import android.os.{Build, Bundle}
import android.preference.{Preference, PreferenceManager, PreferenceFragment}
import com.hanhuy.android.common.{AndroidConversions, ServiceBus, UiBus}
import org.acra.ACRA

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
}

object Settings {
  val NAVIGATION_MODE_TABS = "Tabs"
  val NAVIGATION_MODE_DROPDOWN = "Drop Down"
  val NAVIGATION_MODE_DRAWER = "Drawer"

  val WIDGET_IDS = Setting[String]("internal_widget_ids", "")
  val HIDE_KEYBOARD = Setting[Boolean]("ui_hide_kbd_after_send", false)
  val IRC_DEBUG = Setting[Boolean]("irc_debug_log", false)
  val NAVIGATION_MODE = Setting[String]("ui_selector_mode2",
    NAVIGATION_MODE_TABS)
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
    AndroidConversions.honeycombAndNewer)
  val DAYNIGHT_MODE = Setting[Boolean]("ui_daynight_mode", false)

  def apply(c: Context) = {
    new Settings(c.getApplicationContext)
  }
}

class Settings(val context: Context)
extends SharedPreferences.OnSharedPreferenceChangeListener {
    val p = PreferenceManager.getDefaultSharedPreferences(context)
    p.registerOnSharedPreferenceChangeListener(this)

    override def onSharedPreferenceChanged(p: SharedPreferences, key: String) {
        val Setting(s) = key
        val e = BusEvent.PreferenceChanged(this, s)
        UiBus.send(e) // already on main thread
        ServiceBus.send(e)
    }

  def get[A](setting: Setting[A])(implicit m: ClassTag[A]): A = {
    val result = if (classOf[String] == m.runtimeClass) {
      val default: String = setting.defaultRes map {
        context.getString
      } getOrElse setting.default.asInstanceOf[String]
      p.getString(setting.key, default)
    } else if (classOf[Boolean] == m.runtimeClass) {
      p.getBoolean(setting.key, setting.default.asInstanceOf[Boolean])
    } else if (classOf[Float] == m.runtimeClass) {
      p.getFloat(setting.key, setting.default.asInstanceOf[Float])
    } else if (classOf[Long] == m.runtimeClass) {
      p.getLong(setting.key, setting.default.asInstanceOf[Long])
    } else if (classOf[Int] == m.runtimeClass) {
      p.getInt(setting.key, setting.default.asInstanceOf[Int])
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
class SettingsFragmentActivity extends Activity {
  override def onCreate(bundle: Bundle) {
    super.onCreate(bundle)
    val f = getFragmentManager.findFragmentByTag("settings fragment")
    if (f == null) {
      val tx = getFragmentManager.beginTransaction()
      tx.add(android.R.id.content, new SettingsFragment, "settings fragment")
      tx.commit()
    }
  }
}

class SettingsFragment
extends PreferenceFragment with macroid.Contexts[Fragment] {
  import macroid.FullDsl._
  import AndroidConversions._
  override def onCreate(bundle: Bundle) {
    super.onCreate(bundle)
    addPreferencesFromResource(R.xml.settings)

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
              getUi(toast("Debug log sent") <~ fry)
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
