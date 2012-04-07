import AndroidKeys._

scalaVersion := "2.8.2"

name := "qicr-lite"

seq(androidBuildSettings: _*)

proguardScala in Android := true

proguardOptions in Android ++= Seq("-dontobfuscate", "-dontoptimize")

aaptNonConstantId in Android := false
