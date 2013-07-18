import sbt._
import sbt.Keys._

import android.Keys._

object QicrBuild extends Build {
  lazy val root = Project(id = "qicr", base = file(".")) settings(
    organization         := "com.hanhuy.android",
    packageT in Compile <<= packageT in Android in lite,
    packageRelease      <<= packageRelease in Android in lite,
    packageDebug        <<= packageDebug in Android in lite
  ) aggregate(lite, common)

  lazy val lite = Project(id = "lite", base = file("lite")) settings(
      android.Plugin.androidBuild(common) ++ Seq(
        organization         := "com.hanhuy.android",
        proguardOptions in Android ++= Seq("-dontobfuscate", "-dontoptimize"),
        proguardScala in Android := true
      ): _*) dependsOn(common)

  lazy val common = Project(id = "common", base = file("common")) settings(Seq(
    scalacOptions in Compile += "-deprecation",
    organization         := "com.hanhuy.android",
    javacOptions in Compile += "-deprecation",
    libraryDependencies += "com.android.support" % "support-v4" % "13.0.0"
  ) ++ android.Plugin.androidBuild: _*)
}
