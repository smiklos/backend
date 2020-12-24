package io.branchtalk.users.api

import io.branchtalk.api.{ Permission => _, RequiredPermissions => _, _ }
import io.branchtalk.discussions.DiscussionsFixtures
import io.branchtalk.mappings._
import io.branchtalk.shared.model._
import io.branchtalk.users.UsersFixtures
import io.branchtalk.users.api.UserModels._
import io.branchtalk.users.model.{ Permission, RequiredPermissions, User }
import org.specs2.mutable.Specification
import sttp.model.StatusCode

final class ChannelModerationServerSpec
    extends Specification
    with ServerIOTest
    with UsersFixtures
    with DiscussionsFixtures {

  sequential // User pagination tests cannot be run in parallel to other User tests

  implicit protected val uuidGenerator: TestUUIDGenerator = new TestUUIDGenerator

  "ChannelModerationServer-provided endpoints" should {

    "on GET /discussions/channels/{channelID}/moderation" in {

      "return paginated Moderators" in {
        withAllProjections {
          for {
            // given
            (CreationScheduled(userID), CreationScheduled(sessionID)) <- userCreate.flatMap(
              usersWrites.userWrites.createUser
            )
            _ <- usersReads.userReads.requireById(userID).eventually()
            _ <- usersWrites.userWrites.updateUser(
              User.Update(
                id = userID,
                moderatorID = None,
                newUsername = Updatable.Keep,
                newDescription = OptionUpdatable.Keep,
                newPassword = Updatable.Keep,
                updatePermissions = List(Permission.Update.Add(Permission.Administrate))
              )
            )
            userIDs <- (0 until 9).toList
              .traverse(_ => userCreate.flatMap(usersWrites.userWrites.createUser).map(_._1.id))
              .map(_ :+ userID)
            _ <- userIDs.traverse(usersReads.userReads.requireById(_)).eventually()
            channelID <- channelIDCreate
            permission = Permission.ModerateChannel(channelID)
            _ <- userIDs
              .map(
                User.Update(
                  _,
                  moderatorID = None,
                  newUsername = Updatable.Keep,
                  newDescription = OptionUpdatable.Keep,
                  newPassword = Updatable.Keep,
                  updatePermissions = List(Permission.Update.Add(permission))
                )
              )
              .traverse(usersWrites.userWrites.updateUser)
            users <- userIDs
              .traverse(usersReads.userReads.requireById)
              .assert("Users should eventually have Moderator status")(
                _.forall(_.data.permissions.allow(RequiredPermissions.one(permission)))
              )
              .eventually()
            // when
            response1 <- ChannelModerationAPIs.paginate.toTestCall.untupled(
              Authentication.Session(sessionID = sessionIDApi2Users.reverseGet(sessionID)),
              channelID,
              None,
              PaginationLimit(5).some
            )
            response2 <- ChannelModerationAPIs.paginate.toTestCall.untupled(
              Authentication.Session(sessionID = sessionIDApi2Users.reverseGet(sessionID)),
              channelID,
              PaginationOffset(5L).some,
              PaginationLimit(5).some
            )
          } yield {
            // then
            response1.code must_=== StatusCode.Ok
            response1.body must beValid(beRight(anInstanceOf[Pagination[APIUser]]))
            response2.code must_=== StatusCode.Ok
            response2.body must beValid(beRight(anInstanceOf[Pagination[APIUser]]))
            (response1.body.toValidOpt.flatMap(_.toOption), response2.body.toValidOpt.flatMap(_.toOption))
              .mapN { (pagination1, pagination2) =>
                (pagination1.entities.toSet ++ pagination2.entities.toSet) must_=== users.map(APIUser.fromDomain).toSet
              }
              .getOrElse(pass)
          }
        }
      }
    }

    "on POST /discussions/channels/{channelID}/moderation" in {
      withAllProjections {
        for {
          // given
          (CreationScheduled(userID), CreationScheduled(sessionID)) <- userCreate.flatMap(
            usersWrites.userWrites.createUser
          )
          _ <- usersReads.userReads.requireById(userID).eventually()
          _ <- usersWrites.userWrites.updateUser(
            User.Update(
              id = userID,
              moderatorID = None,
              newUsername = Updatable.Keep,
              newDescription = OptionUpdatable.Keep,
              newPassword = Updatable.Keep,
              updatePermissions = List(Permission.Update.Add(Permission.Administrate))
            )
          )
          (CreationScheduled(updatedUserID), CreationScheduled(_)) <- userCreate.flatMap(
            usersWrites.userWrites.createUser
          )
          _ <- usersReads.userReads.requireById(updatedUserID).eventually()
          channelID <- channelIDCreate
          permission = Permission.ModerateChannel(channelID)
          // when
          response <- ChannelModerationAPIs.grantChannelModeration.toTestCall.untupled(
            Authentication.Session(sessionID = sessionIDApi2Users.reverseGet(sessionID)),
            channelID,
            GrantModerationRequest(updatedUserID)
          )
          _ <- usersReads.userReads
            .requireById(updatedUserID)
            .assert("User should eventually have Moderator status")(
              _.data.permissions.allow(RequiredPermissions.one(permission))
            )
            .eventually()
        } yield
        // then
        response.body must beValid(beRight(be_===(GrantModerationResponse(updatedUserID))))
      }
    }

    "on DELETE /discussions/channels/{channelID}/moderation" in {
      withAllProjections {
        for {
          // given
          (CreationScheduled(userID), CreationScheduled(sessionID)) <- userCreate.flatMap(
            usersWrites.userWrites.createUser
          )
          _ <- usersReads.userReads.requireById(userID).eventually()
          _ <- usersWrites.userWrites.updateUser(
            User.Update(
              id = userID,
              moderatorID = None,
              newUsername = Updatable.Keep,
              newDescription = OptionUpdatable.Keep,
              newPassword = Updatable.Keep,
              updatePermissions = List(Permission.Update.Add(Permission.Administrate))
            )
          )
          (CreationScheduled(updatedUserID), CreationScheduled(_)) <- userCreate.flatMap(
            usersWrites.userWrites.createUser
          )
          _ <- usersReads.userReads.requireById(updatedUserID).eventually()
          channelID <- channelIDCreate
          permission = Permission.ModerateChannel(channelID)
          _ <- usersWrites.userWrites.updateUser(
            User.Update(
              id = updatedUserID,
              moderatorID = None,
              newUsername = Updatable.Keep,
              newDescription = OptionUpdatable.Keep,
              newPassword = Updatable.Keep,
              updatePermissions = List(Permission.Update.Add(permission))
            )
          )
          _ <- usersReads.userReads
            .requireById(updatedUserID)
            .assert("User should eventually have Moderator status")(
              _.data.permissions.allow(RequiredPermissions.one(permission))
            )
            .eventually()
          // when
          response <- ChannelModerationAPIs.revokeChannelModeration.toTestCall.untupled(
            Authentication.Session(sessionID = sessionIDApi2Users.reverseGet(sessionID)),
            channelID,
            RevokeModerationRequest(updatedUserID)
          )
          _ <- usersReads.userReads
            .requireById(updatedUserID)
            .assert("User should eventually lose Moderator status")(
              _.data.permissions.allow(RequiredPermissions.one(permission))
            )
            .eventually()
        } yield
        // then
        response.body must beValid(beRight(be_===(RevokeModerationResponse(updatedUserID))))
      }
    }
  }
}
