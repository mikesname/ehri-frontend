package controllers.generic

import play.api.libs.concurrent.Execution.Implicits._
import play.api.mvc.Controller
import controllers.base.{ControllerHelpers, CoreActionBuilders}
import utils.search.SearchIndexMediator
import play.api.libs.iteratee.{Enumerator, Concurrent}
import scala.concurrent.Future
import scala.util.{Failure, Success}
import play.api.Logger
import backend.Resource


object Indexable {
  /**
   * Message that terminates a long-lived streaming response, such
   * as the search index update job.
   */
  val DONE_MESSAGE = "Done"
  val ERR_MESSAGE = "Index Error"
}

/**
 * Mixin trait for items which can be individually re-indexed, i.e:
 * repositories, vocabularies, countries, etc.
 *
 * @author Mike Bryant (http://github.com/mikesname)
 */
trait Indexable[MT] extends Controller with CoreActionBuilders with ControllerHelpers {

  def searchIndexer: SearchIndexMediator

  private def wrapMsg(m: String) = s"<message>$m</message>"

  private def finishSuccess(chan: Concurrent.Channel[String]) {
    chan.push(wrapMsg(Indexable.DONE_MESSAGE))
    chan.eofAndEnd()
  }

  private def finishError(chan: Concurrent.Channel[String], t: Throwable) {
    Logger.logger.error(t.getMessage)
    chan.push(wrapMsg("Indexing operation failed: " + t.getMessage))
    chan.push(wrapMsg(Indexable.ERR_MESSAGE))
    chan.eofAndEnd()
  }


  /**
   * Update a single item in the index.
   *
   * @param id The items id
   * @return An action returning a chunked progress response.
   */
  def updateItemPost(id: String) = AdminAction { implicit request =>
    val channel = Concurrent.unicast[String] { chan =>
      searchIndexer.handle.withChannel(chan, wrapMsg).indexId(id).onComplete {
        case Success(()) => finishSuccess(chan)
        case Failure(t) => finishError(chan, t)
      }
    }

    Ok.chunked(channel.andThen(Enumerator.eof))
  }

  /**
   * Update items parented by this one. The field in the parent docs that
   * refers to this one must be given, i.e. holderId=[this-item]
   * @param field The discriminator field
   * @param id The parent item id
   * @return An action returning a chunked progress response.
   */
  def updateChildItemsPost(field: String, id: String)(implicit rs: Resource[MT]) = AdminAction { implicit request =>
    Logger.debug(s"INDEXING WITH: $searchIndexer")
    val channel = Concurrent.unicast[String] { chan =>

      def clearIndex: Future[Unit] = {
        val f = searchIndexer.handle.clearKeyValue(field, id)
        f.onSuccess {
          case () => chan.push(wrapMsg("... finished clearing index"))
        }
        f
      }

      val job = clearIndex.flatMap { _ =>
        searchIndexer.handle.withChannel(chan, wrapMsg).indexChildren(rs.entityType, id)
      }

      job.onComplete {
        case Success(()) => finishSuccess(chan)
        case Failure(t) => finishError(chan, t)
      }
    }

    Ok.chunked(channel.andThen(Enumerator.eof))
  }

}
