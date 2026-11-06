package services.harvesting

import org.apache.pekko.Done
import org.apache.pekko.pattern.after
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Sink
import play.api.libs.ws.{StandaloneWSResponse, WSRequestExecutor, WSRequestFilter}
import play.api.{Configuration, Logger}

import java.time.format.DateTimeFormatter
import java.time.{Instant, ZonedDateTime}
import scala.concurrent.Future.{successful => immediate}
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

/**
  * How harvest requests are retried when a server asks us to slow down.
  *
  * @param maxRetries   the number of retries after the first request
  * @param initialDelay the delay before the first retry, doubled for each
  *                     subsequent one, when the server gives no Retry-After
  * @param maxDelay     the longest delay before any retry
  */
case class RetryPolicy(maxRetries: Int, initialDelay: FiniteDuration, maxDelay: FiniteDuration)

object RetryPolicy {
  def apply(config: Configuration): RetryPolicy = RetryPolicy(
    config.get[Int]("ehri.admin.dataManager.harvestRetry.maxRetries"),
    config.get[FiniteDuration]("ehri.admin.dataManager.harvestRetry.initialDelay"),
    config.get[FiniteDuration]("ehri.admin.dataManager.harvestRetry.maxDelay"),
  )
}

object HttpRetry {
  private val logger = Logger(getClass)

  /**
    * Whether a status indicates the server wants us to slow down.
    */
  def isRetryable(status: Int): Boolean = status == 429 || status == 503

  // Retry-After is either a number of seconds or an HTTP date
  private[harvesting] def retryAfter(value: String, now: Instant): Option[FiniteDuration] =
    Try(value.trim.toLong.seconds).orElse(Try {
      val date = ZonedDateTime.parse(value.trim, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant
      (date.toEpochMilli - now.toEpochMilli).max(0L).millis
    }).toOption

  /**
    * Consume a response body that won't be used, so a streamed
    * response releases its connection.
    */
  def discardBody(r: StandaloneWSResponse)(implicit mat: Materializer): Future[Done] =
    r.bodyAsSource.runWith(Sink.ignore).recover { case _ => Done }(mat.executionContext)

  /**
    * A request filter which retries a request with backoff while the
    * response status is 429 or 503, up to the policy's maximum number
    * of retries, returning the last response.
    */
  def filter(policy: RetryPolicy)(implicit mat: Materializer): WSRequestFilter = WSRequestFilter { next =>
    WSRequestExecutor { req =>
      implicit val ec: ExecutionContext = mat.executionContext

      def attempt(n: Int): Future[StandaloneWSResponse] = next(req).flatMap { r =>
        if (isRetryable(r.status) && n < policy.maxRetries) {
          val wait = r.header("Retry-After").flatMap(retryAfter(_, Instant.now())).getOrElse(policy.initialDelay * math.pow(2, n).toLong).min(policy.maxDelay)
          logger.warn(s"Got status ${r.status} for ${req.url}, retrying in $wait (attempt ${n + 1} of ${policy.maxRetries})")
          discardBody(r).flatMap(_ => after(wait, mat.system.scheduler)(attempt(n + 1)))
        } else immediate(r)
      }

      attempt(0)
    }
  }
}
