import java.io.{ByteArrayInputStream, InputStream, OutputStream, OutputStreamWriter}
import java.util.Collections

import com.amazonaws.auth.profile.ProfileCredentialsProvider
import com.amazonaws.auth._
import com.amazonaws.services.dynamodbv2.model.AttributeValue
import com.amazonaws.services.dynamodbv2.{AmazonDynamoDB, AmazonDynamoDBClient}
import com.amazonaws.services.lambda.runtime.Context
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.json.jackson2.JacksonFactory
import com.google.api.services.chat.v1._
import com.google.api.services.chat.v1.model._
import play.api.libs.json.{JsValue, Json}

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.io.Source
import scala.util.{Failure, Success, Try}

object Handler {

  case class LambdaId(value: Int)

  object LambdaId {

    val ENV_VAR = "env"

    def fromEnv(envId: String): Try[LambdaId] = Try(Integer.parseInt(envId)).map(LambdaId.apply)
  }

  lazy val config = getConfig().get

  lazy val jsonAuth = config("google")

  private def hangoutsClient(jsonAuth: String) = {
    val httpTransport = GoogleNetHttpTransport.newTrustedTransport()
    val jsonFactory = JacksonFactory.getDefaultInstance()
    val credential = GoogleCredential
      .fromStream(new ByteArrayInputStream(jsonAuth.getBytes("UTF-8")))
      //.createScoped(Collections.singleton(DriveScopes.DRIVE_READONLY))

    new HangoutsChat.Builder(httpTransport, jsonFactory, credential)
      .setApplicationName("feedback chat bot")
      .build()
  }

  def doIt(input: JsValue): Option[JsValue] = {

    println(input)

    val spaces = hangoutsClient(jsonAuth).spaces().list()
    println(s"spaces: $spaces")
    Some(input)
  }

  def getUserData(user: String) = {
    AwsS3.getItem(s"user-$user").getOrElse(Map("id" -> s"user-$user"))
  }
  def getConfig() = {
    AwsS3.getItem("config")
  }
  def setUserData(data: Map[String, String]) = {
    AwsS3.putItem(data)
  }

  def main(args: Array[String]): Unit = {

//    val configData = Source.fromFile("/etc/gu/chatbot.json", "UTF-8").getLines().mkString("\n")
//    AwsS3.putItem(Map("id" -> "config", "google" -> configData))

    val spaces = hangoutsClient(jsonAuth).spaces().list()
    println(s"spaces: $spaces")
    val item = getUserData("ididid")
    println(s"item: $item")
  }

  // this is the entry point
  def apply(inputStream: InputStream, outputStream: OutputStream, context: Context): Unit = {
    val res = for {
      output <- doIt(Json.parse(inputStream)) match {
        case None => Failure(new RuntimeException("oops probably couldn't deserialise"))
        case Some(result ) => Success(result)
      }
    } yield output
    res match {
      case Failure(ex) => throw ex
      case Success(outputJS) => outputForAPIGateway(outputStream, outputJS)
    }
  }

  def outputForAPIGateway(outputStream: OutputStream, jsonResponse: JsValue): Unit = {
    val writer = new OutputStreamWriter(outputStream, "UTF-8")
    println(s"Response will be: \n ${jsonResponse.toString}")
    writer.write(Json.stringify(jsonResponse))
    writer.close()
  }

}

object AwsS3 {

  val tableName = "feedback-bot"

  def getItem(id: String) = {
    val result = client.getItem(tableName, Map("id" -> new AttributeValue(id)).asJava)
    val struct = Option(result.getItem).map(_.asScala.toMap)
    struct.map(_.mapValues(_.getS))
  }

  def putItem(data: Map[String, String]) = {
    val result = client.putItem(tableName, data.mapValues(v => new AttributeValue(v)).asJava)
    ()
  }

  val client = AmazonDynamoDBClient.builder.withCredentials(aws.CredentialsProvider).build()

}

object aws {
  val ProfileName = "developerPlayground"

  lazy val CredentialsProvider = new AWSCredentialsProviderChain(
    new EnvironmentVariableCredentialsProvider,
    new SystemPropertiesCredentialsProvider,
    new ProfileCredentialsProvider(ProfileName),
    new InstanceProfileCredentialsProvider(false),
    new EC2ContainerCredentialsProviderWrapper
  )

}