package io.branchtalk.users.api

import cats.data.NonEmptyList
import cats.effect.{ Clock, Concurrent, ContextShift, Sync, Timer }
import com.typesafe.scalalogging.Logger
import io.branchtalk.api.{ Permission => _, _ }
import io.branchtalk.auth._
import io.branchtalk.shared.model.CommonError
import io.branchtalk.users.api.UserModels._
import io.branchtalk.users.model.Ban
import io.branchtalk.users.reads.BanReads
import io.branchtalk.users.writes.BanWrites
import org.http4s._
import sttp.tapir.server.http4s._
import sttp.tapir.server.ServerEndpoint

final class UserBanServer[F[_]: Sync: ContextShift: Clock: Concurrent: Timer](
  authServices: AuthServices[F],
  banReads:     BanReads[F],
  banWrites:    BanWrites[F]
) {

  implicit private val as: AuthServices[F] = authServices

  private val logger = Logger(getClass)

  implicit private val serverOptions: Http4sServerOptions[F] = UserBanServer.serverOptions[F].apply(logger)

  implicit private val errorHandler: ServerErrorHandler[F, UserError] =
    UserBanServer.errorHandler[F].apply(logger)

  private val list = UserBanAPIs.list.serverLogic[F].apply { case (_, _) =>
    for {
      set <- banReads.findGlobally
    } yield BansResponse(set.view.map(_.bannedUserID).toList)
  }

  private val orderBan =
    UserBanAPIs.orderBan.serverLogic[F].apply { case ((moderator, _), BanOrderRequest(userID, reason)) =>
      val order = Ban.Order(userID, reason, Ban.Scope.Globally, moderator.id.some)
      for {
        _ <- banWrites.orderBan(order)
      } yield BanOrderResponse(userID)
    }

  private val liftBan = UserBanAPIs.liftBan.serverLogic[F].apply { case ((moderator, _), BanLiftRequest(userID)) =>
    val lift = Ban.Lift(userID, Ban.Scope.Globally, moderator.id.some)
    for {
      _ <- banWrites.liftBan(lift)
    } yield BanLiftResponse(userID)
  }

  def endpoints: NonEmptyList[ServerEndpoint[_, UserError, _, Any, F]] = NonEmptyList.of(
    list,
    orderBan,
    liftBan
  )

  val routes: HttpRoutes[F] = endpoints.map(Http4sServerInterpreter.toRoutes(_)).reduceK
}
object UserBanServer {

  def serverOptions[F[_]: Sync: ContextShift]: Logger => Http4sServerOptions[F] = ServerOptions.create[F, UserError](
    _,
    ServerOptions.ErrorHandler[UserError](
      () => UserError.ValidationFailed(NonEmptyList.one("Data missing")),
      () => UserError.ValidationFailed(NonEmptyList.one("Multiple errors")),
      (msg, _) => UserError.ValidationFailed(NonEmptyList.one(s"Error happened: ${msg}")),
      (expected, actual) => UserError.ValidationFailed(NonEmptyList.one(s"Expected: $expected, actual: $actual")),
      errors =>
        UserError.ValidationFailed(
          NonEmptyList
            .fromList(errors.map(e => s"Invalid value at ${e.path.map(_.encodedName).mkString(".")}"))
            .getOrElse(NonEmptyList.one("Validation failed"))
        )
    )
  )

  def errorHandler[F[_]: Sync]: Logger => ServerErrorHandler[F, UserError] =
    ServerErrorHandler.handleCommonErrors[F, UserError] {
      case CommonError.InvalidCredentials(_) =>
        UserError.BadCredentials("Invalid credentials")
      case CommonError.InsufficientPermissions(msg, _) =>
        UserError.NoPermission(msg)
      case CommonError.NotFound(what, id, _) =>
        UserError.NotFound(show"$what with id=$id could not be found")
      case CommonError.ParentNotExist(what, id, _) =>
        UserError.NotFound(show"Parent $what with id=$id could not be found")
      case CommonError.ValidationFailed(errors, _) =>
        UserError.ValidationFailed(errors)
    }
}
