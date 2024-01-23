package services.tasks

import play.api.libs.json.JsObject

import java.time.Instant
import scala.concurrent.Future
import scala.util.Try

case class Task(id: Long, `type`: String, payload: JsObject, created: Instant, failed: Boolean, error: String)

trait TaskService {
  def addTask(`type`: String, payload: JsObject): Future[Task]
  def runTask(`type`: String)(taskF: Task => Future[Unit]): Future[Option[Boolean]]
}
