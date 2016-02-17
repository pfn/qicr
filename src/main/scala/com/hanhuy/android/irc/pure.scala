package com.hanhuy.android.irc

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view._
import iota.IO

// TODO fix protection level after refactoring
private[irc] trait PureState[T,S] {
  val state: S
  val zero: T
  /** run an IO action, ignore the result, do not update state */
  def apply[A](io: IO[A]): IO[(T,S)] = io map (_ => zero -> state)
  /** run an IO action, ignore the result, update state */
  def applyState(io: IO[S]): IO[(T,S)] = io map (s => zero -> s)
  /** run an IO action, return the result, do not update state */
  def applyResult(io: IO[T]): IO[(T,S)]
}

/** beware:
  * https://youtrack.jetbrains.com/issue/SCL-9888
  * https://issues.scala-lang.org/browse/SI-9658
  */
trait PureActivity[S] extends Activity {
  private[this] var state: S = _
  sealed trait ActivityState[T] extends PureState[T,S]
  trait ActivityStateUnit extends ActivityState[Unit] {
    val zero = ()
    def applyResult(io: IO[Unit]): IO[(Unit,S)] = apply(io)
  }
  trait ActivityStateBoolean extends ActivityState[Boolean] {
    val zero = false
    def applyResult(io: IO[Boolean]): IO[(Boolean,S)] = io map (b => b -> state)
  }
  case class OnCreate(state: S)                        extends ActivityStateUnit
  case class OnDestroy(state: S)                       extends ActivityStateUnit
  case class OnStart(state: S)                         extends ActivityStateUnit
  case class OnStop(state: S)                          extends ActivityStateUnit
  case class OnResume(state: S)                        extends ActivityStateUnit
  case class OnPause(state: S)                         extends ActivityStateUnit
  case class OnCreateOptionsMenu(state: S, menu: Menu) extends ActivityStateBoolean
  case class OnOptionsItemSelected(state: S, item: MenuItem) extends ActivityStateBoolean
  case class TransformState(state: S, oldState: S)     extends ActivityStateUnit
  case class SaveState(state: S, bundle: Bundle)       extends ActivityStateUnit

  def initialState(b: Option[Bundle]): S
  def transformState(f: S => S): IO[Unit] = IO {
    state = applyState(TransformState(f(state),state)).perform()._2
  }

  def applyState[T](s: ActivityState[T]): IO[(T,S)]
  def defaultApplyState[T](s: ActivityState[T]): IO[(T,S)] = IO(s.zero -> s.state)

  final override def onCreate(savedInstanceState: Bundle) = {
    super.onCreate(savedInstanceState)
    state = applyState(OnCreate(initialState(Option(savedInstanceState)))).perform()._2
  }

  final override def onCreateOptionsMenu(m: Menu): Boolean = {
    val b = super.onCreateOptionsMenu(m)
    val (r,st) = applyState(OnCreateOptionsMenu(state, m)).perform()
    state = st
    b || r
  }

  override def onOptionsItemSelected(item: MenuItem) = {
    val (r,st) = applyState(OnOptionsItemSelected(state, item)).perform()
    state = st
    r || super.onOptionsItemSelected(item)
  }

  final override def onSaveInstanceState(outState: Bundle) = {
    super.onSaveInstanceState(outState)
    state = applyState(SaveState(state, outState)).perform()._2
  }

  final override def onStart() = {
    super.onStart()
    state = applyState(OnStart(state)).perform()._2
  }

  final override def onResume() = {
    super.onResume()
    state = applyState(OnResume(state)).perform()._2
  }

  final override def onPause() = {
    super.onPause()
    state = applyState(OnPause(state)).perform()._2
  }

  final override def onStop() = {
    super.onStop()
    state = applyState(OnStop(state)).perform()._2
  }

  final override def onDestroy() = {
    super.onDestroy()
    state = applyState(OnDestroy(state)).perform()._2
  }
}

private[irc] trait PureFragmentBase[S] {
  // TODO fix protection level properly after refactoring into separate library
  private[irc] var state: S = _
  sealed trait FragmentState[T] extends PureState[T,S]
  trait FragmentStateUnit extends FragmentState[Unit] {
    val zero = ()
    def applyResult(io: IO[Unit]): IO[(Unit,S)] = apply(io)
  }
  trait FragmentStateBoolean extends FragmentState[Boolean] {
    val zero = false
    def applyResult(io: IO[Boolean]): IO[(Boolean,S)] = io map (b => b -> state)
  }
  trait FragmentStateView extends FragmentState[View] {
    val zero = null
    def applyResult(io: IO[View]): IO[(View,S)] = io map (b => b -> state)
  }
  case class OnCreate(state: S)                        extends FragmentStateUnit
  case class OnActivityCreated(state: S)               extends FragmentStateUnit
  case class OnViewCreated(state: S)                   extends FragmentStateUnit
  case class OnDestroy(state: S)                       extends FragmentStateUnit
  case class OnStart(state: S)                         extends FragmentStateUnit
  case class OnStop(state: S)                          extends FragmentStateUnit
  case class OnResume(state: S)                        extends FragmentStateUnit
  case class OnPause(state: S)                         extends FragmentStateUnit
  case class OnAttach(state: S)                        extends FragmentStateUnit
  case class OnDetach(state: S)                        extends FragmentStateUnit
  case class OnDestroyView(state: S)                   extends FragmentStateUnit
  case class TransformState(state: S, oldState: S)     extends FragmentStateUnit
  case class SaveState(state: S, bundle: Bundle)       extends FragmentStateUnit
  case class OnCreateView(state: S, inflater: LayoutInflater, container: ViewGroup) extends FragmentStateView
  case class OnCreateOptionsMenu(state: S, menu: Menu, inflater: MenuInflater) extends FragmentStateUnit
  case class OnOptionsItemSelected(state: S, item: MenuItem) extends FragmentStateBoolean

  def initialState(savedState: Option[Bundle], arguments: Option[Bundle]): S
  def transformState(f: S => S): IO[Unit] = IO {
    state = applyState(TransformState(f(state),state)).perform()._2
  }

  def applyState[T](s: FragmentState[T]): IO[(T,S)]
  def defaultApplyState[T](s: FragmentState[T]): IO[(T,S)] = IO(s.zero -> s.state)
}

trait PureFragment[S] extends android.app.Fragment with PureFragmentBase[S] {
  final override def onActivityCreated(savedInstanceState: Bundle) = {
    super.onActivityCreated(savedInstanceState)
    state = applyState(OnActivityCreated(state)).perform()._2
  }

  final override def onAttach(context: Context) = {
    super.onAttach(context)
    state = applyState(OnAttach(state)).perform()._2
  }

  final override def onCreate(savedInstanceState: Bundle) = {
    super.onCreate(savedInstanceState)
    state = applyState(OnCreate(initialState(
      Option(savedInstanceState), Option(getArguments)))).perform()._2
  }

  final override def onCreateOptionsMenu(m: Menu, inflater: MenuInflater): Unit = {
    super.onCreateOptionsMenu(m, inflater)
    state = applyState(OnCreateOptionsMenu(state, m, inflater)).perform()._2
  }

  final override def onCreateView(inflater: LayoutInflater, container: ViewGroup, savedInstanceState: Bundle) = {
    super.onCreateView(inflater, container, savedInstanceState)
    val (view, st) = applyState(OnCreateView(state, inflater, container)).perform()
    state = st
    view
  }

  override def onViewCreated(view: View, savedInstanceState: Bundle) = {
    super.onViewCreated(view, savedInstanceState)
    state = applyState(OnViewCreated(state)).perform()._2
  }

  final override def onDestroy() = {
    super.onDestroy()
    state = applyState(OnDestroy(state)).perform()._2
  }

  final override def onDestroyView() = {
    super.onDestroyView()
    state = applyState(OnDestroyView(state)).perform()._2
  }

  final override def onDetach() = {
    super.onDetach()
    state = applyState(OnDetach(state)).perform()._2
  }

  override def onOptionsItemSelected(item: MenuItem) = {
    val (r,st) = applyState(OnOptionsItemSelected(state, item)).perform()
    state = st
    r || super.onOptionsItemSelected(item)
  }

  final override def onPause() = {
    super.onPause()
    state = applyState(OnPause(state)).perform()._2
  }

  final override def onResume() = {
    super.onResume()
    state = applyState(OnResume(state)).perform()._2
  }

  final override def onSaveInstanceState(outState: Bundle) = {
    super.onSaveInstanceState(outState)
    state = applyState(SaveState(state, outState)).perform()._2
  }

  final override def onStart() = {
    super.onStart()
    state = applyState(OnStart(state)).perform()._2
  }

  final override def onStop() = {
    super.onStop()
    state = applyState(OnStop(state)).perform()._2
  }
}

trait PureFragmentCompat[S] extends android.support.v4.app.Fragment with PureFragmentBase[S] {
  final override def onActivityCreated(savedInstanceState: Bundle) = {
    super.onActivityCreated(savedInstanceState)
    state = applyState(OnActivityCreated(state)).perform()._2
  }

  final override def onAttach(context: Context) = {
    super.onAttach(context)
    state = applyState(OnAttach(state)).perform()._2
  }

  final override def onCreate(savedInstanceState: Bundle) = {
    super.onCreate(savedInstanceState)
    state = applyState(OnCreate(initialState(
      Option(savedInstanceState), Option(getArguments)))).perform()._2
  }

  final override def onCreateOptionsMenu(m: Menu, inflater: MenuInflater): Unit = {
    super.onCreateOptionsMenu(m, inflater)
    state = applyState(OnCreateOptionsMenu(state, m, inflater)).perform()._2
  }

  final override def onCreateView(inflater: LayoutInflater, container: ViewGroup, savedInstanceState: Bundle) = {
    super.onCreateView(inflater, container, savedInstanceState)
    val (view, st) = applyState(OnCreateView(state, inflater, container)).perform()
    state = st
    view
  }

  override def onViewCreated(view: View, savedInstanceState: Bundle) = {
    super.onViewCreated(view, savedInstanceState)
    state = applyState(OnViewCreated(state)).perform()._2
  }

  final override def onDestroy() = {
    super.onDestroy()
    state = applyState(OnDestroy(state)).perform()._2
  }

  final override def onDestroyView() = {
    super.onDestroyView()
    state = applyState(OnDestroyView(state)).perform()._2
  }

  final override def onDetach() = {
    super.onDetach()
    state = applyState(OnDetach(state)).perform()._2
  }

  override def onOptionsItemSelected(item: MenuItem) = {
    val (r,st) = applyState(OnOptionsItemSelected(state, item)).perform()
    state = st
    r || super.onOptionsItemSelected(item)
  }

  final override def onPause() = {
    super.onPause()
    state = applyState(OnPause(state)).perform()._2
  }

  final override def onResume() = {
    super.onResume()
    state = applyState(OnResume(state)).perform()._2
  }

  final override def onSaveInstanceState(outState: Bundle) = {
    super.onSaveInstanceState(outState)
    state = applyState(SaveState(state, outState)).perform()._2
  }

  final override def onStart() = {
    super.onStart()
    state = applyState(OnStart(state)).perform()._2
  }

  final override def onStop() = {
    super.onStop()
    state = applyState(OnStop(state)).perform()._2
  }
}
