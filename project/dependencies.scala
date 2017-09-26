import sbt._

object Dependencies {

  val scalaGitVersion = "3.4"

  val jgitVersionOverride = Option(System.getProperty("jgit.version"))

  val jgitVersion = jgitVersionOverride.getOrElse("4.4.1.201607150455-r")

  val jgit = "org.eclipse.jgit" % "org.eclipse.jgit" % jgitVersion

  // the 1.7.2 here matches slf4j-api in jgit's dependencies
  val slf4jSimple = "org.slf4j" % "slf4j-simple" % "1.7.2"

  val scalaGit = "com.madgag.scala-git" %% "scala-git" % scalaGitVersion exclude("org.eclipse.jgit", "org.eclipse.jgit")

  val scalaGitTest = "com.madgag.scala-git" %% "scala-git-test" % scalaGitVersion

  val scalatest = "org.scalatest" %% "scalatest" % "3.0.4"

  val madgagCompress = "com.madgag" % "util-compress" % "1.33"

  val textmatching = "com.madgag" %% "scala-textmatching" % "2.0"

  val scopt = "com.github.scopt" %% "scopt" % "3.5.0"

  val guava = Seq("com.google.guava" % "guava" % "19.0", "com.google.code.findbugs" % "jsr305" % "2.0.3")

  val scalaIoFile = "com.github.scala-incubator.io" %% "scala-io-file" % "0.4.3-1"

}
