import AndroidKeys._

name := "qicr-common"

scalaVersion := "2.8.2"

seq(androidBuildSettings: _*)

aaptNonConstantId in Android := false
