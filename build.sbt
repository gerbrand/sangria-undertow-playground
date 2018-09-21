import java.text.SimpleDateFormat
import java.util.Date
import sbt.Keys._

name := "sangria-undertow-playground"
description := "An example of GraphQL server written with Undertow and Sangria."

version := "1.0-SNAPSHOT"

scalaVersion := "2.12.3"

libraryDependencies ++= Seq(
  "io.undertow" % "undertow-core" % "1.4.20.Final" % "compile",
  "org.sangria-graphql" %% "sangria" % "1.3.0",
  "org.sangria-graphql" %% "sangria-play-json" % "1.0.4",
  "org.slf4j" % "slf4j-api" % "1.8.0-beta2",
  "org.scalatest" %% "scalatest" % "3.0.4" % "test"
)

assemblyMergeStrategy in assembly := {
  case PathList("org", "apache", "commons", "logging", _) => MergeStrategy.first
  case PathList("org", "apache", "commons", "logging", "impl", _) => MergeStrategy.first
  case PathList("META-INF", "io.netty.versions.properties") => MergeStrategy.discard
  case x =>
    val oldStrategy = (assemblyMergeStrategy in assembly).value
    oldStrategy(x)
}

fork in Test := false

parallelExecution in Test := false

mainClass in assembly := Some("io.dhlparcel.Boot")

herokuAppName in Compile := "sangria-undertow-playground-1"

herokuFatJar in Compile := Some((assemblyOutputPath in assembly).value)