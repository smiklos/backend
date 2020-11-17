package io.branchtalk.discussions.api

import cats.data.{ NonEmptyList, NonEmptySet }
import cats.effect.{ Concurrent, ContextShift, Sync, Timer }
import com.typesafe.scalalogging.Logger
import io.branchtalk.api._
import io.branchtalk.auth._
import io.branchtalk.configs.PaginationConfig
import io.branchtalk.discussions.api.PostModels._
import io.branchtalk.discussions.model.Post
import io.branchtalk.discussions.reads.PostReads
import io.branchtalk.discussions.writes.PostWrites
import io.branchtalk.mappings._
import io.branchtalk.shared.models.{ CommonError, Paginated }
import io.scalaland.chimney.dsl._
import org.http4s._
import sttp.tapir.server.http4s._
import sttp.tapir.server.ServerEndpoint

import scala.collection.immutable.SortedSet

final class PostServer[F[_]: Sync: ContextShift: Concurrent: Timer](
  authServices:     AuthServices[F],
  postReads:        PostReads[F],
  postWrites:       PostWrites[F],
  paginationConfig: PaginationConfig
) {

  implicit private val as: AuthServices[F] = authServices

  private val logger = Logger(getClass)

  implicit private val serverOptions: Http4sServerOptions[F] = PostServer.serverOptions[F].apply(logger)

  private val withErrorHandling = PostServer.serverErrorHandling[F].apply(logger)

  private val newest = PostAPIs.newest.serverLogic[F].apply { case ((_, _), channelID, optOffset, optLimit) =>
    withErrorHandling {
      val offset     = paginationConfig.resolveOffset(optOffset)
      val limit      = paginationConfig.resolveLimit(optLimit)
      val channelIDS = SortedSet(channelID)
      for {
        paginated <- NonEmptySet.fromSet(channelIDS) match {
          case Some(channelIDs) => postReads.paginate(channelIDs, offset.nonNegativeLong, limit.positiveInt)
          case None             => Paginated.empty[Post].pure[F]
        }
      } yield Pagination.fromPaginated(paginated.map(APIPost.fromDomain), offset, limit)
    }
  }

  private val create = PostAPIs.create.serverLogic[F].apply { case ((user, _), channelID, createData) =>
    withErrorHandling {
      val userID = user.id
      val data = createData
        .into[Post.Create]
        .withFieldConst(_.authorID, userIDUsers2Discussions.get(userID))
        .withFieldConst(_.channelID, channelID)
        .transform
      for {
        creationScheduled <- postWrites.createPost(data)
      } yield CreatePostResponse(creationScheduled.id)
    }
  }

  private val read = PostAPIs.read
    .serverLogicWithOwnership[F, Unit]
    .apply { case (_, channelID, postID) =>
      postReads
        .requireById(postID)
        .flatTap(post => Sync[F].delay(assert(post.data.channelID === channelID, "Post should belong to Channel")))
        .void
    } { case ((_, _), _, postID) =>
      withErrorHandling {
        for {
          post <- postReads.requireById(postID)
        } yield APIPost.fromDomain(post)
      }
    }

  private val update = PostAPIs.update
    .serverLogicWithOwnership[F, UserID]
    .apply { case (_, channelID, postID, _) =>
      postReads
        .requireById(postID)
        .flatTap(post => Sync[F].delay(assert(post.data.channelID === channelID, "Post should belong to Channel")))
        .map(_.data.authorID)
        .map(userIDApi2Discussions.reverseGet)
    } { case ((user, _), _, postID, updateData) =>
      withErrorHandling {
        val userID = user.id
        val data = updateData
          .into[Post.Update]
          .withFieldConst(_.id, postID)
          .withFieldConst(_.editorID, userIDUsers2Discussions.get(userID))
          .withFieldRenamed(_.newContent, _.newContent)
          .withFieldRenamed(_.newTitle, _.newTitle)
          .transform
        for {
          result <- postWrites.updatePost(data)
        } yield UpdatePostResponse(result.id)
      }
    }

  private val delete = PostAPIs.delete
    .serverLogicWithOwnership[F, UserID]
    .apply { case (_, channelID, postID) =>
      postReads
        .requireById(postID)
        .flatTap(post => Sync[F].delay(assert(post.data.channelID === channelID, "Post should belong to Channel")))
        .map(_.data.authorID)
        .map(userIDApi2Discussions.reverseGet)
    } { case ((user, _), _, postID) =>
      withErrorHandling {
        val userID = user.id
        val data   = Post.Delete(postID, userIDUsers2Discussions.get(userID))
        for {
          result <- postWrites.deletePost(data)
        } yield DeletePostResponse(result.id)
      }
    }

  private val restore = PostAPIs.restore
    .serverLogicWithOwnership[F, UserID]
    .apply { case (_, channelID, postID) =>
      postReads
        .requireById(postID, isDeleted = true)
        .flatTap(post => Sync[F].delay(assert(post.data.channelID === channelID, "Post should belong to Channel")))
        .map(_.data.authorID)
        .map(userIDApi2Discussions.reverseGet)
    } { case ((user, _), _, postID) =>
      withErrorHandling {
        val userID = user.id
        val data   = Post.Restore(postID, userIDUsers2Discussions.get(userID))
        for {
          result <- postWrites.restorePost(data)
        } yield RestorePostResponse(result.id)
      }
    }

  def endpoints: NonEmptyList[ServerEndpoint[_, PostError, _, Any, F]] = NonEmptyList.of(
    newest,
    create,
    read,
    update,
    delete,
    restore
  )

  val routes: HttpRoutes[F] = endpoints.map(_.toRoutes).reduceK
}
object PostServer {

  def serverOptions[F[_]: Sync: ContextShift]: Logger => Http4sServerOptions[F] = ServerOptions.create[F, PostError](
    _,
    ServerOptions.ErrorHandler[PostError](
      () => PostError.ValidationFailed(NonEmptyList.one("Data missing")),
      () => PostError.ValidationFailed(NonEmptyList.one("Multiple errors")),
      (msg, _) => PostError.ValidationFailed(NonEmptyList.one(s"Error happened: ${msg}")),
      (expected, actual) => PostError.ValidationFailed(NonEmptyList.one(s"Expected: $expected, actual: $actual")),
      errors =>
        PostError.ValidationFailed(
          NonEmptyList
            .fromList(errors.map(e => s"Invalid value at ${e.path.map(_.encodedName).mkString(".")}"))
            .getOrElse(NonEmptyList.one("Validation failed"))
        )
    )
  )

  def serverErrorHandling[F[_]: Sync]: Logger => ServerErrorHandling[F, PostError] =
    ServerErrorHandling.handleCommonErrors[F, PostError] {
      case CommonError.InvalidCredentials(_) =>
        PostError.BadCredentials("Invalid credentials")
      case CommonError.InsufficientPermissions(msg, _) =>
        PostError.NoPermission(msg)
      case CommonError.NotFound(what, id, _) =>
        PostError.NotFound(s"$what with id=${id.show} could not be found")
      case CommonError.ParentNotExist(what, id, _) =>
        PostError.NotFound(s"Parent $what with id=${id.show} could not be found")
      case CommonError.ValidationFailed(errors, _) =>
        PostError.ValidationFailed(errors)
    }
}
