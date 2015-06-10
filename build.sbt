platformTarget in Android := "android-22"

scalaVersion in Global := "2.11.6"

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
  "com.hanhuy.android" %% "scala-conversions" % "1.1",
  "com.hanhuy.android" %% "scala-common" % "1.0-SNAPSHOT",
  "com.hanhuy" % "sirc" % "1.1.6-SNAPSHOT",
  "ch.acra" % "acra" % "4.5.0",
  apklib("com.viewpagerindicator" % "library" % "2.4.1"),
  "com.android.support" % "support-v4" % "21.0.0",
  "com.android.support" % "appcompat-v7" % "21.0.0")

javacOptions in Compile ++= Seq("-target", "1.6", "-source", "1.6")

proguardOptions in Android += "-keep class android.support.v7.widget.SearchView { <init>(...); }"

proguardOptions in Android += "-keep class android.support.v7.internal.widget.* { <init>(...); }"

proguardCache in Android ++= "macroid" :: "android.support" :: Nil

extraResDirectories in (lite,Android) += baseDirectory.value / "src" / "lite" / "res"

packageName in (lite,Android) := "com.hanhuy.android.irc.lite"

run <<= run in (lite,Android)
