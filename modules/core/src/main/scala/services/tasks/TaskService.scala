package services.tasks

import com.google.inject.ImplementedBy
import play.api.libs.json.JsObject

import java.time.Instant
import scala.concurrent.Future

case class Task(id: Long, `type`: String, payload: JsObject, created: Instant, failed: Boolean, error: Option[String])


@ImplementedBy(classOf[SqlTaskService])
trait TaskService {
  def addTask(`type`: String, payload: JsObject): Future[Task]
  def runTask(`type`: String)(taskF: Task => Unit): Option[Boolean]
}
