package io.branchtalk.api

import cats.data.Kleisli
import cats.effect.{ Sync, Timer }
import dev.profunktor.redis4cats.RedisCommands
import fs2.{ io => _, _ }
import io.branchtalk.api.CachingOps.{ CacheKey, CachedResponse }
import io.branchtalk.configs.APIConfig
import io.branchtalk.logging.RequestID
import org.http4s._
import org.http4s.headers
import org.http4s.server.middleware.Caching

import scala.collection.compat.immutable.ArraySeq

final class CachingOps[F[_]: Sync: Timer](apiConfig: APIConfig) {

  private val cachedMethods = Set[Method](Method.POST, Method.PUT, Method.DELETE) // TODO: make configurable
  private val maxSize       = 1024 // TODO: make configurable

  private def redis: RedisCommands[F, CacheKey, CachedResponse] = ??? // TODO: pass

  // request missing X-Request-ID should not use cache at all
  private def verifyRequest(request: Request[F]): Option[CacheKey] =
    request.headers
      .get(RequestIDOps.requestIDHeader)
      .filter(_ => cachedMethods.contains(request.method))
      .map(_.value.pipe(RequestID(_)))
      .map(
        CacheKey(_, scala.runtime.ScalaRunTime._hashCode((request.method.name, request.uri, request.headers.toList)))
      )

  // maps response if available, on error acts as if there were no cache
  private def getCachedResponse(cacheKey: CacheKey): F[Option[Response[F]]] = redis
    .get(cacheKey)
    .map { response: Option[CachedResponse] =>
      response.map { case CachedResponse(status, httpVersion, headers, body) =>
        Response.apply[F](
          status = Status.fromInt(status).getOrElse(???),
          httpVersion = HttpVersion.fromString(httpVersion).getOrElse(???),
          headers = Headers(headers.map[Header] { case (n, v) => Header(n, v) }.toList),
          body = Stream.fromIterator(body.iterator)
        )
      }
    }
    .handleError(_ => None)

  // if response is <= maxSize, it is cached by redis
  private def cacheResponse(cacheKey: CacheKey, response: Response[F]): Response[F] = {
    val buffer = java.nio.ByteBuffer.allocateDirect(maxSize + 1)
    def storeInBuffer(byte: Byte): F[Unit] = Sync[F].delay {
      if (buffer.position() < maxSize) {
        buffer.put(byte)
      }
      ()
    }
    val pushOnceSent = Stream.evalSeq(
      Sync[F]
        .defer(
          if (buffer.position() < maxSize) {
            val cachedResponse = CachedResponse(
              status = response.status.code,
              httpVersion = response.httpVersion.renderString,
              headers = response.headers.toList.map(h => h.name.value -> h.value).toMap,
              body = ArraySeq.from(buffer.array()).take(buffer.position())
            )
            redis.append(cacheKey, cachedResponse)
          } else Sync[F].unit
        )
        .as(List.empty)
    )
    response.withBodyStream(response.body.evalTap(storeInBuffer).append(pushOnceSent))
  }

  // WIP
  def httpWIP(service: HttpApp[F]): HttpApp[F] = Kleisli { request: Request[F] =>
    verifyRequest(request) match {
      case Some(cacheKey) =>
        getCachedResponse(cacheKey).flatMap {
          case Some(response) => response.pure[F] // returns cache if possible
          case None           => service(request).map(cacheResponse(cacheKey, _)) // returns and caches in the process
        }
      case None =>
        service(request) // not cached
    }
  }

  // TODO: replace with the above after it is polished and working

  private def doNotCacheWithoutRequestID(service: HttpApp[F]): HttpApp[F] = Kleisli { request: Request[F] =>
    val removeCacheIfRequestIDMissing: Response[F] => Response[F] =
      request.headers.get(RequestIDOps.requestIDHeader).map(_.value.pipe(RequestID(_))) match {
        case Some(_) => identity
        case None    => _.removeHeader(headers.`Cache-Control`).removeHeader(headers.Date).removeHeader(headers.Expires)
      }
    service(request).map(removeCacheIfRequestIDMissing)
  }

  def http(service: HttpApp[F]): HttpApp[F] = Caching.cache[F, F](
    lifetime = apiConfig.http.cacheDuration,
    isPublic = CacheDirective.public.asLeft,
    methodToSetOn = Set(Method.POST, Method.PUT, Method.DELETE),
    statusToSetOn = Caching.Helpers.defaultStatusToSetOn,
    http = doNotCacheWithoutRequestID(service)
  )
}
object CachingOps {

  // TODO: derive codecs

  final case class CacheKey(requestID: RequestID, hash: Int)

  final case class CachedResponse(
    status:      Int,
    httpVersion: String,
    headers:     Map[String, String],
    body:        ArraySeq[Byte]
  )

  def apply[F[_]: Sync: Timer](apiConfig: APIConfig): CachingOps[F] = new CachingOps[F](apiConfig)
}
