import java.io.{ByteArrayInputStream, InputStream, OutputStream, OutputStreamWriter}
import java.util.Collections

import Handler.WireBody.{WireMessage, WireSpace, WireUser}
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
import play.api.libs.json.{JsValue, Json, Writes}

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

  case class WireRequest(body: String)
  object WireRequest {
    implicit val reads = Json.reads[WireRequest]
  }

  case class WireBody(
    `type`: String,
    token: String,
    message: WireMessage,
    user: WireUser,
    space: WireSpace
  )
  object WireBody {
    case class WireMessage(
      name: String, //  spaces/zBKQ9AAAAAE/messages/fD0Vh9OUndQ.fD0Vh9OUndQ
      text: String, // this is what they typed
      thread: WireThread
    )
    case class WireThread(
      name: String // "spaces/zBKQ9AAAAAE/threads/fD0Vh9OUndQ"
    )
    case class WireUser(
      name: String, // "users/376324763467834786234"
      displayName: String, // "john duffell"
      email: String // the email address
    )
    case class WireSpace(
      name: String, // spaces/zBKQ9AAAAAE
    )

    implicit val readsWireThread = Json.reads[WireThread]
    implicit val readsWireMessage = Json.reads[WireMessage]
    implicit val readsWireUser = Json.reads[WireUser]
    implicit val readsWireSpace = Json.reads[WireSpace]
    implicit val reads = Json.reads[WireBody]
  }

  case class WireResponse(statusCode: String, headers: Map[String, String], body: String)
  object WireResponse {

    implicit val responseWrites = Json.writes[WireResponse]

  }

  case class ChatResponse(text: String)
  object ChatResponse {
    implicit val writes = Json.writes[ChatResponse]
  }

  def doIt(input: JsValue): Option[JsValue] = {

    val wireInput = input.validate[WireRequest]

    println(input)
    val wireBody = Json.parse(wireInput.get.body).validate[WireBody].get
    println(s"body as case class = $wireBody")

    val spaces = hangoutsClient(jsonAuth).spaces().list()
    println(s"spaces: $spaces")

    val chatResponse = dealWithMessage(wireBody)
    val jsVal = Json.toJson(chatResponse)
    val wireOutput = WireResponse("200", Map("Content-Type" -> "text/json"), Json.stringify(jsVal))
    Some(Json.toJson[WireResponse](wireOutput))
  }

  def botInitiate(spaceId: String, message: String) = {
    println(s"bot message: $spaceId, message: $message")
  }

  // this decides what to do
  def dealWithMessage(wireBody: WireBody): ChatResponse = {
    val email = wireBody.user.email
    val message = wireBody.message.text

    val currentState = getUserData(email).updated("spaceId", wireBody.space.name/* TODO tbc */)
    if (message == "debug") ChatResponse(currentState.toString)
    val (updatedData, response) = currentState.get("state") match {
      case Some("choseUser") =>
        (currentState, "We are still waiting for your feedback.")
      case Some("asked") =>
        val askerEmail = currentState("asker")
        val originatorUserData = getUserData(askerEmail)
        val originatorSpaceId = originatorUserData("spaceId")
        botInitiate(originatorSpaceId, s"Your feedback from $email is: $message")
        setUserData(originatorUserData.-("state"))
        (currentState.-("state"), s"Thanks! we have sent your comments to $askerEmail")
      case None if message.contains("@") => // we aren't expecting anything special back
        val theirUserData = getUserData(message)
        theirUserData.get("spaceId") match {
          case Some(theirSpaceId) =>
            val updated = currentState.updated ("state", "choseUser").updated("theirSpaceId", theirSpaceId)
            botInitiate(theirSpaceId, s"hello, $email wants to get feedback - please type in now.")
            setUserData(theirUserData.updated("state", "asked").updated("asker", email))
            (updated, s"We have asked $message for feedback!\nPlease hold tight while they respond in their own time.")
          case None =>
            (currentState, s"sorry, we don't have $message on our system yet, please get them to message the bot first")
        }
      case None =>
        (currentState, "*Welcome to feedback bot!*\uD83E\uDD16\nWho have you been working with recently?")

    }

    setUserData(updatedData)
    ChatResponse(s"$response")
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