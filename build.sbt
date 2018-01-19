version := "0.1"

val scalaV = "2.12.4"

val akkaVersion = "2.5.8"

scalafmtTestOnCompile in ThisBuild := true
scalafmtFailTest in ThisBuild := false

lazy val server = (project in file("server")).settings(
  scalaVersion := scalaV,
  scalaJSProjects := Seq(client),
  pipelineStages in Assets := Seq(scalaJSPipeline),
  // triggers scalaJSPipeline when using compile or continuous compilation
  compile in Compile := ((compile in Compile) dependsOn scalaJSPipeline).value,
  libraryDependencies ++= Seq(
    "com.typesafe.akka" %% "akka-typed" % akkaVersion,
    "com.typesafe.akka" %% "akka-typed-testkit" % akkaVersion % Test,
    "com.typesafe.akka" %% "akka-http" % "10.0.10",
    "com.vmunier" %% "scalajs-scripts" % "1.1.0",
    "com.typesafe.akka" %% "akka-stream" % akkaVersion,
    "org.scalatest" %% "scalatest" % "3.0.4" % Test,
    "org.scalacheck" %% "scalacheck" % "1.13.5" % Test
  ),
  WebKeys.packagePrefix in Assets := "public/",
  managedClasspath in Runtime += (packageBin in Assets).value,
).enablePlugins(SbtWeb, SbtTwirl, JavaAppPackaging).
  dependsOn(sharedJvm)

lazy val client = (project in file("client")).settings(
  scalaVersion := scalaV,
  scalaJSUseMainModuleInitializer := true,
  libraryDependencies ++= Seq(
    "org.scala-js" %%% "scalajs-dom" % "0.9.3",
    "io.suzaku" %%% "diode" % "1.1.3",
    "com.lihaoyi" %%% "scalatags" % "0.6.7"
  )
).enablePlugins(ScalaJSPlugin, ScalaJSWeb).
  dependsOn(sharedJs)

lazy val shared = (crossProject.crossType(CrossType.Pure) in file("shared")).
  settings(
    scalaVersion := scalaV,
    libraryDependencies ++= Seq(
      "com.lihaoyi" %%% "upickle" % "0.5.1"
    )
  )

lazy val sharedJvm = shared.jvm
lazy val sharedJs = shared.js

// loads the server project at sbt startup
onLoad in Global := (onLoad in Global).value andThen { s: State => "project server" :: s }

