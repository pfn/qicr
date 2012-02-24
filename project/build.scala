import sbt._
import AndroidKeys._
object QicrBuild extends Build {
    lazy val root = Project(id = "qicr", base = file("."),
            settings = Defaults.defaultSettings :+
                    (packageK in Compile <<=
                            packageK in Android in lite map identity)
                    ) aggregate(lite, common)
    lazy val lite = Project(
            id = "lite", base = file("lite"),
            settings = Defaults.defaultSettings :+
                    (packageK in Compile <<= packageK in Compile dependsOn(
                            packageK in Compile in common))
            ) dependsOn(common)
    lazy val common = Project(id = "common", base = file("common"))
}
