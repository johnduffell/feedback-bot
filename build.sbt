
name := "feedback-bot"

version := "0.1"

scalaVersion := "2.12.7"


libraryDependencies ++= Seq(
  "com.typesafe.play" %% "play-json" % "2.6.10",
  "org.typelevel" %% "cats-core" % "1.4.0",
  "com.amazonaws" % "aws-lambda-java-core" % "1.2.0",
  "com.google.apis" % "google-api-services-chat" % "v1-rev53-1.25.0",
  "com.amazonaws" % "aws-java-sdk-dynamodb" % "1.11.311"
)


assemblyJarName := "lambda.jar"
