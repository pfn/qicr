package com.hanhuy.android.irc

import android.content.Context

import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FileNotFoundException

object Preferences {
    val FILE_NAME = "prefs.dat"
    var _prefs : ConfigData.Preferences.Builder = _

    def load(c : Context) {
        try {
            val fin = c.openFileInput(FILE_NAME)
            _prefs = ConfigData.Preferences.newBuilder(
                    ConfigData.Preferences.parseFrom(fin))
            fin.close()
        }
        catch {
            case e: FileNotFoundException => {
                _prefs = ConfigData.Preferences.newBuilder()
            }
        }
        prefs
    }

    def save(c : Context) {
        val fout = c.openFileOutput(FILE_NAME, Context.MODE_PRIVATE)
        val p = _prefs.build()
        p.writeTo(fout)
        fout.close()
        _prefs = ConfigData.Preferences.newBuilder(p)
    }

    def prefs = _prefs
}
