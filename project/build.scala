import sbt._
import sbt.Keys._

import android.Keys._

object QicrBuild extends Build {
  lazy val root = Project(id = "qicr", base = file(".")) settings(
    organization         := "com.hanhuy.android",
    packageT in Compile <<= packageT in Android in lite,
    packageRelease      <<= packageRelease in Android in lite,
    packageDebug        <<= packageDebug in Android in lite
  ) aggregate(lite, common, sirc)

  lazy val sirc = RootProject(uri("https://github.com/sorcix/sIRC.git#7fa7cc7"))

  override def settings = super.settings ++
    Seq(exportJars in (sirc,Compile) := true) ++
      inScope(Global in sirc) (org.sbtidea.SbtIdeaPlugin.settings)

  lazy val lite = Project(id = "lite", base = file("lite")) settings(
      android.Plugin.androidBuild(common) ++ Seq(
        organization                := "com.hanhuy.android",
        proguardOptions in Android ++= Seq("-dontobfuscate", "-dontoptimize",
          "-dontwarn android.support.**"),
        proguardScala in Android := true
      ): _*) dependsOn(common)

  lazy val common = Project(id = "common", base = file("common")) settings(Seq(
    scalacOptions in Compile += "-deprecation",
    organization             := "com.hanhuy.android",
    javacOptions in Compile  += "-deprecation",
    // can get rid of mavenLocal after sirc is in central
    // https://github.com/sorcix/sIRC/issues/4
    resolvers += Resolver.mavenLocal,
    // mvn install sirc locally to use the below dep
    libraryDependencies ++= Seq(
      "com.android.support" % "support-v4" % "18.0.0",
      "com.android.support" % "appcompat-v7" % "18.0.0")
  ) ++ android.Plugin.androidBuild: _*) dependsOn(sirc)
}
