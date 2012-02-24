import sbt._
import AndroidKeys._
object QicrBuild extends Build {
    lazy val root = Project(id = "qicr", base = file("."),
            settings = Defaults.defaultSettings :+
                    (packageT in Compile <<=
                            packageT in Android in lite)
                    ) aggregate(lite, common)
    lazy val lite = Project(
            id = "lite", base = file("lite"),
            settings = Defaults.defaultSettings :+
                    (packageT in Compile <<= packageT in Compile dependsOn(
                            packageT in Compile in common))
            ) dependsOn(common)
    lazy val common = Project(id = "common", base = file("common"))
}
