package com.hanhuy.android.irc

import android.app.Activity
import android.content.Intent
import android.support.v4.app.Fragment
import com.hanhuy.android.common.Logcat

import scala.concurrent.{Future, Promise}

/**
  * @author pfnguyen
  */
trait ActivityResultManager extends Activity {
  case object ActivityResultCancel extends Exception
  private[this] var _requestCode = 0
  def requestCode = {
    _requestCode = _requestCode + 1
    _requestCode % 0xff
  }
  private[this] val arlog = Logcat("ActivityResultManager")
  private[this] var results = Map.empty[Int,Promise[Intent]]
  final override def onActivityResult(request: Int,
                                      resultCode: Int,
                                      data: Intent): Unit = {
    if (results.contains(request)) {
      if (resultCode != Activity.RESULT_OK) {
        results(request).failure(ActivityResultCancel)
      } else {
        results(request).success(data)
      }
      results -= request
    } else {
      super.onActivityResult(request, resultCode, data)
      arlog.w(s"Request code $request not found: " + data)
    }
  }
  final def requestActivityResult(intent: Intent): Future[Intent] = {
    val req = requestCode
    val p = results.getOrElse(req, Promise[Intent]())
    results += ((req, p))
    super.startActivityForResult(intent, req)
    p.future
  }
}
trait FragmentResultManager extends Fragment {
  case object ActivityResultCancel extends Exception
  private[this] var _requestCode = 0
  def requestCode = {
    _requestCode = _requestCode + 1
    _requestCode % 0xff
  }
  private[this] val arlog = Logcat("ActivityResultManager")
  private[this] var results = Map.empty[Int,Promise[Intent]]
  final override def onActivityResult(request: Int,
                                      resultCode: Int,
                                      data: Intent): Unit = {
    if (results.contains(request)) {
      if (resultCode != Activity.RESULT_OK) {
        results(request).failure(ActivityResultCancel)
      } else {
        results(request).success(data)
      }
      results -= request
    } else {
      super.onActivityResult(request, resultCode, data)
      arlog.w(s"Request code $request not found: " + data)
    }
  }
  final def requestActivityResult(intent: Intent): Future[Intent] = {
    val req = requestCode
    val p = results.getOrElse(req, Promise[Intent]())
    results += ((req, p))
    super.startActivityForResult(intent, req)
    p.future
  }
}
