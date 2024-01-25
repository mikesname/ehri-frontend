package services.search

import akka.actor.{Actor, ActorLogging}
import services.tasks.{Task, TaskService}

import javax.inject.Inject

object IndexTaskWorker {
  case object Run
  case class Result(done: Option[Boolean])
}

case class IndexTaskWorker @Inject()(searchIndexer: SearchIndexMediator, tasks: TaskService) extends Actor with ActorLogging {
  import IndexTaskWorker._

  def receive: Receive = {
    case Run =>
      val r = tasks.runTask("index") { task =>
        log.debug(s"Running task ${task.id}, ${task.payload}")
        val ids = (task.payload \ "ids").as[Seq[String]]
        (task.payload \ "type").as[String] match {
          case "create" => searchIndexer.handle.indexIds(ids: _*)
          case "update" => searchIndexer.handle.indexIds(ids: _*)
          case "delete" => searchIndexer.handle.clearIds(ids: _*)
        }
      }
      self ! Result(r)

    case Result(Some(_)) =>
      self ! Run
  }
}
