import sbt._
import sbt.Keys._

import AndroidKeys._

object QicrBuild extends Build {
  lazy val root = Project(id = "qicr", base = file(".")) settings(
    compile in Compile  <<= compile in Compile in lite,
    packageT in Compile <<= packageT in Android in lite,
    packageRelease      <<= packageRelease in Android in lite,
    packageDebug        <<= packageDebug in Android in lite
  ) aggregate(lite, common)
  lazy val lite = Project(id = "lite", base = file("lite")) settings(
      compile in Compile <<= compile in Compile dependsOn(
        packageT in Compile in common)
  ) dependsOn(common)
  lazy val common = Project(id = "common", base = file("common"))
}
