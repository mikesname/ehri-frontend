package services.tasks

import akka.actor.ActorSystem
import anorm.postgresql.{asJson, fromJson}
import anorm.{RowParser, SqlParser, _}
import com.google.inject.Inject
import play.api.db.Database
import play.api.libs.json.JsObject

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}


case class SqlTaskService @Inject()(db: Database, actorSystem: ActorSystem) extends TaskService {
  private implicit def executionContext: ExecutionContext =
    actorSystem.dispatchers.lookup("contexts.blocking-io")

  private implicit val parser: RowParser[Task] = {
    SqlParser.long("id") ~
    SqlParser.str("type") ~
    SqlParser.get("payload")(fromJson[JsObject]) ~
    SqlParser.get[Instant]("created") ~
    SqlParser.bool("failed") ~
    SqlParser.get[Option[String]]("error")
  }.map { case id ~ t ~ p ~ c ~ f ~ e => Task(id, t, p, c, f, e) }

  override def addTask(`type`: String, payload: JsObject): Future[Task] = Future {
    db.withConnection { implicit conn =>
      SQL"""
        INSERT INTO tasks (type, payload) VALUES (${`type`}, ${asJson(payload)})
      """.executeInsert(parser.single)
    }
  }

  override def runTask(`type`: String)(taskF: Task => Unit): Option[Boolean] = {
    db.withTransaction { implicit conn =>
      // FIXME: Does with transaction work asynchronously?
      SQL"""
        SELECT * FROM tasks ORDER BY id ASC LIMIT 1 FOR UPDATE SKIP LOCKED
      """.as(parser.singleOpt).map { task =>
        try {
          taskF(task)
          SQL"""DELETE FROM tasks WHERE id = ${task.id}""".executeUpdate()
          true
        } catch {
          case t: Throwable =>
              SQL"""
                UPDATE tasks
                SET
                  failed = true,
                  error = ${t.getMessage} WHERE id = ${task.id}
              """.executeUpdate()
            false
        }
      }
    }
  }
}
