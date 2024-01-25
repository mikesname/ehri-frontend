package services.search

import akka.actor.ActorRef
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import play.api.libs.json.Json
import play.api.{Configuration, Logger}
import services.data.EventForwarder.{Create, Delete, Update}
import services.data.EventHandler
import services.tasks.TaskService

import javax.inject.{Inject, Named}
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

case class IndexingEventHandler @Inject()(
  searchIndexer: SearchIndexMediator,
  config: Configuration,
  tasks: TaskService,
  @Named("event-forwarder") forwarder: ActorRef,
  @Named("task-worker") taskWorker: ActorRef
)(implicit ec: ExecutionContext, mat: Materializer) extends EventHandler {

  private val logger = Logger(getClass)

  // Bind the data API Create/Update/Delete actions
  // to the SearchIndexMediator update/delete handlers. Do this
  // asynchronously and log any failures...

  import scala.concurrent.duration.Duration

  private lazy val threshold = config.get[Int]("ehri.admin.bulkOperations.threshold")
  private def timeoutMinutes(items: Int): Duration = {
    if (items < threshold) 1.minute else config.get[Duration]("ehri.admin.bulkOperations.timeout")
  }

  private def logFailure(ids: Seq[String], func: Seq[String] => Future[Unit]): Unit = func(ids).failed.foreach {
    t => logger.error(s"Indexing error", t)
  }

  def handleCreate(ids: String*): Unit = {
    forwarder ! Create(ids)
    Await.result(tasks.addTask("index", Json.obj(
      "type" -> "create",
      "ids" -> ids
    )), 5.seconds)
    taskWorker ! IndexTaskWorker.Run
//    logFailure(ids, ids => searchIndexer.handle.indexIds(ids: _*))
  }

  def handleUpdate(ids: String*): Unit = {
    forwarder ! Update(ids)
    Await.result(tasks.addTask("index", Json.obj(
      "type" -> "update",
      "ids" -> ids
    )), 5.seconds)
    taskWorker ! IndexTaskWorker.Run
//    logFailure(ids, ids => searchIndexer.handle.indexIds(ids: _*))
  }

  // Special case - block when deleting because otherwise we get ItemNotFounds
  // after redirects because the item is still in the search index but not in
  // the database.
  def handleDelete(ids: String*): Unit = logFailure(ids, ids => Future.successful[Unit] {
//    val deletes = ids.toSeq.grouped(threshold * 2).map { group =>
//      logger.debug(s"Deleting ID group: ${group.mkString(", ")}")
//      forwarder ! Delete(group)
//      searchIndexer.handle.clearIds(group: _*)
//    }
//    // This runs all deletes in a synchronous sequence, as opposed to in parallel
//    val allDone: Future[akka.Done] = Source.fromIterator(() => deletes)
//      .mapAsync(parallelism = 1)(identity)
//      .runForeach(identity)
//
//    Await.result(tasks.addTask("index", Json.obj(
//      "type" -> "delete",
//      "ids" -> ids
//    )), 5.seconds)
//    concurrent.Await.result(allDone, timeoutMinutes(ids.size))
    forwarder ! Delete(ids)
    Await.result(tasks.addTask("index", Json.obj(
      "type" -> "delete",
      "ids" -> ids
    )), 5.seconds)
    taskWorker ! IndexTaskWorker.Run
  })
}
