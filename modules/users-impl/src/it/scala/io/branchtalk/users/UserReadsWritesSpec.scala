package io.branchtalk.users

import cats.effect.IO
import io.branchtalk.shared.model.{ CommonError, ID, OptionUpdatable, TestUUIDGenerator, Updatable }
import io.branchtalk.users.model.{ Password, Permission, Permissions, User }
import monocle.macros.syntax.lens._
import org.specs2.mutable.Specification

import scala.concurrent.duration.DurationInt

final class UserReadsWritesSpec extends Specification with UsersIOTest with UsersFixtures {

  sequential // User pagination tests cannot be run in parallel to other User tests

  implicit protected val uuidGenerator: TestUUIDGenerator = new TestUUIDGenerator

  "User Reads & Writes" should {

    "create a User and eventually read it" in {
      usersWrites.runProjector.use { usersProjector =>
        for {
          // given
          _ <- usersProjector.logError("Error reported by Users projector").start
          creationData <- (0 until 3).toList.traverse(_ => userCreate)
          // when
          toCreate <- creationData.traverse(usersWrites.userWrites.createUser)
          ids = toCreate.map(_._1.id)
          users <- ids.traverse(usersReads.userReads.requireById).eventually()
          usersOpt <- ids.traverse(usersReads.userReads.getById).eventually()
          usersExist <- ids.traverse(usersReads.userReads.exists).eventually()
          userDeleted <- ids.traverse(usersReads.userReads.deleted).eventually()
        } yield {
          // then
          ids must containTheSameElementsAs(users.map(_.id))
          usersOpt must contain(beSome[User]).foreach
          usersExist must contain(beTrue).foreach
          userDeleted must not(contain(beTrue).atLeastOnce)
        }
      }
    }

    "don't update a User that doesn't exists" in {
      usersWrites.runProjector.use { usersProjector =>
        for {
          // given
          _ <- usersProjector.logError("Error reported by Users projector").start
          moderatorID <- userCreate.flatMap(usersWrites.userWrites.createUser).map(_._1.id)
          _ <- usersReads.userReads.requireById(moderatorID).eventually()
          creationData <- (0 until 3).toList.traverse(_ => userCreate)
          fakeUpdateData <- creationData.traverse { data =>
            ID.create[IO, User].map { id =>
              User.Update(
                id = id,
                moderatorID = moderatorID.some,
                newUsername = Updatable.Set(data.username),
                newDescription = OptionUpdatable.setFromOption(data.description),
                newPassword = Updatable.Set(data.password),
                updatePermissions = List.empty
              )
            }
          }
          // when
          toUpdate <- fakeUpdateData.traverse(usersWrites.userWrites.updateUser(_).attempt)
        } yield
        // then
        toUpdate must contain(beLeft[Throwable]).foreach
      }
    }

    "update an existing User" in {
      usersWrites.runProjector.use { usersProjector =>
        for {
          // given
          _ <- usersProjector.logError("Error reported by Users projector").start
          moderatorID <- userCreate.flatMap(usersWrites.userWrites.createUser).map(_._1.id)
          _ <- usersReads.userReads.requireById(moderatorID).eventually()
          creationData <- (0 until 3).toList.traverse(_ => userCreate)
          toCreate <- creationData.traverse(usersWrites.userWrites.createUser)
          ids = toCreate.map(_._1.id)
          created <- ids.traverse(usersReads.userReads.requireById).eventually()
          updateData = created.zipWithIndex.collect {
            case (User(id, data), 0) =>
              User.Update(
                id = id,
                moderatorID = moderatorID.some,
                newUsername = Updatable.Set(data.username),
                newDescription = OptionUpdatable.setFromOption(data.description),
                newPassword = Updatable.Set(data.password),
                updatePermissions = List(Permission.Update.Add(Permission.IsUser(id)))
              )
            case (User(id, _), 1) =>
              User.Update(
                id = id,
                moderatorID = moderatorID.some,
                newUsername = Updatable.Keep,
                newDescription = OptionUpdatable.Keep,
                newPassword = Updatable.Keep,
                updatePermissions = List.empty
              )
            case (User(id, _), 2) =>
              User.Update(
                id = id,
                moderatorID = moderatorID.some,
                newUsername = Updatable.Keep,
                newDescription = OptionUpdatable.Erase,
                newPassword = Updatable.Keep,
                updatePermissions = List(Permission.Update.Add(Permission.ModerateUsers))
              )
          }
          // when
          _ <- updateData.traverse(usersWrites.userWrites.updateUser)
          updated <- ids
            .traverse(usersReads.userReads.requireById)
            .assert("Updated entity should have lastModifiedAt set")(_.last.data.lastModifiedAt.isDefined)
            .eventually()
        } yield
        // then
        created
          .zip(updated)
          .zipWithIndex
          .collect {
            case ((User(id, older), User(_, newer)), 0) =>
              // set case
              older
                .lens(_.permissions)
                .set(Permissions.empty.append(Permission.IsUser(id))) must_=== newer.lens(_.lastModifiedAt).set(None)
            case ((User(_, older), User(_, newer)), 1) =>
              // keep case
              older must_=== newer
            case ((User(_, older), User(_, newer)), 2) =>
              // erase case
              older
                .lens(_.permissions)
                .set(Permissions.empty.append(Permission.ModerateUsers))
                .lens(_.description)
                .set(None) must_=== newer.lens(_.lastModifiedAt).set(None)
          }
          .lastOption
          .getOrElse(true must beFalse)
      }
    }

    "allow delete of a created User" in {
      usersWrites.runProjector.use { usersProjector =>
        for {
          // given
          _ <- usersProjector.logError("Error reported by Users projector").start
          moderatorID <- userCreate.flatMap(usersWrites.userWrites.createUser).map(_._1.id)
          _ <- usersReads.userReads.requireById(moderatorID).eventually()
          creationData <- (0 until 3).toList.traverse(_ => userCreate)
          // when
          toCreate <- creationData.traverse(usersWrites.userWrites.createUser)
          ids = toCreate.map(_._1.id)
          _ <- ids.traverse(usersReads.userReads.requireById).eventually()
          _ <- ids.map(User.Delete(_, moderatorID.some)).traverse(usersWrites.userWrites.deleteUser)
          _ <- ids
            .traverse(usersReads.userReads.getById)
            .assert("All Users should be eventually deleted")(_.forall(_.isEmpty))
            .eventually()
          notExist <- ids.traverse(usersReads.userReads.exists)
          areDeleted <- ids.traverse(usersReads.userReads.deleted)
        } yield {
          // then
          notExist must contain(beFalse).foreach
          areDeleted must contain(beTrue).foreach
        }
      }
    }

    "allow password checking" in {
      usersWrites.runProjector.use { usersProjector =>
        for {
          // given
          _ <- usersProjector.logError("Error reported by Users projector").start
          goodPassword <- passwordCreate("password")
          rawGoodPassword <- Password.Raw.parse[IO]("password".getBytes)
          rawBadPassword <- Password.Raw.parse[IO]("bad".getBytes)
          userId <- userCreate
            .map(_.copy(password = goodPassword))
            .flatMap(usersWrites.userWrites.createUser)
            .map(_._1.id)
          user <- usersReads.userReads.requireById(userId).eventually()
          // when
          ok <- usersReads.userReads.authenticate(user.data.username, rawGoodPassword).attempt
          fail <- usersReads.userReads.authenticate(user.data.username, rawBadPassword).attempt
        } yield {
          // then
          ok must beRight(user)
          fail must beLeft(anInstanceOf[CommonError.InvalidCredentials])
        }
      }
    }

    "paginate newest Users" in {
      usersWrites.runProjector.use { usersProjector =>
        for {
          // given
          _ <- usersProjector.logError("Error reported by Users projector").start
          paginatedData <- (0 until 10).toList.traverse(_ => userCreate)
          paginatedIds <- paginatedData.traverse(usersWrites.userWrites.createUser).map(_.map(_._1.id))
          _ <- paginatedIds.traverse(usersReads.userReads.requireById(_)).eventually(delay = 1.second)
          // when
          pagination <- usersReads.userReads.paginate(User.Sorting.Newest, 0L, 5)
          pagination2 <- usersReads.userReads.paginate(User.Sorting.Newest, 5L, 5)
        } yield {
          // then
          pagination.entities must haveSize(5)
          pagination.nextOffset.map(_.value) must beSome(5)
          pagination2.entities must haveSize(5)
        }
      }
    }

    "paginate Users by name alphabetically" in {
      usersWrites.runProjector.use { usersProjector =>
        for {
          // given
          _ <- usersProjector.logError("Error reported by Users projector").start
          paginatedData <- (0 until 10).toList.traverse(_ => userCreate)
          paginatedIds <- paginatedData.traverse(usersWrites.userWrites.createUser).map(_.map(_._1.id))
          _ <- paginatedIds.traverse(usersReads.userReads.requireById(_)).eventually(delay = 1.second)
          // when
          pagination <- usersReads.userReads.paginate(User.Sorting.NameAlphabetically, 0L, 5)
          pagination2 <- usersReads.userReads.paginate(User.Sorting.NameAlphabetically, 5L, 5)
        } yield {
          // then
          pagination.entities must haveSize(5)
          pagination.nextOffset.map(_.value) must beSome(5)
          pagination2.entities must haveSize(5)
        }
      }
    }

    "paginate Users by email alphabetically" in {
      usersWrites.runProjector.use { usersProjector =>
        for {
          // given
          _ <- usersProjector.logError("Error reported by Users projector").start
          paginatedData <- (0 until 10).toList.traverse(_ => userCreate)
          paginatedIds <- paginatedData.traverse(usersWrites.userWrites.createUser).map(_.map(_._1.id))
          _ <- paginatedIds.traverse(usersReads.userReads.requireById(_)).eventually(delay = 1.second)
          // when
          pagination <- usersReads.userReads.paginate(User.Sorting.EmailAlphabetically, 0L, 5)
          pagination2 <- usersReads.userReads.paginate(User.Sorting.EmailAlphabetically, 5L, 5)
        } yield {
          // then
          pagination.entities must haveSize(5)
          pagination.nextOffset.map(_.value) must beSome(5)
          pagination2.entities must haveSize(5)
        }
      }
    }
  }
}
