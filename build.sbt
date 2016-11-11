name := "SocketChat"

version := "1.0"

scalaVersion := "2.11.8"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % "2.4.12",
  "com.typesafe.akka" %% "akka-slf4j" % "2.4.12"
)


javaOptions += "-Xmx3G -Xms3G -server -XX:+UseG1GC"