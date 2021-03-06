package integration.admin

import helpers.IntegrationTestRunner
import models.CypherQuery
import play.api.libs.json.{JsNumber, JsValue, Json}
import play.api.test.FakeRequest


class CypherQuerySpec extends IntegrationTestRunner {

  import mockdata.privilegedUser

  case class ResultFormat(columns: Seq[String], data: Seq[Seq[JsValue]])
  object ResultFormat {
    implicit val _reads = Json.reads[ResultFormat]
  }

  "Cypher Query views" should {

    import models.CypherQuery._

    val formData = Map(
      NAME -> Seq("Test Query"),
      QUERY -> Seq("MATCH (u:userProfile) RETURN u.__ID__"),
      DESCRIPTION -> Seq("List all users")
    )

    "allow creating new queries" in new ITestApp {
      val cqCount = cypherQueryBuffer.size
      val post = FakeRequest(controllers.cypher.routes.CypherQueries.createQueryPost())
        .withUser(privilegedUser).withCsrf.callWith(formData)
      status(post) must equalTo(SEE_OTHER)
      val newCount = cypherQueryBuffer.size
      newCount must equalTo(cqCount + 1)
      cypherQueryBuffer.get(newCount) must beSome.which { f =>
        f.name must_== "Test Query"
      }
    }

    "allow updating queries" in new ITestApp {
      private val fakeKey = cypherQueryBuffer.size + 1
      cypherQueryBuffer += fakeKey -> CypherQuery(name = "Test", query = "RETURN 1")
      val cqCount = cypherQueryBuffer.size
      val post = FakeRequest(controllers.cypher.routes.CypherQueries
        .updateQueryPost(fakeKey.toString))
        .withUser(privilegedUser)
        .withCsrf
        .callWith(formData.updated("name", Seq("Update Test")))
      status(post) must equalTo(SEE_OTHER)
      val newCount = cypherQueryBuffer.size
      newCount must equalTo(cqCount)
      cypherQueryBuffer.get(fakeKey) must beSome.which { f =>
        f.name must_== "Update Test"
      }
    }

    "allow deleting queries" in new ITestApp {
      private val fakeKey = cypherQueryBuffer.size + 1
      cypherQueryBuffer += fakeKey -> CypherQuery(name = "Test", query = "RETURN 1")
      val cqCount = cypherQueryBuffer.size
      val post = FakeRequest(controllers.cypher.routes.CypherQueries
        .deleteQueryPost(fakeKey.toString))
        .withUser(privilegedUser)
        .withCsrf
        .call()
      status(post) must equalTo(SEE_OTHER)
      cypherQueryBuffer.size must_== cqCount - 1
    }

    "allow executing queries" in new ITestApp {
      private val fakeKey = cypherQueryBuffer.size + 1
      cypherQueryBuffer += fakeKey -> CypherQuery(name = "Test", query = "RETURN 1")
      val q = FakeRequest(controllers.cypher.routes.CypherQueries
        .executeQuery(fakeKey.toString))
        .withUser(privilegedUser)
        .call()
      status(q) must equalTo(OK)

      contentAsJson(q).validate[ResultFormat].asOpt must beSome.which { r =>
        r.columns.head must_== "1"
        r.data.head.head must_== JsNumber(1)
      }

      // CSV
      val q2 = FakeRequest("GET", controllers.cypher.routes.CypherQueries
        .executeQuery(fakeKey.toString) + "?format=csv")
        .withUser(privilegedUser)
        .call()
      status(q2) must equalTo(OK)
      contentAsString(q2) must_== "1\n1\n"
    }
  }
}
