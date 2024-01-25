package services.tasks

import akka.actor.ActorSystem
import helpers.withDatabaseFixture
import org.specs2.specification.AfterAll
import play.api.db.Database
import play.api.test.PlaySpecification

class SqlTaskServiceSpec extends PlaySpecification with AfterAll {

  private val actorSystem = ActorSystem()
  override def afterAll(): Unit = await(actorSystem.terminate())
  def service(implicit db: Database) = SqlTaskService(db, actorSystem)

  "task service" should {
    "add and run tasks" in withDatabaseFixture("task-fixtures.sql") { implicit db =>
      val task = service.addTask("test", play.api.libs.json.Json.obj("foo" -> "bar"))
      await(task).`type` must_== "test"
      await(task).payload must_== play.api.libs.json.Json.obj("foo" -> "bar")

      var ran = false
      val run = service.runTask("test") { task =>
        ran = true
      }
      run must beSome(true)
      ran must beTrue
    }

    "handle errors" in withDatabaseFixture("task-fixtures.sql") { implicit db =>
      val task = service.addTask("test", play.api.libs.json.Json.obj("foo" -> "bar"))
      await(task).`type` must_== "test"
      await(task).payload must_== play.api.libs.json.Json.obj("foo" -> "bar")

      var ran = false
      val run = service.runTask("test") { task =>
        throw new Exception("test")
        ran = true
      }
      run must beSome(false)
    }

    "handle no tasks" in withDatabaseFixture("task-fixtures.sql") { implicit db =>
      val run = service.runTask("test") { task => }
      run must beNone
    }
  }
}
