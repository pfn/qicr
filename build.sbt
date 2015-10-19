val supportSdkVersion = "23.1.0"

platformTarget in Android := "android-23"

scalaVersion in Global := "2.11.7"

scalacOptions in Compile ++= Seq("-deprecation", "-Xexperimental")

javacOptions in Compile ++= Seq("-target", "1.6", "-source", "1.6")

javacOptions in Compile  += "-deprecation"

unmanagedJars in Compile ~= { _ filterNot (
  _.data.getName startsWith "android-support-v4") }

resolvers += Resolver.sonatypeRepo("snapshots")

resolvers += "bintray" at "http://jcenter.bintray.com"

resolvers += Resolver.sonatypeRepo("snapshots")

libraryDependencies ++= Seq(
  "org.macroid" %% "macroid" % "2.0.0-M4",
  "com.hanhuy.android" %% "scala-conversions" % "1.6",
  "com.hanhuy.android" %% "scala-conversions-design" % "1.6",
  "com.hanhuy.android" %% "scala-common" % "1.1",
  "com.hanhuy" % "sirc" % "1.1.6-SNAPSHOT",
  "ch.acra" % "acra" % "4.7.0-RC.1",
  "com.hanhuy.android" % "viewserver" % "1.0.3",
  "com.android.support" % "design" % supportSdkVersion,
  "com.android.support" % "support-v4" % supportSdkVersion,
  "com.android.support" % "appcompat-v7" % supportSdkVersion)

javacOptions in Compile ++= Seq("-target", "1.6", "-source", "1.6")

proguardOptions in Android += "-keep class android.support.v7.widget.SearchView { <init>(...); }"

proguardOptions in Android += "-keep class android.support.v7.internal.widget.* { <init>(...); }"

proguardOptions in Android += "-keep class scala.runtime.BoxesRunTime { *; }" // for debugging

proguardCache in Android ++= "macroid" :: "android.support" :: Nil

proguardScala := true

useProguard := true

useProguardInDebug in Android := false

extraResDirectories in Android += baseDirectory.value / "src" / "lite" / "res"

applicationId in Android := "com.hanhuy.android.irc.lite"

run <<= run in Android

dexMaxHeap := "3g"

protifySettings
