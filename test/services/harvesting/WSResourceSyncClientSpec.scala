package services.harvesting

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import helpers.TestConfiguration
import models.{FileLink, ResourceSyncConfig}
import play.api.BuiltInComponentsFromContext
import play.api.routing.Router
import play.api.test.PlaySpecification
import play.filters.HttpFiltersComponents

class WSResourceSyncClientSpec extends PlaySpecification with TestConfiguration with RateLimiting {

  private implicit val as: ActorSystem = ActorSystem("test")
  private implicit val mat: Materializer = Materializer(as)

  def withResourceSyncClient[T](block: WSResourceSyncClient => T): T = {
    import play.api.mvc._
    import play.api.routing.sird._
    import play.api.test._
    import play.core.server.Server
    Server.withApplicationFromContext() { context =>
      new BuiltInComponentsFromContext(context) with HttpFiltersComponents {
        override def router: Router = Router.from {
          case play.api.routing.sird.GET(p"/resourcesync/$file") =>
            Action { implicit req =>
              Results.Ok.sendResource(file)(executionContext, fileMimeTypes)
            }
          case play.api.routing.sird.GET(p"/limited/$name/${int(limited)}") =>
            Action { req => rateLimited(req, limited)(Results.Ok(name)) }
          case play.api.routing.sird.GET(p"/retry-after/$name/${int(limited)}/$after") =>
            Action { req => rateLimited(req, limited, Some(after))(Results.Ok(name)) }
        }
      }.application
    } { implicit port =>
      WsTestClient.withClient { client =>
        block(WSResourceSyncClient(client, retryPolicy))
      }
    }
  }

  private def download(client: ResourceSyncClient, path: String): String =
    await(collect(client.get(endpoint, FileLink(path))))

  private val endpoint = ResourceSyncConfig("/resourcesync/capabilitylist.xml")

  "OAI RS client service" should {
    "list items" in withResourceSyncClient { client =>

      val list = await(client.list(endpoint))
      list.headOption must beSome.which { link =>
        link.loc must_== "/resourcesync/hierarchical-ead.xml"
      }
      list.size must_== 3
    }

    "list items with filter" in withResourceSyncClient { client =>
      val list = await(client.list(endpoint.copy(filter = Some("hier"))))
      list.headOption must beSome
      list.size must_== 1
    }

    "list items with RegExp filter" in withResourceSyncClient { client =>
      val list = await(client.list(endpoint.copy(filter = Some("test\\d\\.xml$"))))
      list.headOption must beSome
      list.size must_== 2
    }

    "retry a download that is rate limited" in withResourceSyncClient { client =>
      download(client, "/limited/a/2") must_== "a"
      requestCount("/limited/a/2") must_== 3
    }

    "give up on a download after the maximum retries" in withResourceSyncClient { client =>
      download(client, "/limited/b/3") must throwA[ResourceSyncError].like {
        case e: ResourceSyncError => e.code must_== "tooManyRequests"
      }
      requestCount("/limited/b/3") must_== 3
    }

    "wait no longer than the maximum delay when told to retry after" in withResourceSyncClient { client =>
      val start = System.currentTimeMillis()
      download(client, "/retry-after/c/1/3600") must_== "c"
      (System.currentTimeMillis() - start) must beLessThan(5000L)
    }

    "report rate limiting when listing items" in withResourceSyncClient { client =>
      await(client.list(ResourceSyncConfig("/limited/list/3"))) must throwA[ResourceSyncError].like {
        case e: ResourceSyncError => e.code must_== "tooManyRequests"
      }
      requestCount("/limited/list/3") must_== 3
    }

    "not retry when retries are disabled" in withResourceSyncClient { client =>
      download(client.withoutRetries, "/limited/e/1") must throwA[ResourceSyncError]
      requestCount("/limited/e/1") must_== 1
    }
  }
}
