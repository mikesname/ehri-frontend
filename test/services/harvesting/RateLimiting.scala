package services.harvesting

import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.util.ByteString
import play.api.mvc.{RequestHeader, Result, Results}

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.Future
import scala.concurrent.duration._

/**
  * Helpers for testing harvest clients against rate-limited endpoints.
  */
trait RateLimiting {
  protected val retryPolicy: RetryPolicy = RetryPolicy(maxRetries = 2, initialDelay = 10.millis, maxDelay = 50.millis)

  private val requests = new ConcurrentHashMap[String, AtomicInteger]()

  protected def requestCount(path: String): Int = requests.getOrDefault(path, new AtomicInteger(0)).get()

  /**
    * Respond with 429 to the first `limited` requests for a path, optionally
    * with a Retry-After header, and with the given result after that.
    */
  protected def rateLimited(req: RequestHeader, limited: Int, retryAfter: Option[String] = None)(ok: => Result): Result = {
    val count = requests.computeIfAbsent(req.path, _ => new AtomicInteger(0)).incrementAndGet()
    if (count > limited) ok
    else retryAfter.fold[Result](Results.TooManyRequests)(v => Results.TooManyRequests.withHeaders("Retry-After" -> v))
  }

  protected def collect(src: Source[ByteString, _])(implicit mat: Materializer): Future[String] =
    src.runWith(Sink.fold(ByteString.empty)(_ ++ _)).map(_.utf8String)(mat.executionContext)
}
