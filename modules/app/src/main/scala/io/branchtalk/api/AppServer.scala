package io.branchtalk.api

import cats.arrow.FunctionK
import cats.data.NonEmptyList
import cats.effect.{ Concurrent, ConcurrentEffect, ContextShift, Resource, Timer }
import com.softwaremill.macwire.wire
import io.branchtalk.auth.{ AuthServices, AuthServicesImpl }
import io.branchtalk.configs.{ APIConfig, APIPart, AppArguments, PaginationConfig }
import io.branchtalk.discussions.api.{ ChannelServer, CommentServer, PostServer, SubscriptionServer }
import io.branchtalk.discussions.{ DiscussionsReads, DiscussionsWrites }
import io.branchtalk.logging.MDC
import io.branchtalk.openapi.OpenAPIServer
import io.branchtalk.shared.model.UUIDGenerator
import io.branchtalk.users.api.{ ChannelModerationServer, UserModerationServer, UserServer }
import io.branchtalk.users.{ UsersReads, UsersWrites }
import io.prometheus.client.CollectorRegistry
import org.http4s._
import org.http4s.implicits._
import org.http4s.metrics.MetricsOps
import org.http4s.metrics.prometheus.Prometheus
import org.http4s.server.Server
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.server.middleware._
import sttp.tapir.server.ServerEndpoint

import scala.annotation.nowarn
import scala.concurrent.ExecutionContext

final class AppServer[F[_]: Concurrent: Timer: MDC](
  usesServer:              UserServer[F],
  userModerationServer:    UserModerationServer[F],
  channelModerationServer: ChannelModerationServer[F],
  channelServer:           ChannelServer[F],
  postServer:              PostServer[F],
  commentServer:           CommentServer[F],
  subscriptionServer:      SubscriptionServer[F],
  openAPIServer:           OpenAPIServer[F],
  metricsOps:              MetricsOps[F],
  correlationIDOps:        CorrelationIDOps[F],
  requestIDOps:            RequestIDOps[F],
  apiConfig:               APIConfig
) {

  private val corsConfig = CORSConfig(
    anyOrigin = apiConfig.http.corsAnyOrigin,
    allowCredentials = apiConfig.http.corsAllowCredentials,
    maxAge = apiConfig.http.corsMaxAge.toSeconds
  )

  private val logger = io.branchtalk.shared.model.Logger.getLogger[F]

  private val logRoutes = Logger[F, F](
    logHeaders = apiConfig.http.logHeaders,
    logBody = apiConfig.http.logBody,
    fk = FunctionK.id,
    logAction = ((s: String) => logger.info(s)).some
  )(_)

  private val enableMDCPropagation: Http[F, F] => Http[F, F] = _.mapF(MDC[F].enable(_))

  val routes: HttpApp[F] =
    NonEmptyList
      .of(
        usesServer.routes,
        userModerationServer.routes,
        channelModerationServer.routes,
        channelServer.routes,
        postServer.routes,
        commentServer.routes,
        subscriptionServer.routes,
        openAPIServer.routes
      )
      .reduceK
      .pipe(GZip(_))
      .pipe(CORS(_, corsConfig))
      .pipe(Metrics[F](metricsOps))
      .pipe(correlationIDOps.httpRoutes)
      .pipe(requestIDOps.httpRoutes) // TODO: cache requests with the same X-Request-ID AND auth header
      .orNotFound
      .pipe(logRoutes)
      .pipe(enableMDCPropagation)
}
object AppServer {

  // scalastyle:off method.length
  @nowarn("cat=unused") // macwire
  @SuppressWarnings(Array("org.wartremover.warts.GlobalExecutionContext")) // TODO: make configurable
  def asResource[F[_]: ConcurrentEffect: ContextShift: Timer: MDC](
    appArguments:           AppArguments,
    apiConfig:              APIConfig,
    registry:               CollectorRegistry,
    usersReads:             UsersReads[F],
    usersWrites:            UsersWrites[F],
    discussionsReads:       DiscussionsReads[F],
    discussionsWrites:      DiscussionsWrites[F]
  )(implicit uuidGenerator: UUIDGenerator): Resource[F, Server[F]] =
    Prometheus.metricsOps[F](registry, "server").flatMap { metricsOps =>
      val correlationIDOps: CorrelationIDOps[F] = CorrelationIDOps[F]

      val requestIDOps: RequestIDOps[F] = RequestIDOps[F]

      // this is kind of silly...
      import usersReads.{ sessionReads, userReads }
      import usersWrites.{ sessionWrites, userWrites }
      import discussionsReads.{ channelReads, commentReads, postReads, subscriptionReads }
      import discussionsWrites.{ channelWrites, commentWrites, postWrites, subscriptionWrites }

      val authServices: AuthServices[F] = wire[AuthServicesImpl[F]]

      val usersServer: UserServer[F] = {
        val paginationConfig: PaginationConfig = apiConfig.safePagination(APIPart.Users)
        wire[UserServer[F]]
      }
      val userModerationServer: UserModerationServer[F] = {
        val paginationConfig: PaginationConfig = apiConfig.safePagination(APIPart.Users)
        wire[UserModerationServer[F]]
      }
      val channelModerationServer: ChannelModerationServer[F] = {
        val paginationConfig: PaginationConfig = apiConfig.safePagination(APIPart.Users)
        wire[ChannelModerationServer[F]]
      }
      val channelServer: ChannelServer[F] = {
        val paginationConfig: PaginationConfig = apiConfig.safePagination(APIPart.Channels)
        wire[ChannelServer[F]]
      }
      val postServer: PostServer[F] = {
        val paginationConfig: PaginationConfig = apiConfig.safePagination(APIPart.Posts)
        wire[PostServer[F]]
      }
      val commentServer: CommentServer[F] = {
        val paginationConfig: PaginationConfig = apiConfig.safePagination(APIPart.Comments)
        wire[CommentServer[F]]
      }
      val subscriptionServer: SubscriptionServer[F] = {
        val paginationConfig: PaginationConfig = apiConfig.safePagination(APIPart.Posts)
        wire[SubscriptionServer[F]]
      }
      val openAPIServer: OpenAPIServer[F] = {
        import apiConfig.info
        val endpoints: NonEmptyList[ServerEndpoint[_, _, _, Nothing, F]] =
          NonEmptyList
            .of(
              usersServer.endpoints,
              userModerationServer.endpoints,
              channelModerationServer.endpoints,
              channelServer.endpoints,
              postServer.endpoints,
              commentServer.endpoints,
              subscriptionServer.endpoints
            )
            .reduceK
        wire[OpenAPIServer[F]]
      }

      val appServer = wire[AppServer[F]]

      val logger = io.branchtalk.shared.model.Logger.getLogger[F]

      Resource.make(logger.info("Starting up API server"))(_ => logger.info("API server shut down")) >>
        BlazeServerBuilder[F](ExecutionContext.global)
          .enableHttp2(apiConfig.http.http2Enabled)
          .withLengthLimits(maxRequestLineLen = apiConfig.http.maxRequestLineLength.value,
                            maxHeadersLen = apiConfig.http.maxHeaderLineLength.value
          )
          .bindHttp(port = appArguments.port, host = appArguments.host)
          .withHttpApp(appServer.routes)
          .resource
          .flatTap { server =>
            Resource.liftF(logger.info(s"API server started at ${server.address.toString}"))
          }
    }
  // scalastyle:on method.length
}
