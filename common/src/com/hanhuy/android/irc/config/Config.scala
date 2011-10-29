package com.hanhuy.android.irc.config

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteDatabase
import android.provider.BaseColumns

import scala.collection.mutable.ArrayBuffer

import Config._

object Config {
    val DATABASE_VERSION = 1
    val DATABASE_NAME = "config"
    val TABLE_SERVERS = "servers"

    val FIELD_NAME        = "name"
    val FIELD_AUTOCONNECT = "autoconnect"
    val FIELD_HOSTNAME    = "hostname"
    val FIELD_PORT        = "port"
    val FIELD_SSL         = "ssl"
    val FIELD_LOGGING     = "logging"
    val FIELD_NICKNAME    = "nickname"
    val FIELD_ALTNICK     = "altnick"
    val FIELD_USERNAME    = "username"
    val FIELD_PASSWORD    = "password"
    val FIELD_REALNAME    = "realname"
    val FIELD_AUTORUN     = "autorun"
    val FIELD_AUTOJOIN    = "autojoin"
    val FIELD_USESOCKS    = "usesocks"
    val FIELD_SOCKSHOST   = "sockshost"
    val FIELD_SOCKSPORT   = "socksport"
    val FIELD_SOCKSUSER   = "socksuser"
    val FIELD_SOCKSPASS   = "sockspass"
    val FIELD_USESASL     = "usesasl"
    val FIELD_SASLUSER    = "sasluser"
    val FIELD_SASLPASS    = "saslpass"

    val TABLE_SERVERS_DDL = "CREATE TABLE " + TABLE_SERVERS + " (" +
            BaseColumns._ID   + " INTEGER PRIMARY KEY, " +
            FIELD_NAME        + " TEXT NOT NULL, " +
            FIELD_AUTOCONNECT + " INTEGER NOT NULL, " +
            FIELD_HOSTNAME    + " TEXT NOT NULL, " +
            FIELD_PORT        + " INTEGER NOT NULL, " +
            FIELD_SSL         + " INTEGER NOT NULL, " +
            FIELD_LOGGING     + " INTEGER NOT NULL, " +
            FIELD_NICKNAME    + " TEXT NOT NULL, " +
            FIELD_ALTNICK     + " TEXT NOT NULL, " +
            FIELD_USERNAME    + " TEXT NOT NULL, " +
            FIELD_PASSWORD    + " TEXT, " +
            FIELD_REALNAME    + " TEXT NOT NULL, " +
            FIELD_AUTOJOIN    + " TEXT, " +
            FIELD_AUTORUN     + " TEXT, " +
            FIELD_USESOCKS    + " INTEGER NOT NULL, " +
            FIELD_SOCKSHOST   + " TEXT, " +
            FIELD_SOCKSPORT   + " INTEGER, " +
            FIELD_SOCKSUSER   + " TEXT, " +
            FIELD_SOCKSPASS   + " TEXT, " +
            FIELD_USESASL     + " INTEGER NOT NULL, " +
            FIELD_SASLUSER    + " TEXT, " +
            FIELD_SASLPASS    + " TEXT " +
    ");"
}
class Config(context: Context)
extends SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
    override def onCreate(db: SQLiteDatabase) {
        db.execSQL(TABLE_SERVERS_DDL)
    }
    override def onUpgrade(db: SQLiteDatabase, oldv: Int, newv: Int) = Unit

    def getServers() : ArrayBuffer[Server] = {
        val db = getReadableDatabase()
        val list = new ArrayBuffer[Server]
        val c = db.query(TABLE_SERVERS, null, null, null, null, null, null)
        val col_id          = c.getColumnIndexOrThrow(BaseColumns._ID)
        val col_name        = c.getColumnIndexOrThrow(FIELD_NAME)
        val col_autoconnect = c.getColumnIndexOrThrow(FIELD_AUTOCONNECT)
        val col_hostname    = c.getColumnIndexOrThrow(FIELD_HOSTNAME)
        val col_port        = c.getColumnIndexOrThrow(FIELD_PORT)
        val col_ssl         = c.getColumnIndexOrThrow(FIELD_SSL)
        val col_nickname    = c.getColumnIndexOrThrow(FIELD_NICKNAME)
        val col_altnick     = c.getColumnIndexOrThrow(FIELD_ALTNICK)
        val col_username    = c.getColumnIndexOrThrow(FIELD_USERNAME)
        val col_password    = c.getColumnIndexOrThrow(FIELD_PASSWORD)
        val col_realname    = c.getColumnIndexOrThrow(FIELD_REALNAME)
        val col_autorun     = c.getColumnIndexOrThrow(FIELD_AUTORUN)
        val col_autojoin    = c.getColumnIndexOrThrow(FIELD_AUTOJOIN)
        while (c.moveToNext()) {
            val s = new Server
            s.id          = c.getLong(col_id)
            s.name        = c.getString(col_name)
            s.autoconnect = c.getInt(col_autoconnect) != 0
            s.hostname    = c.getString(col_hostname)
            s.port        = c.getInt(col_port)
            s.ssl         = c.getInt(col_ssl) != 0
            s.nickname    = c.getString(col_nickname)
            s.altnick     = c.getString(col_altnick)
            s.username    = c.getString(col_username)
            s.password    = c.getString(col_password)
            s.realname    = c.getString(col_realname)
            s.autorun     = c.getString(col_autorun)
            s.autojoin    = c.getString(col_autojoin)
            list += s
        }
        c.close()
        db.close()
        list
    }
    def addServer(server: Server) {
        val db = getWritableDatabase()
        val id = db.insertOrThrow(TABLE_SERVERS, null, server.values)
        server.id = id
        db.close()
        if (id == -1)
            throw new IllegalStateException("Unable to create server")
    }
    def updateServer(server: Server) {
        val db = getWritableDatabase()
        val rows = db.update(TABLE_SERVERS, server.values,
                BaseColumns._ID + " = ?", Array[String]("" + server.id))
        db.close()
        if (rows != 1)
            throw new IllegalStateException("Wrong rows updated: " + rows)
    }
    def deleteServer(server: Server) {
        val db = getWritableDatabase()
        val rows = db.delete(TABLE_SERVERS,
                BaseColumns._ID + " = ?", Array[String]("" + server.id))
        db.close()
        if (rows != 1)
            throw new IllegalStateException("Wrong rows deleted: " + rows)
    }
}
