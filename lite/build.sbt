import AndroidKeys._

scalaVersion := "2.8.1"

name := "qicr-lite"

seq(androidBuildSettings: _*)

proguardScala in Android := true

proguardOptions in Android ++= Seq("-dontobfuscate", "-dontoptimize")
