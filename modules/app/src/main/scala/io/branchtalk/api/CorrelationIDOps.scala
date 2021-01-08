package io.branchtalk.api

import cats.data.{ Kleisli, OptionT }
import cats.effect.Sync
import io.branchtalk.logging.{ CorrelationID, MDC }
import io.branchtalk.shared.model.UUIDGenerator
import org.http4s._
import org.http4s.util.{ CaseInsensitiveString => CIString }

final class CorrelationIDOps[F[_]: Sync: MDC](implicit uuidGenerator: UUIDGenerator) {

  def httpRoutes(service: HttpRoutes[F]): HttpRoutes[F] = Kleisli { request: Request[F] =>
    for {
      correlationID <- request.headers.get(CorrelationIDOps.correlationIDHeader) match {
        case None           => CorrelationID.generate[OptionT[F, *]]
        case Some(idHeader) => CorrelationID(idHeader.value).pure[OptionT[F, *]]
      }
      _ <- correlationID.updateMDC[F].pipe(OptionT.liftF(_))
      reqWithID = request.putHeaders(Header.Raw(CorrelationIDOps.correlationIDHeader, correlationID.show))
      response <- service(reqWithID)
    } yield response
  }
}
object CorrelationIDOps {

  val correlationIDHeader: CIString = CIString("X-Correlation-ID")

  def apply[F[_]: Sync: MDC](implicit uuidGenerator: UUIDGenerator): CorrelationIDOps[F] = new CorrelationIDOps[F]
}
