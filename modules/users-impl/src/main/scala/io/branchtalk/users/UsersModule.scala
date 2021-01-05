package io.branchtalk.users

import cats.data.NonEmptyList
import cats.effect.{ ConcurrentEffect, ContextShift, Resource, Timer }
import com.softwaremill.macwire.wire
import io.branchtalk.discussions.events.DiscussionEvent
import io.branchtalk.logging.MDC
import io.branchtalk.shared.infrastructure._
import io.branchtalk.shared.model.{ Logger, UUID, UUIDGenerator }
import io.branchtalk.users.events.{ UsersCommandEvent, UsersEvent }
import io.branchtalk.users.reads._
import io.branchtalk.users.writes._
import io.prometheus.client.CollectorRegistry

import scala.annotation.nowarn

final case class UsersReads[F[_]](
  userReads:    UserReads[F],
  sessionReads: SessionReads[F],
  banReads:     BanReads[F]
)

final case class UsersWrites[F[_]](
  userWrites:             UserWrites[F],
  sessionWrites:          SessionWrites[F],
  banWrites:              BanWrites[F],
  runProjector:           ConsumerStream.AsResource[F],
  runDiscussionsConsumer: ConsumerStream.Runner[F, DiscussionEvent]
)
@nowarn("cat=unused") // macwire
object UsersModule {

  private val module = DomainModule[UsersEvent, UsersCommandEvent]

  def reads[F[_]: ConcurrentEffect: ContextShift: Timer](
    domainConfig: DomainConfig,
    registry:     CollectorRegistry
  ): Resource[F, UsersReads[F]] =
    Logger.getLogger[F].pipe { logger =>
      Resource.make(logger.info("Initialize Users reads"))(_ => logger.info("Shut down Users reads"))
    } >>
      module.setupReads[F](domainConfig, registry).map { case ReadsInfrastructure(transactor, _) =>
        val userReads:    UserReads[F]    = wire[UserReadsImpl[F]]
        val sessionReads: SessionReads[F] = wire[SessionReadsImpl[F]]
        val banReads:     BanReads[F]     = wire[BanReadsImpl[F]]

        wire[UsersReads[F]]
      }

  def writes[F[_]: ConcurrentEffect: ContextShift: Timer: MDC](
    domainConfig:           DomainConfig,
    registry:               CollectorRegistry
  )(implicit uuidGenerator: UUIDGenerator): Resource[F, UsersWrites[F]] =
    Logger.getLogger[F].pipe { logger =>
      Resource.make(logger.info("Initialize Users writes"))(_ => logger.info("Shut down Users writes")) >>
        module.setupWrites[F](domainConfig, registry).map {
          case WritesInfrastructure(transactor, internalProducer, internalConsumerStream, producer, cache) =>
            val userWrites:    UserWrites[F]    = wire[UserWritesImpl[F]]
            val sessionWrites: SessionWrites[F] = wire[SessionWritesImpl[F]]
            val banWrites:     BanWrites[F]     = wire[BanWritesImpl[F]]

            val projector: Projector[F, UsersCommandEvent, (UUID, UsersEvent)] = NonEmptyList
              .of(
                wire[UserProjector[F]],
                wire[BanProjector[F]]
              )
              .reduce
            val runProjector: ConsumerStream.AsResource[F] =
              internalConsumerStream.withCachedPipeToResource(logger, cache)(
                ConsumerStream.noID.andThen(projector).andThen(producer).andThen(ConsumerStream.produced)
              )

            val discussionsConsumer: DiscussionsConsumer[F] = wire[DiscussionsConsumer[F]]
            val runDiscussionsConsumer: ConsumerStream.Runner[F, DiscussionEvent] =
              _.withPipeToResource(logger)(
                ConsumerStream.noID
                  .andThen(discussionsConsumer)
                  .andThen(internalProducer)
                  .andThen(ConsumerStream.produced)
              )

            wire[UsersWrites[F]]
        }
    }

  def listenToUsers[F[_]](domainConfig: DomainConfig)(
    discussionEventConsumer:            ConsumerStream.Builder[F, DiscussionEvent],
    runDiscussionsConsumer:             ConsumerStream.Runner[F, DiscussionEvent]
  ): ConsumerStream.AsResource[F] =
    (discussionEventConsumer andThen runDiscussionsConsumer)(domainConfig.consumers("discussions"))
}
