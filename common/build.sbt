import AndroidKeys._

name := "qicr-common"

scalaVersion := "2.8.1"

seq(androidBuildSettings: _*)

aaptNonConstantId in Android := false
