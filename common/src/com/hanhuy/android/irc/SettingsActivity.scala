package com.hanhuy.android.irc

import com.hanhuy.android.irc.model.BusEvent

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.preference.PreferenceManager
import android.preference.PreferenceActivity
import android.preference.PreferenceFragment

import scala.collection.mutable.HashSet

class Settings(c: Context)
extends SharedPreferences.OnSharedPreferenceChangeListener {
    val p = PreferenceManager.getDefaultSharedPreferences(c)
    p.registerOnSharedPreferenceChangeListener(this)

    override def onSharedPreferenceChanged(p: SharedPreferences, key: String) {
        val e = BusEvent.PreferenceChanged(key)
        UiBus.send(e)
        ServiceBus.send(e)
    }

    def getBoolean(key: Int, default: Boolean = false): Boolean =
            p.getBoolean(c.getString(key), default)
    def getFloat(key: Int, default: Float = 0f): Float =
            p.getFloat(c.getString(key), default)
    def getInt(key: Int, default: Int = 0): Int =
            p.getInt(c.getString(key), default)
    def getLong(key: Int, default: Long = 0l): Long =
            p.getLong(c.getString(key), default)
    def getString(key: Int, default: String = ""): String =
            p.getString(c.getString(key), default)
    def getString(key: Int, default: Int): String =
            p.getString(c.getString(key), c.getString(default))

    def set(key: Int, value: Boolean) =
            p.edit().putBoolean(c.getString(key), value).commit()
}

// android2.3-
class SettingsActivity extends PreferenceActivity {
    override def onCreate(bundle: Bundle) {
        super.onCreate(bundle)
        addPreferencesFromResource(R.xml.settings)
    }
}

// android3.0+
class SettingsFragmentActivity extends Activity {
    override def onCreate(bundle: Bundle) {
        super.onCreate(bundle)
        val tx = getFragmentManager().beginTransaction()
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
