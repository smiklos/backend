package io.branchtalk.users.api

import cats.data.NonEmptyList
import cats.effect.{ Clock, Concurrent, ContextShift, Sync, Timer }
import com.typesafe.scalalogging.Logger
import io.branchtalk.api.{ Permission => _, _ }
import io.branchtalk.auth._
import io.branchtalk.configs.PaginationConfig
import io.branchtalk.shared.model.{ CommonError, OptionUpdatable, Updatable }
import io.branchtalk.users.api.UserModels._
import io.branchtalk.users.{ UsersReads, UsersWrites }
import io.branchtalk.users.model.{ Permission, User }
import org.http4s._
import sttp.tapir.server.http4s._
import sttp.tapir.server.ServerEndpoint

final class UserModerationServer[F[_]: Sync: ContextShift: Clock: Concurrent: Timer](
  authServices:     AuthServices[F],
  usersReads:       UsersReads[F],
  usersWrites:      UsersWrites[F],
  paginationConfig: PaginationConfig
) {

  implicit private val as: AuthServices[F] = authServices

  private val logger = Logger(getClass)

  implicit private val serverOptions: Http4sServerOptions[F] = UserModerationServer.serverOptions[F].apply(logger)

  implicit private val errorHandler: ServerErrorHandler[F, UserError] =
    UserModerationServer.errorHandler[F].apply(logger)

  private val paginate = UserModerationAPIs.paginate.serverLogic[F].apply { case ((_, _), optOffset, optLimit) =>
    val sortBy  = User.Sorting.NameAlphabetically
    val offset  = paginationConfig.resolveOffset(optOffset)
    val limit   = paginationConfig.resolveLimit(optLimit)
    val filters = List(User.Filter.HasPermission(Permission.ModerateUsers))
    for {
      paginated <- usersReads.userReads.paginate(sortBy, offset.nonNegativeLong, limit.positiveInt, filters)
    } yield Pagination.fromPaginated(paginated.map(APIUser.fromDomain), offset, limit)
  }

  private val grantUserModeration = UserModerationAPIs.grantUserModeration.serverLogic[F].apply {
    case ((moderator, _), GrantModerationRequest(userID)) =>
      val update = User.Update(
        id = userID,
        moderatorID = moderator.id.some,
        newUsername = Updatable.Keep,
        newDescription = OptionUpdatable.Keep,
        newPassword = Updatable.Keep,
        updatePermissions = List(Permission.Update.Add(Permission.ModerateUsers))
      )
      for {
        _ <- usersWrites.userWrites.updateUser(update)
      } yield GrantModerationResponse(userID)
  }

  private val revokeUserModeration = UserModerationAPIs.revokeUserModeration.serverLogic[F].apply {
    case ((moderator, _), RevokeModerationRequest(userID)) =>
      val update = User.Update(
        id = userID,
        moderatorID = moderator.id.some,
        newUsername = Updatable.Keep,
        newDescription = OptionUpdatable.Keep,
        newPassword = Updatable.Keep,
        updatePermissions = List(Permission.Update.Remove(Permission.ModerateUsers))
      )
      for {
        _ <- usersWrites.userWrites.updateUser(update)
      } yield RevokeModerationResponse(userID)
  }
  def endpoints: NonEmptyList[ServerEndpoint[_, UserError, _, Any, F]] = NonEmptyList.of(
    paginate,
    grantUserModeration,
    revokeUserModeration
  )

  val routes: HttpRoutes[F] = endpoints.map(_.toRoutes).reduceK
}

object UserModerationServer {

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
        UserError.NotFound(s"$what with id=${id.show} could not be found")
      case CommonError.ParentNotExist(what, id, _) =>
        UserError.NotFound(s"Parent $what with id=${id.show} could not be found")
      case CommonError.ValidationFailed(errors, _) =>
        UserError.ValidationFailed(errors)
    }
}
