resolvers += Resolver.url("scala-sbt releases", new URL(
  "http://scalasbt.artifactoryonline.com/scalasbt/sbt-plugin-releases/"))(
  Resolver.ivyStylePatterns)

addSbtPlugin("com.hanhuy.sbt" % "android-sdk-plugin" % "0.3.0")
