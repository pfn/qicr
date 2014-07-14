import sbt._
import sbt.Keys._

import android.Keys._
import android.Dependencies.{apklib,LibraryProject}

object QicrBuild extends android.AutoBuild {
  lazy val root = Project(id = "qicr", base = file(".")) settings(Seq(
      scalaVersion         := "2.9.2",
      packageT in Compile <<= packageT in Android in lite,
      packageRelease      <<= packageRelease in Android in lite,
      packageDebug        <<= packageDebug in Android in lite,
      run                 <<= run in Android in lite
    ) ++ android.Plugin.androidCommands: _*
  ) aggregate(lite, common)

  lazy val lite = Project(id = "lite", base = file("lite")) settings(
      android.Plugin.androidBuild(common) ++ Seq(
        transitiveAndroidLibs in Android := false,
        dependencyClasspath in Compile ~= { _ filterNot (
          _.data.getName startsWith "android-support-v4") },
        scalaVersion         := "2.9.2",
        localProjects in Android := Seq(LibraryProject(common.base)),
        proguardScala in Android := true
      ): _*) dependsOn common

  lazy val common = Project(id = "common", base = file("common")) settings(Seq(
    scalacOptions in Compile += "-deprecation",
    javacOptions in Compile  += "-deprecation",
    scalaVersion         := "2.9.2",
    dependencyClasspath in Compile ~= { _ filterNot (
      _.data.getName startsWith "android-support-v4") },

    libraryDependencies ++= Seq(
      "com.hanhuy" %% "android-common" % "0.1",
      "com.sorcix" % "sirc" % "1.1.5",
      "ch.acra" % "acra" % "4.5.0",
      apklib("com.viewpagerindicator" % "library" % "2.4.1"),
      "com.android.support" % "support-v4" % "19.1.0",
      "com.android.support" % "appcompat-v7" % "19.1.0")
  ): _*)
}
