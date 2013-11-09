package rest

import play.api.libs.concurrent.Execution.Implicits._
import scala.concurrent.Future
import play.api.libs.json._
import defines.EntityType
import play.api.Play.current
import play.api.cache.Cache
import models.json.{RestResource, RestReadable, RestConvertable}
import models.base.AnyModel
import play.api.Logger


/**
 * Data Access Object for managing descriptions on entities.
 */
case class DescriptionDAO(eventHandler: RestEventHandler) extends RestDAO {

  private val entities = new EntityDAO(eventHandler)

  def requestUrl = "http://%s:%d/%s/description".format(host, port, mount)

  def createDescription[MT,DT](id: String, item: DT, logMsg: Option[String] = None)(
        implicit apiUser: ApiUser, rs: RestResource[MT], fmt: RestConvertable[DT], rd: RestReadable[MT]): Future[MT] = {
    userCall(enc(requestUrl, id)).withHeaders(msgHeader(logMsg): _*)
        .post(Json.toJson(item)(fmt.restFormat)).flatMap { response =>
      checkError(response)
      entities.get(id).map { item =>
        eventHandler.handleUpdate(id)
        Cache.remove(id)
        item
      }
    }
  }

  def updateDescription[MT,DT](id: String, did: String, item: DT, logMsg: Option[String] = None)(
      implicit apiUser: ApiUser, rs: RestResource[MT], fmt: RestConvertable[DT], rd: RestReadable[MT]): Future[MT] = {
    userCall(enc(requestUrl, id, did)).withHeaders(msgHeader(logMsg): _*)
        .put(Json.toJson(item)(fmt.restFormat)).flatMap { response =>
      checkError(response)
      entities.get(id).map { item =>
        eventHandler.handleUpdate(id)
        Cache.remove(id)
        item
      }
    }
  }

  def deleteDescription[MT](id: String, did: String, logMsg: Option[String] = None)(
      implicit apiUser: ApiUser, rs: RestResource[MT], rd: RestReadable[MT]): Future[Boolean] = {
    userCall(enc(requestUrl, id, did)).withHeaders(msgHeader(logMsg): _*)
          .delete.map { response =>
      checkError(response)
      eventHandler.handleDelete(did)
      Cache.remove(id)
      true
    }
  }

  def createAccessPoint[MT,DT](id: String, did: String, item: DT, logMsg: Option[String] = None)(
        implicit apiUser: ApiUser, rs: RestResource[MT], fmt: RestConvertable[DT], rd: RestReadable[MT]): Future[(MT,DT)] = {
    userCall(enc(requestUrl, id, did, EntityType.AccessPoint.toString))
        .withHeaders(msgHeader(logMsg): _*)
        .post(Json.toJson(item)(fmt.restFormat)).flatMap { response =>
      entities.get(id).map { item =>
        eventHandler.handleUpdate(id)
        Cache.remove(id)
        (item, checkErrorAndParse[DT](response)(fmt.restFormat))
      }
    }
  }

  def deleteAccessPoint[MT <: AnyModel](id: String, did: String, apid: String, logMsg: Option[String] = None)(
        implicit apiUser: ApiUser, rs: RestResource[MT], rd: RestReadable[MT]): Future[MT] = {
    userCall(enc(requestUrl, id, did, apid)).withHeaders(msgHeader(logMsg): _*)
      .delete.flatMap { response =>
      entities.get(id).map { item =>
        eventHandler.handleUpdate(id)
        Cache.remove(id)
        item
      }
    }
  }

  def deleteAccessPoint(id: String, logMsg: Option[String] = None)(implicit apiUser: ApiUser): Future[Boolean] = {
    val url = enc(requestUrl, "accessPoint", id)
    Logger.logger.debug(s"DELETE ACCESS POINT $url")
    userCall(url).withHeaders(msgHeader(logMsg): _*).delete.map { response =>
      checkError(response)
      eventHandler.handleDelete(id)
      true
    }
  }
}
