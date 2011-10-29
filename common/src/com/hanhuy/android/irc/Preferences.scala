package com.hanhuy.android.irc

import android.content.Context

import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FileNotFoundException

object Preferences {
    var _prefs : Object = _
    val FILE_NAME = "prefs.dat"

    def load(c : Context) {
    }

    def save(c : Context) {
    }

    def prefs = _prefs
}
