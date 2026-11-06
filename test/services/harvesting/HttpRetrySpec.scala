package services.harvesting

import org.specs2.mutable.Specification

import java.time.Instant
import scala.concurrent.duration._

class HttpRetrySpec extends Specification {
  private val now = Instant.parse("2015-10-21T07:28:00Z")

  "Retry-After parsing" should {
    "accept a number of seconds" in {
      HttpRetry.retryAfter(" 120 ", now) must beSome(120.seconds)
    }

    "accept an HTTP date" in {
      HttpRetry.retryAfter("Wed, 21 Oct 2015 07:30:00 GMT", now) must beSome(2.minutes)
    }

    "not wait for an HTTP date in the past" in {
      HttpRetry.retryAfter("Wed, 21 Oct 2015 07:00:00 GMT", now) must beSome(Duration.Zero)
    }

    "ignore an invalid value" in {
      HttpRetry.retryAfter("soon", now) must beNone
    }
  }
}
