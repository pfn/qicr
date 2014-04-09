import sbt._
import sbt.Keys._

import android.Keys._

object QicrBuild extends Build {
  lazy val root = Project(id = "qicr", base = file(".")) settings(Seq(
      packageT in Compile <<= packageT in Android in lite,
      packageRelease      <<= packageRelease in Android in lite,
      packageDebug        <<= packageDebug in Android in lite,
      run                 <<= run in Android in lite
    ) ++ android.Plugin.androidCommands: _*
  ) aggregate(lite, common)

  lazy val lite = Project(id = "lite", base = file("lite")) settings(
      android.Plugin.androidBuild(common) ++ Seq(
        //proguardOptions in Android ++= Seq("-dontoptimize"),
        proguardScala in Android := true
      ): _*) dependsOn(common)

  lazy val common = Project(id = "common", base = file("common")) settings(Seq(
    scalacOptions in Compile += "-deprecation",
    javacOptions in Compile  += "-deprecation",

    libraryDependencies ++= Seq(
      "com.sorcix" % "sirc" % "1.1.5",
      "com.viewpagerindicator" % "library" % "2.4.1" % "provided",
      "com.android.support" % "support-v4" % "18.0.0",
      "com.android.support" % "appcompat-v7" % "18.0.0")
  ) ++ android.Plugin.androidBuild: _*)
}
