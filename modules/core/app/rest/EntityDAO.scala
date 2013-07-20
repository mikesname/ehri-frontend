package rest

import play.api.libs.concurrent.Execution.Implicits._
import scala.concurrent.Future
import play.api.libs.ws.WS
import play.api.libs.json._
import defines.{EntityType,ContentType}
import models.{UserProfile, Entity}
import models.json.{ClientConvertable, RestReadable, RestConvertable}
import play.api.Logger
import play.api.Play.current
import play.api.cache.Cache
import models.base.AnyModel


/**
 * Class representing a page of data.
 *
 * @param total
 * @param offset
 * @param limit
 * @param items
 * @tparam T
 */
case class Page[+T](
  total: Long,
  offset: Int,
  limit: Int,
  items: Seq[T]
) extends utils.AbstractPage[T]

object Page {

  implicit def restReads[T](implicit rd: RestReadable[T]): Reads[Page[T]] = {
    Page.pageReads(rd.restReads)
  }
  implicit def clientFormat[T](implicit cfmt: ClientConvertable[T]): Writes[Page[T]] = {
    Page.pageWrites(cfmt.clientFormat)
  }

  import play.api.libs.json._
  import play.api.libs.json.util._
  import play.api.libs.functional.syntax._

  implicit def pageReads[T](implicit r: Reads[T]): Reads[Page[T]] = (
    (__ \ "total").read[Long] and
    (__ \ "offset").read[Int] and
    (__ \ "limit").read[Int] and
    (__ \ "values").lazyRead(Reads.seq[T](r))
  )(Page.apply[T] _)

  implicit def pageWrites[T](implicit r: Writes[T]): Writes[Page[T]] = (
    (__ \ "total").write[Long] and
      (__ \ "offset").write[Int] and
      (__ \ "limit").write[Int] and
      (__ \ "values").lazyWrite(Writes.seq[T](r))
    )(unlift(Page.unapply[T] _))

  implicit def pageFormat[T](implicit r: Reads[T], w: Writes[T]): Format[Page[T]]
      = Format(pageReads(r), pageWrites(w))
}

object RestPageParams {
  final val ACCESSOR_PARAM = "accessibleTo"
  final val ORDER_PARAM = "sort"
  final val FILTER_PARAM = "filter"
  final val OFFSET_PARAM = "offset"
  final val LIMIT_PARAM = "limit"
  final val PAGE_PARAM = "page"

  final val DEFAULT_LIST_LIMIT = 20
}

/**
 * Class for handling page parameter data
 * @param page
 * @param limit
 * @param filters
 * @param sort
 */
case class RestPageParams(page: Option[Int] = None, limit: Option[Int] = None, filters: List[String] = Nil, sort: List[String] = Nil) {

  import RestPageParams._

  def mergeWith(default: RestPageParams): RestPageParams = RestPageParams(
    page = page.orElse(default.page),
    limit = limit.orElse(default.limit),
    filters = if (filters.isEmpty) default.filters else filters,
    sort = if (sort.isEmpty) default.sort else sort
  )

  def offset: Int = (page.getOrElse(1) - 1) * limit.getOrElse(DEFAULT_LIST_LIMIT)
  def range: String = s"$offset-${offset + limit.getOrElse(DEFAULT_LIST_LIMIT)}"

  override def toString = {
    "?" + List(
      s"${OFFSET_PARAM}=${offset}",
      s"${LIMIT_PARAM}=${limit.getOrElse(DEFAULT_LIST_LIMIT)}",
      multiParam(FILTER_PARAM, filters),
      multiParam(ORDER_PARAM, sort)
    ).filterNot(_.trim.isEmpty).mkString("&")
  }

  private def multiParam(key: String, values: List[String]) = values.map(s => s"${key}=${s}").mkString("&")

}

object EntityDAO {


  /**
   * Global listeners for CUD events
   */
  import scala.collection.mutable.ListBuffer
  private val onCreate: ListBuffer[JsObject => Unit] = ListBuffer.empty
  private val onUpdate: ListBuffer[JsObject => Unit] = ListBuffer.empty
  private val onDelete: ListBuffer[String => Unit] = ListBuffer.empty

  def addCreateHandler(f: JsObject => Unit): Unit = onCreate += f
  def addUpdateHandler(f: JsObject => Unit): Unit = onUpdate += f
  def addDeleteHandler(f: String => Unit): Unit = onDelete += f

  def handleCreate(e: JsObject): JsObject = {
    onCreate.foreach(f => f(e))
    e
  }
  def handleUpdate(e: JsObject): JsObject = {
    onUpdate.foreach(f => f(e))
    e
  }
  def handleDelete(id: String): Unit = onDelete.foreach(f => f(id))
}

/**
 * Data Access Object for fetching data about generic entity types.
 *
 * @param entityType
 * @param userProfile
 */
case class EntityDAO[MT](entityType: EntityType.Type, userProfile: Option[UserProfile] = None) extends RestDAO {

  import play.api.http.Status._

  def requestUrl = "http://%s:%d/%s/%s".format(host, port, mount, entityType)

  def get(id: String)(implicit rd: RestReadable[MT]): Future[Either[RestError, MT]] = {
    val cached = Cache.getAs[JsValue](id)
    if (cached.isDefined) {
      Future.successful(Right(cached.get.as[MT](rd.restReads)))
    } else {
      Logger.logger.debug("GET {} ", enc(requestUrl, id))
      WS.url(enc(requestUrl, id)).withHeaders(authHeaders.toSeq: _*).get.map { response =>
        checkErrorAndParse(response)(rd.restReads).right.map { entity =>
          Cache.set(id, response.json, cacheTime)
          entity
        }
      }
    }
  }

  def getJson(id: String): Future[Either[RestError, JsObject]] = {
    WS.url(enc(requestUrl, id)).withHeaders(authHeaders.toSeq: _*).get.map { response =>
      checkErrorAndParse[JsObject](response)
    }
  }

  def get(key: String, value: String)(implicit rd: RestReadable[MT]): Future[Either[RestError, MT]] = {
    WS.url(requestUrl).withHeaders(authHeaders.toSeq: _*)
        .withQueryString("key" -> key, "value" -> value)
        .get.map { response =>
      checkErrorAndParse(response)(rd.restReads)
    }
  }

  def create[T](item: T, accessors: List[String] = Nil,
      params: Map[String,Seq[String]] = Map(),
      logMsg: Option[String] = None)(implicit wrt: RestConvertable[T], rd: RestReadable[MT]): Future[Either[RestError, MT]] = {
    val qs = utils.joinQueryString(params)
    val url = enc(requestUrl, "?%s".format(
        (accessors.map(a => s"${RestPageParams.ACCESSOR_PARAM}=${a}") ++ List(qs)).mkString("&")))
    Logger.logger.debug("CREATE {} ", url)
    WS.url(url)
        .withHeaders(msgHeader(logMsg) ++ authHeaders.toSeq: _*)
      .post(Json.toJson(item)(wrt.restFormat)).map { response =>
        checkErrorAndParse(response)(rd.restReads).right.map { item =>
          EntityDAO.handleCreate(response.json.as[JsObject])
          item
        }
    }
  }

  def createInContext[T,TT](id: String, contentType: ContentType.Value, item: T, accessors: List[String] = Nil,
      logMsg: Option[String] = None)(
        implicit wrt: RestConvertable[T], rd: RestReadable[TT]): Future[Either[RestError, TT]] = {
    val url = enc(requestUrl, id, contentType, "?%s".format(
        accessors.map(a => s"${RestPageParams.ACCESSOR_PARAM}=${a}").mkString("&")))
    Logger.logger.debug("CREATE-IN {} ", url)
    WS.url(url)
        .withHeaders(msgHeader(logMsg) ++ authHeaders.toSeq: _*)
        .post(Json.toJson(item)(wrt.restFormat)).map { response =>
      checkErrorAndParse(response)(rd.restReads).right.map { item =>
        EntityDAO.handleCreate(response.json.as[JsObject])
        item
      }
    }
  }

  def update[T](id: String, item: T, logMsg: Option[String] = None)(
      implicit wrt: RestConvertable[T], rd: RestReadable[MT]): Future[Either[RestError, MT]] = {
    val url = enc(requestUrl, id)
    Logger.logger.debug("UPDATE: {}", url)
    WS.url(url).withHeaders(msgHeader(logMsg) ++ authHeaders.toSeq: _*)
        .put(Json.toJson(item)(wrt.restFormat)).map { response =>
      checkErrorAndParse(response)(rd.restReads).right.map { item =>
        EntityDAO.handleUpdate(response.json.as[JsObject])
        Cache.set(id, response.json)
        item
      }
    }
  }

  def delete(id: String, logMsg: Option[String] = None): Future[Either[RestError, Boolean]] = {
    val url = enc(requestUrl, id)
    Logger.logger.debug("DELETE {}", url)
    WS.url(url).withHeaders(authHeaders.toSeq: _*).delete.map { response =>
      // FIXME: Check actual error content...
      checkError(response).right.map(r => {
        EntityDAO.handleDelete(id)
        Cache.remove(id)
        r.status == OK
      })
    }
  }

  def listJson(params: RestPageParams = RestPageParams()): Future[Either[RestError, List[JsObject]]] = {
    val url = enc(requestUrl, "list" + params.toString)
    Logger.logger.debug("LIST: {}", url)
    WS.url(url)
        .withHeaders(authHeaders.toSeq: _*).get.map { response =>
      checkErrorAndParse[List[JsObject]](response)
    }
  }

  def list(params: RestPageParams = RestPageParams())(implicit rd: RestReadable[MT]): Future[Either[RestError, List[MT]]] = {
    val url = enc(requestUrl, "list" + params.toString)
    Logger.logger.debug("LIST: {}", url)
    WS.url(url)
        .withHeaders(authHeaders.toSeq: _*).get.map { response =>
      checkErrorAndParse(response)(Reads.list(rd.restReads))
    }
  }

  def listChildren[CMT](id: String, params: RestPageParams = RestPageParams())(
      implicit rd: RestReadable[CMT]): Future[Either[RestError, List[CMT]]] = {
    WS.url(enc(requestUrl, id, "list" + params.toString))
        .withHeaders(authHeaders.toSeq: _*).get.map { response =>
      checkErrorAndParse(response)(Reads.list(rd.restReads))
    }
  }

  def pageJson(params: RestPageParams = RestPageParams()): Future[Either[RestError, Page[JsObject]]] = {
    val url = enc(requestUrl, "page" + params.toString)
    Logger.logger.debug("PAGE: {}", url)
    WS.url(url).withHeaders(authHeaders.toSeq: _*).get.map { response =>
      checkErrorAndParse(response)(Page.pageReads[JsObject])
    }
  }

  def page(params: RestPageParams = RestPageParams())(implicit rd: RestReadable[MT]): Future[Either[RestError, Page[MT]]] = {
    val url = enc(requestUrl, "page" + params.toString)
    Logger.logger.debug("PAGE: {}", url)
    WS.url(url).withHeaders(authHeaders.toSeq: _*).get.map { response =>
      checkErrorAndParse(response)(Page.pageReads(rd.restReads))
    }
  }

  def pageChildren[CMT](id: String, params: RestPageParams = RestPageParams())(implicit rd: RestReadable[CMT]): Future[Either[RestError, Page[CMT]]] = {
    WS.url(enc(requestUrl, id, "page" + params.toString))
        .withHeaders(authHeaders.toSeq: _*).get.map { response =>
      checkErrorAndParse(response)(Page.pageReads(rd.restReads))
    }
  }

  def count(params: RestPageParams = RestPageParams()): Future[Either[RestError, Long]] = {
    WS.url(enc(requestUrl, "count" + params.toString))
        .withHeaders(authHeaders.toSeq: _*).get.map { response =>
      checkErrorAndParse[Long](response)
    }
  }

  def countChildren(id: String, params: RestPageParams = RestPageParams()): Future[Either[RestError, Long]] = {
    WS.url(enc(requestUrl, id, "count" + params.toString))
        .withHeaders(authHeaders.toSeq: _*).get.map { response =>
      checkErrorAndParse[Long](response)
    }
  }
}