import sbt._
import sbt.Keys._

import android.Keys._
import android.Dependencies.apklib

object QicrBuild extends android.AutoBuild {
  lazy val root = Project(id = "qicr", base = file(".")) settings(Seq(
    packageT in Compile    <<= packageT in Android in lite,
    packageRelease         <<= packageRelease in Android in lite,
    packageDebug           <<= packageDebug in Android in lite,
    run                    <<= run in Android in lite,
    packageName in Android  := "com.hanhuy.android.irc" // for pidcat
  ): _*) aggregate(lite, common)

  lazy val lite = Project(id = "lite", base = file("lite")) androidBuildWith common settings(
    sourceGenerators in Compile <<= (sourceGenerators in Compile) (g => Seq(g.last)),
    scalaVersion := "2.11.2",
    transitiveAndroidLibs in Android := false,
    unmanagedClasspath in Compile ~= { _ filterNot (
      _.data.getName startsWith "android-support-v4") },
    resolvers += Resolver.sonatypeRepo("snapshots"),
    proguardCache in Android += ProguardCache("macroid") % "org.macroid",
    proguardScala in Android := true
    )

  lazy val common = Project(id = "common", base = file("common")) settings(Seq(
    scalaVersion := "2.11.2",
    scalacOptions in Compile += "-deprecation",
    javacOptions in Compile  += "-deprecation",
    unmanagedClasspath in Compile ~= { _ filterNot (
      _.data.getName startsWith "android-support-v4") },

    resolvers += Resolver.sonatypeRepo("snapshots"),
    resolvers += "bintray" at "http://jcenter.bintray.com",
    libraryDependencies ++= Seq(
      "org.macroid" %% "macroid" % "2.0.0-M3",
      "com.hanhuy" %% "android-common" % "0.3-SNAPSHOT",
      "com.sorcix" % "sirc" % "1.1.5",
      "ch.acra" % "acra" % "4.5.0",
      apklib("com.viewpagerindicator" % "library" % "2.4.1"),
      "com.android.support" % "support-v4" % "20.0.0",
      "com.android.support" % "appcompat-v7" % "19.1.0")
  ): _*)
}
