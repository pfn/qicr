import android.dsl._

dexInProcess := false

versionName := {
  import com.typesafe.sbt.SbtGit.GitKeys.gitReader
  gitReader.value.withGit(_.describedVersion)
}

versionCode := {
  versionName.value.map { v =>
    val dashIdx = v.indexOf("-")
    val commit = if (dashIdx != -1)
      v.substring(dashIdx + 1, v.indexOf("-", dashIdx + 1)).toInt
    else 0
    val parts = v.takeWhile(_ != '-') split "\\." map (p =>
      util.Try(p.toInt).toOption.getOrElse(0))
    parts.reverse.foldLeft((1000000000,1)) { case ((ac,mult),x) =>
      (ac + x * math.max(10,mult), mult * 1000)
    }._1 + math.min(9,commit) // commits beyond 9 do not move versionCode
  }
}


val supportSdkVersion = "23.4.0"

val conversionsVersion = "23.2.0"

platformTarget := "android-23"

scalaVersion in Global := "2.11.7"

scalacOptions in Compile ++= Seq("-deprecation", "-Xexperimental")

javacOptions in Compile ++= Seq("-target", "1.6", "-source", "1.6")

javacOptions in Compile  += "-deprecation"

unmanagedJars in Compile ~= { _ filterNot (
  _.data.getName startsWith "android-support-v4") }

libraryDependencies ++= Seq(
  "com.hanhuy.android" %% "scala-conversions" % conversionsVersion,
  "com.hanhuy.android" %% "scala-conversions-appcompat" % conversionsVersion,
  "com.hanhuy.android" %% "scala-common" % "1.3",
  "com.hanhuy.android" %% "iota" % "1.0.4",
  "com.hanhuy.android" %% "iota-pure" % "0.1",
  "com.hanhuy" % "sirc" % "1.1.6-pfn.2",
  "ch.acra" % "acra" % "4.8.2",
  "com.lihaoyi" %% "scalarx" % "0.3.0",
  "com.android.support" % "cardview-v7" % supportSdkVersion,
  "com.android.support" % "recyclerview-v7" % supportSdkVersion,
  "com.android.support" % "design" % supportSdkVersion,
  "com.android.support" % "support-v4" % supportSdkVersion,
  "com.android.support" % "preference-v7" % supportSdkVersion,
  "com.android.support" % "preference-v14" % supportSdkVersion,
  "com.android.support" % "appcompat-v7" % supportSdkVersion)

proguardOptions ++=
  "-keep class android.support.v7.widget.SearchView { <init>(...); }" ::
  "-keep class android.support.v7.internal.widget.* { <init>(...); }" ::
  "-keep class scala.runtime.BoxesRunTime { *; }" :: // for debugging
  "-dontwarn iota.Internal210**" ::
  Nil

applicationId := "com.hanhuy.android.irc.lite"

resValue("string", "app_name", "qicr lite")

resValue("bool", "debug_build", Def.task { apkbuildDebug.value().toString })

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
