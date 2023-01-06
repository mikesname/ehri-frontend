package services.search

import akka.actor.ActorRef
import play.api.Logger
import services.data.EventForwarder.{Create, Update, Delete}
import services.data.EventHandler

import javax.inject.{Inject, Named}
import scala.concurrent.{ExecutionContext, Future}

case class IndexingEventHandler @Inject()(
  searchIndexer: SearchIndexMediator,
  @Named("event-forwarder") forwarder: ActorRef
)(implicit ec: ExecutionContext) extends EventHandler {

  private val logger = Logger(getClass)

  // Bind the data API Create/Update/Delete actions
  // to the SearchIndexMediator update/delete handlers. Do this
  // asynchronously and log any failures...

  import java.util.concurrent.TimeUnit
  import scala.concurrent.duration.Duration

  private def logFailure(ids: Seq[String], func: Seq[String] => Future[Unit]): Unit = func(ids).failed.foreach {
    t => logger.error(s"Indexing error", t)
  }

  def handleCreate(ids: String*): Unit = {
    forwarder ! Create(ids)
    logFailure(ids, ids => searchIndexer.handle.indexIds(ids: _*))
  }

  def handleUpdate(ids: String*): Unit = {
    forwarder ! Update(ids)
    logFailure(ids, ids => searchIndexer.handle.indexIds(ids: _*))
  }

  // Special case - block when deleting because otherwise we get ItemNotFounds
  // after redirects because the item is still in the search index but not in
  // the database.
  def handleDelete(ids: String*): Unit = logFailure(ids, ids => Future.successful[Unit] {
    forwarder ! Delete(ids)
    concurrent.Await.result(searchIndexer.handle.clearIds(ids: _*), Duration(1, TimeUnit.MINUTES))
  })
}
