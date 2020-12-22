package io.branchtalk.users

import cats.effect.{ IO, Resource }
import io.branchtalk.shared.infrastructure.DomainConfig
import io.branchtalk.{ IOTest, ResourcefulTest }
import io.branchtalk.shared.model.UUIDGenerator

trait UsersIOTest extends IOTest with ResourcefulTest {

  implicit protected def uuidGenerator: UUIDGenerator

  // populated by resources
  protected var usersCfg:    DomainConfig    = _
  protected var usersReads:  UsersReads[IO]  = _
  protected var usersWrites: UsersWrites[IO] = _

  protected lazy val usersResource: Resource[IO, Unit] = for {
    _ <- TestUsersConfig.loadDomainConfig[IO].map(usersCfg = _)
    _ <- UsersModule.reads[IO](usersCfg, registry).map(usersReads = _)
    _ <- UsersModule.writes[IO](usersCfg, registry).map(usersWrites = _)
  } yield ()

  override protected def testResource: Resource[IO, Unit] = super.testResource >> usersResource

  protected def withUsersProjections[A](fa: IO[A]): IO[A] =
    usersWrites.runProjector.use { usersProjector =>
      for {
        _ <- usersProjector.logError("Error reported by Users projector").start
        a <- fa
      } yield a
    }
}
