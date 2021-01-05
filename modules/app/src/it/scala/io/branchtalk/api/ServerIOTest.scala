package io.branchtalk.api

import cats.effect.{ IO, Resource }
import io.branchtalk.discussions.DiscussionsIOTest
import io.branchtalk.users.{ UsersIOTest, UsersModule }
import org.http4s.server.Server
import org.specs2.matcher.{ OptionLikeCheckedMatcher, OptionLikeMatcher, ValueCheck }
import sttp.client3.{ Response, SttpBackend }
import sttp.client3.asynchttpclient.cats.AsyncHttpClientCatsBackend
import sttp.model.Uri
import sttp.tapir._
import sttp.tapir.client.sttp._

trait ServerIOTest extends UsersIOTest with DiscussionsIOTest {

  // populated by resources
  protected var server: Server[IO]           = _
  protected var client: SttpBackend[IO, Any] = _
  protected lazy val sttpBaseUri: Uri = Uri.unsafeApply(
    scheme = server.baseUri.scheme.fold(???)(_.value),
    host = server.baseUri.host.fold(???)(_.value),
    port = server.baseUri.port.fold(???)(_.intValue())
  )

  protected lazy val serverResource: Resource[IO, Unit] = for {
    (appArguments, apiConfig) <- TestApiConfigs.asResource[IO]
    _ <- AppServer
      .asResource[IO](
        appArguments = appArguments,
        apiConfig = apiConfig,
        registry = registry,
        usersReads = usersReads,
        usersWrites = usersWrites,
        discussionsReads = discussionsReads,
        discussionsWrites = discussionsWrites
      )
      .map(server = _)
    _ <- AsyncHttpClientCatsBackend.resource[IO]().map(client = _)
  } yield ()

  override protected def testResource: Resource[IO, Unit] = super.testResource >> serverResource

  protected def withAllProjections[A](fa: IO[A]): IO[A] =
    (usersWrites.runProjector,
     UsersModule.listenToUsers(usersCfg)(discussionsReads.discussionEventConsumer, usersWrites.runDiscussionsConsumer),
     discussionsWrites.runProjector
    ).tupled.use { case (usersProjector, usersDiscussionsConsumer, discussionsProjector) =>
      for {
        _ <- usersProjector.logError("Error reported by Users projector").start
        _ <- usersDiscussionsConsumer.logError("Error reported by Users' Discussions projector").start
        _ <- discussionsProjector.logError("Error reported by Discussions projector").start
        a <- fa
      } yield a
    }

  implicit class ServerTestOps[I, E, O](private val endpoint: Endpoint[I, E, O, Any]) {

    val toTestCall: I => IO[Response[DecodeResult[Either[E, O]]]] = (input: I) =>
      endpoint
        .toSttpRequest(sttpBaseUri)(SttpClientOptions.default, WebSocketToPipe.webSocketsNotSupportedForAny)
        .apply(input)
        .acceptEncoding("deflate") // helps debugging request in logs
        .send(client)
  }

  implicit class AuthServerTestOps[I, E, O](private val authEndpoint: AuthedEndpoint[I, E, O, Any]) {

    val toTestCall: I => IO[Response[DecodeResult[Either[E, O]]]] = authEndpoint.endpoint.toTestCall
  }

  import ServerIOTest._

  implicit def toDecoderResultOps[A](result: DecodeResult[A]): DecodeResultOps[A] = new DecodeResultOps[A](result)

  import org.specs2.control.ImplicitParameters._
  def beValid[T](t:          ValueCheck[T]):                     ValidResultCheckedMatcher[T] = ValidResultCheckedMatcher(t)
  def beValid[T](implicit p: ImplicitParam = implicitParameter): ValidResultMatcher[T]        = use(p)(ValidResultMatcher[T]())
}

object ServerIOTest {

  implicit class DecodeResultOps[A](private val result: DecodeResult[A]) extends AnyVal {

    def toValidOpt: Option[A] = result match {
      case DecodeResult.Value(t) => t.some
      case _                     => none[A]
    }
  }

  final case class ValidResultMatcher[T]()
      extends OptionLikeMatcher[DecodeResult, T, T]("DecodeResult.Value", (_: DecodeResult[T]).toValidOpt)

  final case class ValidResultCheckedMatcher[T](check: ValueCheck[T])
      extends OptionLikeCheckedMatcher[DecodeResult, T, T]("DecodeResult.Value", (_: DecodeResult[T]).toValidOpt, check)
}
