package com.hanhuy.android.irc

import android.content.ContentValues
import com.hanhuy.android.common._
import com.hanhuy.android.irc.model.BusEvent.IgnoreListChanged
import com.hanhuy.android.irc.model.Server
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteDatabase
import android.provider.BaseColumns
import android.widget.Toast
import Config._
import rx.Var

object Config {
  val log = Logcat("QicrConfig")
  // TODO encrypt the password with pbkdf2 on primary account username + salt
  val DATABASE_VERSION = 3
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
       |  $FIELD_USESASL      INTEGER NOT NULL
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

  val FIELD_CHANNEL = "channel"
  val FIELD_SERVER = "server"
  val TABLE_FAVORITES = "favorites"
  val TABLE_FAVORITES_DDL =
    s"""
       |CREATE TABLE $TABLE_FAVORITES (
       |  $FIELD_CHANNEL TEXT NOT NULL COLLATE NOCASE,
       |  $FIELD_SERVER TEXT NOT NULL COLLATE NOCASE,
       |  CONSTRAINT unq_favorite UNIQUE ($FIELD_CHANNEL,$FIELD_SERVER)
       |);
     """.stripMargin

  case class CaseInsensitiveSet(underlying: Set[String]) extends Set[String] {
    lazy val uppers = underlying.map(_.toUpperCase)
    lazy val distinct: Set[String] =
      underlying.foldLeft((Set.empty[String],Set.empty[String])) {
        case ((ss,lc),s) =>
          if (lc(s.toUpperCase)) (ss,lc) else (ss + s, lc + s.toUpperCase)
      }._1
    override def contains(s: String) = uppers.contains(s.toUpperCase)
    override def iterator = distinct.iterator
    override def +(s: String) = CaseInsensitiveSet(distinct + s)
    override def -(s: String) = CaseInsensitiveSet(
      distinct.filterNot(_.toUpperCase == s.toUpperCase))
  }
  object Ignores {
    private lazy val initialIgnores = CaseInsensitiveSet(instance.listIgnores)
    private var _ignored = Option.empty[CaseInsensitiveSet]
    private def ignored = _ignored getOrElse initialIgnores

    def size = ignored.size
    def isEmpty = ignored.isEmpty
    def mkString(s: String) = ignored.mkString(s)
    def map[A](f: String => A) = ignored.map(f)

    def apply(s: String) = ignored(s)

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
      _ignored = Some(CaseInsensitiveSet(Set.empty))
    }
  }

  case class Favorite(channel: String, server: String) {
    override def equals(other: Any) = other match {
      case Favorite(c, s) =>
        c.equalsIgnoreCase(channel) && s.equalsIgnoreCase(server)
      case _ => false
    }
    override def hashCode =
      channel.toLowerCase.hashCode + server.toLowerCase.hashCode
  }
  object Favorites {
    import model.ChannelLike
    import model.Channel
    private lazy val initialFavorites = instance.listFavorites
    private var _favorited = Option.empty[Set[Favorite]]
    private def favorited = _favorited getOrElse initialFavorites

    def size = favorited.size
    def isEmpty = favorited.isEmpty
    def mkString(s: String) = favorited.mkString(s)
    def map[A](f: Favorite => A) = favorited.map(f)

    def apply(c: ChannelLike) = favorited(Favorite(c.name, c.server.name))

    def +=(n: Channel) = {
      val newFavorites = favorited + Favorite(n.name, n.server.name)
      val change = newFavorites -- favorited
      instance.addFavorites(change)
      _favorited = Some(newFavorites)
    }

    def -=(n: Channel) = {
      val newFavorites = favorited - Favorite(n.name, n.server.name)
      val changed = favorited -- newFavorites
      instance.deleteFavorites(changed)
      _favorited = Some(newFavorites)
    }

    def clear(): Unit = {
      instance.deleteFavorites(favorited)
      _favorited = Some(Set.empty)
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
    db.execSQL(TABLE_FAVORITES_DDL)
    db.setTransactionSuccessful()
    db.endTransaction()
  }
  val UPGRADES: Map[Int,Seq[String]] = Map(
    2 -> Seq(TABLE_IGNORES_DDL),
    3 -> Seq(TABLE_FAVORITES_DDL)
  )

  override def onUpgrade(db: SQLiteDatabase, oldv: Int, newv: Int) {
    if (oldv < newv) {
      db.beginTransaction()
      (oldv + 1) to newv foreach { v =>
        log.d("Upgrading to: " + v)
        UPGRADES.getOrElse(v, Seq.empty) foreach db.execSQL
      }

      db.setTransactionSuccessful()
      db.endTransaction()
    }
  }

  val servers = Var(listServers)
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
      val col_usesasl     = c.getColumnIndexOrThrow(FIELD_USESASL)

      val list = Iterator.continually(c.moveToNext()).takeWhile (_==true).map {
        _ =>
        val s = new Server
        s.data = Server.ServerData(
          c.getLong(col_id),
          c.getString(col_name),
          c.getString(col_hostname),
          c.getString(col_nickname),
          c.getString(col_altnick),
          c.getInt(col_port),
          c.getString(col_realname),
          c.getString(col_username),
          c.getInt(col_ssl) != 0,
          c.getInt(col_autoconnect) != 0,
          c.getString(col_password).?.filter(_.trim.nonEmpty),
          c.getInt(col_usesasl) != 0,
          c.getString(col_autorun).?.filter(_.trim.nonEmpty),
          c.getString(col_autojoin).?.filter(_.trim.nonEmpty)
        )
        s
      }.toList

      c.close()
      db.close()
      list
    } catch {
      case x: Exception =>
        Toast.makeText(Application.context,
          "Failed to open database", Toast.LENGTH_LONG).show()
        log.e("Unable to open database", x)
        Nil
    }
  }

  def addServer(data: Server.ServerData) = {
    val db = getWritableDatabase
    val id = db.insertOrThrow(TABLE_SERVERS, null, data.values)
    val server = new Server
    server.data = data.copy(id = id)
    db.close()
    if (id == -1)
      throw new IllegalStateException("Unable to create server")
    servers() = server :: servers.now
    server
  }

  def updateServer(data: Server.ServerData) = {
    val db = getWritableDatabase
    val rows = db.update(TABLE_SERVERS, data.values,
      BaseColumns._ID + " = ?", Array[String]("" + data.id))
    db.close()
    val server = servers.now.find(_.id == data.id).get
    server.data = data
    if (rows != 1)
      log.e("Wrong rows updated: " + rows, new StackTrace)
    server
  }

  def deleteServer(server: Server) {
    val db = getWritableDatabase
    val rows = db.delete(TABLE_SERVERS,
      BaseColumns._ID + " = ?", Array[String]("" + server.id))
    db.close()
    if (rows != 1)
      log.e("Wrong rows deleted: " + rows, new StackTrace)
    servers() = servers.now filterNot (_ == server)
  }

  def listIgnores = {
    val db = getReadableDatabase
    val c = db.query(TABLE_IGNORES, Array(FIELD_NICKNAME), null, null, null, null, null)
    val list = Iterator.continually(c.moveToNext()).takeWhile (_==true).map { _ =>
      c.getString(0)
    } toSet

    c.close()
    db.close()
    list
  }

  def listFavorites: Set[Favorite] = {
    val db = getReadableDatabase
    val c = db.query(TABLE_FAVORITES, Array(FIELD_CHANNEL, FIELD_SERVER), null, null, null, null, null)
    val list = Iterator.continually(c.moveToNext()).takeWhile (_==true).map { _ =>
      Favorite(c.getString(0), c.getString(1))
    } toSet

    c.close()
    db.close()
    list
  }

  def deleteIgnores(ignores: Set[String]): Unit = {
    val db = getWritableDatabase
    db.beginTransaction()
    ignores foreach { i =>
      db.delete(TABLE_IGNORES, s"UPPER($FIELD_NICKNAME) = ?", Array(i.toUpperCase))
    }
    db.setTransactionSuccessful()
    db.endTransaction()
    db.close()
  }

  def deleteFavorites(favorites: Set[Favorite]): Unit = {
    val db = getWritableDatabase
    db.beginTransaction()
    favorites foreach { case Favorite(c, s) =>
      db.delete(TABLE_FAVORITES, s"$FIELD_CHANNEL = ? AND $FIELD_SERVER = ?",
        Array(c, s))
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
  def addFavorites(favorites: Set[Favorite]): Unit = {
    val db = getWritableDatabase
    val values = new ContentValues
    db.beginTransaction()
    favorites foreach { case Favorite(c, s) =>
      values.put(FIELD_CHANNEL, c)
      values.put(FIELD_SERVER, s)
      db.insertOrThrow(TABLE_FAVORITES, null, values)
    }
    db.setTransactionSuccessful()
    db.endTransaction()
    db.close()
  }
}
