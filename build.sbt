name := "raft"

version := "0.1"

scalaVersion := "2.12.4"

val akkaVersion = "2.5.8"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-typed" % akkaVersion,
  "com.typesafe.akka" %% "akka-typed-testkit" % akkaVersion % Test,
  "org.scalatest" %% "scalatest" % "3.0.4" % Test
)
