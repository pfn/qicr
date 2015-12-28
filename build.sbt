import android.dsl._

val supportSdkVersion = "23.1.1"

platformTarget := "android-23"

scalaVersion in Global := "2.11.7"

scalacOptions in Compile ++= Seq("-deprecation", "-Xexperimental")

javacOptions in Compile ++= Seq("-target", "1.6", "-source", "1.6")

javacOptions in Compile  += "-deprecation"

unmanagedJars in Compile ~= { _ filterNot (
  _.data.getName startsWith "android-support-v4") }

libraryDependencies ++= Seq(
  "com.hanhuy.android" %% "scala-conversions" % "1.6",
  "com.hanhuy.android" %% "scala-conversions-appcompat" % "1.6",
  "com.hanhuy.android" %% "scala-conversions-design" % "1.6",
  "com.hanhuy.android" %% "scala-common" % "1.1",
  "com.hanhuy.android" %% "iota" % "0.7",
  "com.hanhuy" % "sirc" % "1.1.6-pfn.1",
  "ch.acra" % "acra" % "4.7.0",
  "com.hanhuy.android" % "viewserver" % "1.0.3",
  "com.android.support" % "design" % supportSdkVersion,
  "com.android.support" % "support-v4" % supportSdkVersion,
  "com.android.support" % "appcompat-v7" % supportSdkVersion)

proguardOptions ++=
  "-keep class android.support.v7.widget.SearchView { <init>(...); }" ::
  "-keep class android.support.v7.internal.widget.* { <init>(...); }" ::
  "-keep class scala.runtime.BoxesRunTime { *; }" :: // for debugging
  "-dontwarn iota.**" ::
  Nil

applicationId := "com.hanhuy.android.irc.lite"

resValue("string", "app_name", "qicr lite")

run <<= run in Android

dexMaxHeap := "3g"

flavors += (("no-protify", Seq(
  apkSigningConfig := Some(android.DebugSigningConfig()),
  apkbuildDebug := { val d = apkbuildDebug.value; d(false); d }
)))

// delete vectordrawables because they break moto display
collectResources := {
  val (assets,res) = collectResources.value
  IO.delete(res / "drawable-anydpi-v21")
  (assets,res)
}

useProguardInDebug := false

protifySettings
