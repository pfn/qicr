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
    resolvers += Resolver.sonatypeRepo("snapshots"),
    proguardOptions in Android += "-keep class android.support.v7.widget.SearchView { <init>(...); }",
    proguardOptions in Android += "-keep class android.support.v7.internal.widget.* { <init>(...); }",
    proguardCache in Android += ProguardCache("macroid") % "org.macroid",
    proguardCache in Android += ProguardCache("android.support") % "com.android.support",
    proguardScala in Android := true
    )

  lazy val common = Project(id = "common", base = file("common")) settings(Seq(
    scalaVersion := "2.11.2",
    scalacOptions in Compile ++= Seq("-deprecation", "-Xexperimental"),
    javacOptions in Compile  += "-deprecation",
    unmanagedJars in Compile ~= { _ filterNot (
      _.data.getName startsWith "android-support-v4") },

    resolvers += Resolver.sonatypeRepo("snapshots"),
    resolvers += "bintray" at "http://jcenter.bintray.com",
    libraryDependencies ++= Seq(
      "org.macroid" %% "macroid" % "2.0.0-M3",
      "com.hanhuy" %% "android-common" % "0.3-SNAPSHOT",
      "com.hanhuy" % "sirc" % "1.1.6-SNAPSHOT",
      "ch.acra" % "acra" % "4.5.0",
      apklib("com.viewpagerindicator" % "library" % "2.4.1"),
      "com.android.support" % "support-v4" % "21.0.0",
      "com.android.support" % "appcompat-v7" % "21.0.0")
  ): _*)
}
