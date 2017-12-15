name := "raft"

version := "0.1"

scalaVersion := "2.12.4"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-typed" % "2.5.7",
  "com.typesafe.akka" %% "akka-typed-testkit" % "2.5.7" % Test,
  "org.scalatest" %% "scalatest" % "3.0.4" % Test
)
