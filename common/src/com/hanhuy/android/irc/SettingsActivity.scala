package com.hanhuy.android.irc

import com.hanhuy.android.irc.model.{MessageAdapter, BusEvent}

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.preference.PreferenceManager
import android.preference.PreferenceFragment

object Setting {
  private val settings = collection.mutable.HashMap[String,Setting[_]]()
  def unapply(key: String): Option[Setting[_]] = settings get key
  def apply[A](key: String, default: A) = new Setting(key, default, None)
  def apply[A](key: String, res: Int) = new Setting(key,
    null.asInstanceOf[A], Some(res))
}
class Setting[A](val key: String, val default: A, val defaultRes: Option[Int]) {
  Setting.settings += ((key, this))
}

object Settings {
  val NAVIGATION_MODE_TABS = "Tabs"
  val NAVIGATION_MODE_DROPDOWN = "Drop Down"
  val NAVIGATION_MODE_DRAWER = "Drawer"

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

  def get[A](setting: Setting[A])(implicit m: ClassManifest[A]): A = {
    val result = if (classOf[String] == m.erasure) {
      val default: String = setting.defaultRes map {
        context.getString
      } getOrElse setting.default.asInstanceOf[String]
      p.getString(setting.key, default)
    } else if (classOf[Boolean] == m.erasure) {
      p.getBoolean(setting.key, setting.default.asInstanceOf[Boolean])
    } else if (classOf[Float] == m.erasure) {
      p.getFloat(setting.key, setting.default.asInstanceOf[Float])
    } else if (classOf[Long] == m.erasure) {
      p.getLong(setting.key, setting.default.asInstanceOf[Long])
    } else if (classOf[Int] == m.erasure) {
      p.getInt(setting.key, setting.default.asInstanceOf[Int])
    } else {
      throw new IllegalArgumentException("Unknown type: " + m.erasure)
    }
    result.asInstanceOf[A]
  }
  def set[A](setting: Setting[A], value: A)(implicit m: ClassManifest[A]) {
    val editor = p.edit()
    if (classOf[Boolean] == m.erasure) {
      editor.putBoolean(setting.key, value.asInstanceOf[Boolean])
    } else {
      throw new IllegalArgumentException("Unknown type: " + m.erasure)
    }
    editor.commit()
  }
}

// android3.0+
class SettingsFragmentActivity extends Activity {
    override def onCreate(bundle: Bundle) {
        super.onCreate(bundle)
        val tx = getFragmentManager.beginTransaction()
        tx.add(android.R.id.content, new SettingsFragment, "settings fragment")
        tx.commit()
    }
}

class SettingsFragment extends PreferenceFragment {
    override def onCreate(bundle: Bundle) {
        super.onCreate(bundle)
        addPreferencesFromResource(R.xml.settings)
    }
}
