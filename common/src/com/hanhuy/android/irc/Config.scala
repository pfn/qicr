package com.hanhuy.android.irc

import android.content.ContentValues
import com.hanhuy.android.common.{UiBus, LogcatTag, RichLogger}
import com.hanhuy.android.irc.model.BusEvent.IgnoreListChanged
import com.hanhuy.android.irc.model.Server

import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteDatabase
import android.provider.BaseColumns
import android.widget.Toast
import RichLogger._

import Config._

object Config {
  implicit val TAG = LogcatTag("QicrConfig")
  // TODO encrypt the password with pbkdf2 on primary account username + salt
  val DATABASE_VERSION = 2
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

  val TABLE_SERVERS_DDL =
    s"""
       |CREATE TABLE $TABLE_SERVERS  (
       |  ${BaseColumns._ID}  INTEGER PRIMARY KEY,
       |  $FIELD_NAME         TEXT NOT NULL,
       |  $FIELD_AUTOCONNECT  INTEGER NOT NULL,
       |  $FIELD_HOSTNAME     TEXT NOT NULL,
       |  $FIELD_PORT         INTEGER NOT NULL,
       |  $FIELD_SSL          INTEGER NOT NULL,
       |  $FIELD_LOGGING      INTEGER NOT NULL,
       |  $FIELD_NICKNAME     TEXT NOT NULL,
       |  $FIELD_ALTNICK      TEXT NOT NULL,
       |  $FIELD_USERNAME     TEXT NOT NULL,
       |  $FIELD_PASSWORD     TEXT,
       |  $FIELD_REALNAME     TEXT NOT NULL,
       |  $FIELD_AUTOJOIN     TEXT,
       |  $FIELD_AUTORUN      TEXT,
       |  $FIELD_USESOCKS     INTEGER NOT NULL,
       |  $FIELD_SOCKSHOST    TEXT,
       |  $FIELD_SOCKSPORT    INTEGER,
       |  $FIELD_SOCKSUSER    TEXT,
       |  $FIELD_SOCKSPASS    TEXT,
       |  $FIELD_USESASL      INTEGER NOT NULL,
       |  $FIELD_SASLUSER     TEXT,
       |  $FIELD_SASLPASS     TEXT
       |);
     """.stripMargin

  val TABLE_IGNORES = "ignores"
  val TABLE_IGNORES_DDL =
    s"""
       |CREATE TABLE $TABLE_IGNORES (
       |  $FIELD_NICKNAME TEXT NOT NULL,
       |  CONSTRAINT unq_ignore UNIQUE ($FIELD_NICKNAME)
       |);
     """.stripMargin

  object Ignores {
    private lazy val initialIgnores = instance.listIgnores
    private var _ignored = Option.empty[Set[String]]
    private def ignored = _ignored getOrElse initialIgnores

    def size = ignored.size
    def isEmpty = ignored.isEmpty
    def mkString(s: String) = ignored.mkString(s)
    def map[A](f: String => A) = ignored.map(f)

    def apply(s: String) = ignored.apply(s)

    def +=(n: String) = {
      val newIgnores = ignored + n
      val change = newIgnores -- ignored
      instance.addIgnores(change)
      _ignored = Some(newIgnores)
      UiBus.send(IgnoreListChanged)
    }

    def -=(n: String) = {
      val newIgnores = ignored - n
      val changed = ignored -- newIgnores
      instance.deleteIgnores(changed)
      _ignored = Some(newIgnores)
      UiBus.send(IgnoreListChanged)
    }

    def clear(): Unit = {
      instance.deleteIgnores(ignored)
      _ignored = Some(Set.empty)
    }
  }

  val addServer    = instance.addServer _
  val updateServer = instance.updateServer _
  val deleteServer = instance.deleteServer _

  def servers = instance.servers

  private lazy val instance = new Config
}
class Config private()
extends SQLiteOpenHelper(Application.context, DATABASE_NAME, null, DATABASE_VERSION) {
  override def onCreate(db: SQLiteDatabase) = {
    db.beginTransaction()
    db.execSQL(TABLE_SERVERS_DDL)
    db.execSQL(TABLE_IGNORES_DDL)
    db.setTransactionSuccessful()
    db.endTransaction()
  }
  val UPGRADES: Map[Int,Seq[String]] = Map(
    2 -> Seq(TABLE_IGNORES_DDL)
  )

  override def onUpgrade(db: SQLiteDatabase, oldv: Int, newv: Int) {
    if (oldv < newv) {
      db.beginTransaction()
      Range(oldv, newv + 1) foreach { v =>
        d("Upgrading to: " + v)
        UPGRADES.getOrElse(v, Seq.empty) foreach db.execSQL
      }

      db.setTransactionSuccessful()
      db.endTransaction()
    }
  }

  private var _servers = listServers
  def servers = _servers
  private def listServers = {
    try {
      val db = getReadableDatabase
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

      val list = Stream.continually(c.moveToNext()).takeWhile (_==true).map {
        _ =>
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
        s
      }.force

      c.close()
      db.close()
      list
    } catch {
      case x: Exception =>
        Toast.makeText(Application.context,
          "Failed to open database", Toast.LENGTH_LONG).show()
        e("Unable to open database", x)
        Seq.empty[Server]
    }
  }

  def addServer(server: Server) {
    val db = getWritableDatabase
    val id = db.insertOrThrow(TABLE_SERVERS, null, server.values)
    server.id = id
    db.close()
    if (id == -1)
      throw new IllegalStateException("Unable to create server")
    _servers = server +: _servers
  }

  def updateServer(server: Server) {
    val db = getWritableDatabase
    val rows = db.update(TABLE_SERVERS, server.values,
      BaseColumns._ID + " = ?", Array[String]("" + server.id))
    db.close()
    if (rows != 1)
      e("Wrong rows updated: " + rows, new StackTrace)
    _servers = server +: (_servers filterNot (_ == server))
  }

  def deleteServer(server: Server) {
    val db = getWritableDatabase
    val rows = db.delete(TABLE_SERVERS,
      BaseColumns._ID + " = ?", Array[String]("" + server.id))
    db.close()
    if (rows != 1)
      e("Wrong rows deleted: " + rows, new StackTrace)
    _servers = _servers filterNot (_ == server)
  }

  def listIgnores = {
    val db = getReadableDatabase
    val c = db.query(TABLE_IGNORES, Array(FIELD_NICKNAME), null, null, null, null, null)
    val list = Stream.continually(c.moveToNext()).takeWhile (_==true).map { _ =>
      c.getString(0)
    } toSet

    c.close()
    db.close()
    list
  }

  def deleteIgnores(ignores: Set[String]): Unit = {
    val db = getWritableDatabase
    db.beginTransaction()
    ignores foreach { i =>
      db.delete(TABLE_IGNORES, s"$FIELD_NICKNAME = ?", Array(i))
    }
    db.setTransactionSuccessful()
    db.endTransaction()
    db.close()
  }

  def addIgnores(ignores: Set[String]): Unit = {
    val db = getWritableDatabase
    val values = new ContentValues
    db.beginTransaction()
    ignores foreach { i =>
      values.put(FIELD_NICKNAME, i)
      db.insertOrThrow(TABLE_IGNORES, null, values)
    }
    db.setTransactionSuccessful()
    db.endTransaction()
    db.close()
  }
}
