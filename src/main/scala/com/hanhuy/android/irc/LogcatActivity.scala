package com.hanhuy.android.irc

import android.graphics.Typeface
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.support.v7.widget.{Toolbar, LinearLayoutManager, RecyclerView}
import android.view.ViewGroup
import android.widget.{TextView, LinearLayout}
import com.hanhuy.android.irc.model.{MessageAdapter, RingBuffer}

import scala.concurrent.Future
import sys.process._
import java.lang.{Process => _}

import com.hanhuy.android.common._
import com.hanhuy.android.appcompat.extensions._
import iota._
import Tweaks._
import Futures._
import SpannedGenerator._
import ViewGroup.LayoutParams._

/**
  * @author pfnguyen
  */
class LogcatActivity extends AppCompatActivity with PureActivity[Option[Process]] {
  val LOG_LINE = """^([A-Z])/(.+?)\( *(\d+)\): (.*?)$""".r
  val buffersize = 1024
  lazy val toolbar = newToolbar
  lazy val recycler = {
    val r = new RecyclerView(this)
    r.setLayoutManager(new LinearLayoutManager(this))
    r.setAdapter(Adapter)
    r
  }
  lazy val layout = l[LinearLayout](
    toolbar.!  >>= lp(MATCH_PARENT, WRAP_CONTENT),
    recycler.! >>= lp(MATCH_PARENT, 0, 1)
  ) >>= vertical

  override def initialState(b: Option[Bundle]) = None

  override def applyState[T](s: ActivityState[T]) = s match {
    case OnCreate(_) => s(IO {
      setTheme(if (Settings.get(Settings.DAYNIGHT_MODE)) R.style.SetupTheme_Light else R.style.SetupTheme_Dark)
      toolbar.setTitle("Logcat")
      toolbar.setNavigationIcon(resolveAttr(R.attr.qicrCloseIcon, _.resourceId))
      toolbar.navigationOnClick0(finish())
      setContentView(layout.perform())
    })
    case OnStart(_) => s.applyState(IO {
      var buffering = true
      val logcat = "logcat" :: "-v" :: "brief" :: Nil
      val lineLogger = new ProcessLogger {
        override def out(s: => String) = addLine(s)
        override def buffer[X](f: => X) = f
        override def err(s: => String) = addLine(s)

        def addLine(line: String) = line match {
          case LOG_LINE(level, tag, pid, msg) =>
            if (tag != "ResourceType") UiBus.run {
              val c = Adapter.getItemCount // store in case at max items already
              Adapter.buffer += LogEntry(tag, level, msg)
              Adapter.notifyItemInserted(math.min(buffersize, c + 1))
              if (!buffering)
                recycler.smoothScrollToPosition(Adapter.getItemCount)
            }
          case _ =>
        }
      }
      Future {
        Thread.sleep(500)
        buffering = false
      } onSuccessMain { case _ =>
        recycler.scrollToPosition(Adapter.getItemCount - 1)
      }
      logcat.run(lineLogger).?
    })
    case OnStop(proc) => s.applyState(IO {
      proc.foreach(_.destroy())
      None
    })
    case x => defaultApplyState(x)
  }

  case class LogEntry(tag: String, level: String, msg: String)
  case class LogcatHolder(view: TextView) extends RecyclerView.ViewHolder(view) {
    def bind(e: LogEntry): Unit = {
      view.setText(" %1 %2: %3" formatSpans (
        textColor(MessageAdapter.nickColor(e.level), e.level),
        textColor(MessageAdapter.nickColor(e.tag),   e.tag), e.msg))
    }
  }
  object Adapter extends RecyclerView.Adapter[LogcatHolder] {
    val buffer = RingBuffer[LogEntry](buffersize)
    override def getItemCount = buffer.size
    override def onBindViewHolder(vh: LogcatHolder, i: Int) = vh.bind(buffer(i))

    override def onCreateViewHolder(viewGroup: ViewGroup, i: Int) = {
      val tv = new TextView(LogcatActivity.this)
      tv.setTypeface(Typeface.MONOSPACE)
      LogcatHolder(tv)
    }
  }
}
