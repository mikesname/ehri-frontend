package services.harvesting

import org.apache.pekko.Done
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Sink
import models.OaiPmhConfig
import play.api.cache.AsyncCacheApi
import play.api.routing.Router
import org.specs2.specification.AfterAll
import play.api.test.PlaySpecification
import play.api.BuiltInComponentsFromContext
import play.filters.HttpFiltersComponents

import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag

class WSOaiPmhClientRetrySpec extends PlaySpecification with RateLimiting with AfterAll {

  private implicit val as: ActorSystem = ActorSystem("test")
  private implicit val mat: Materializer = Materializer(as)
  private implicit val ec: ExecutionContext = as.dispatcher
  override def afterAll(): Unit = await(as.terminate())

  private object NoCache extends AsyncCacheApi {
    override def set(key: String, value: Any, expiration: Duration): Future[Done] = Future.successful(Done)
    override def remove(key: String): Future[Done] = Future.successful(Done)
    override def getOrElseUpdate[A: ClassTag](key: String, expiration: Duration)(orElse: => Future[A]): Future[A] = orElse
    override def get[T: ClassTag](key: String): Future[Option[T]] = Future.successful(None)
    override def removeAll(): Future[Done] = Future.successful(Done)
  }

  private val identify =
    """<OAI-PMH><Identify>
      |<repositoryName>Test</repositoryName>
      |<baseURL>http://example.com</baseURL>
      |<protocolVersion>2.0</protocolVersion>
      |<granularity>YYYY-MM-DD</granularity>
      |</Identify></OAI-PMH>""".stripMargin

  private val record = "<OAI-PMH><GetRecord><record><metadata><ead>test</ead></metadata></record></GetRecord></OAI-PMH>"

  def withOaiPmhClient[T](block: WSOaiPmhClient => T): T = {
    import play.api.mvc._
    import play.api.routing.sird._
    import play.api.test._
    import play.core.server.Server
    Server.withApplicationFromContext() { context =>
      new BuiltInComponentsFromContext(context) with HttpFiltersComponents {
        override def router: Router = Router.from {
          case play.api.routing.sird.GET(p"/oai/$name/${int(limited)}") => Action { req =>
            rateLimited(req, limited) {
              if (req.getQueryString("verb").contains("Identify")) Results.Ok(identify).as("text/xml")
              else Results.Ok(record).as("text/xml")
            }
          }
          case play.api.routing.sird.GET(p"/missing/$name") => Action { req =>
            rateLimited(req, 0)(Results.NotFound)
          }
        }
      }.application
    } { implicit port =>
      WsTestClient.withClient { client =>
        block(WSOaiPmhClient(client, NoCache, retryPolicy))
      }
    }
  }

  "OAI-PMH client" should {
    "retry a rate limited request" in withOaiPmhClient { client =>
      val ident = await(client.identify(OaiPmhConfig("/oai/identify/2", "ead")))
      ident.name must_== "Test"
      requestCount("/oai/identify/2") must_== 3
    }

    "retry a rate limited streamed record" in withOaiPmhClient { client =>
      await(collect(client.getRecord(OaiPmhConfig("/oai/record/1", "ead"), "id1"))) must contain("<ead>test</ead>")
      requestCount("/oai/record/1") must_== 2
    }

    "fail a streamed record after the maximum retries" in withOaiPmhClient { client =>
      await(client.getRecord(OaiPmhConfig("/oai/failing/3", "ead"), "id1").runWith(Sink.ignore)) must throwA[OaiPmhError].like {
        case e: OaiPmhError => e.value must_== "429"
      }
      requestCount("/oai/failing/3") must_== 3
    }

    "not fail a streamed record which is not found" in withOaiPmhClient { client =>
      await(collect(client.getRecord(OaiPmhConfig("/missing/record", "ead"), "id1"))) must beEmpty
      requestCount("/missing/record") must_== 1
    }

    "not retry when retries are disabled" in withOaiPmhClient { client =>
      await(client.withoutRetries.identify(OaiPmhConfig("/oai/noretry/1", "ead"))) must throwA[OaiPmhError]
      requestCount("/oai/noretry/1") must_== 1
    }
  }
}
