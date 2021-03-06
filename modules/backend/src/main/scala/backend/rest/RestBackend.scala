package backend.rest

import javax.inject.Inject
import play.api.libs.iteratee.Enumerator

import scala.concurrent.{ExecutionContext, Future}
import play.api.mvc.Headers
import play.api.cache.CacheApi
import play.api.libs.ws.{WSResponseHeaders, WSClient, WSResponse}
import backend._


/**
  * @author Mike Bryant (http://github.com/mikesname)
  */
case class RestBackend @Inject ()(eventHandler: EventHandler, cache: CacheApi, config: play.api.Configuration, ws: WSClient)
  extends Backend {
  def withContext(apiUser: ApiUser)(implicit executionContext: ExecutionContext) = new RestBackendHandle(eventHandler)(cache: CacheApi, config, apiUser, executionContext, ws)
}

case class RestBackendHandle(eventHandler: EventHandler)(
  implicit val cache: CacheApi,
  val config: play.api.Configuration,
  val apiUser: ApiUser,
  val executionContext: ExecutionContext,
  val ws: WSClient
) extends BackendHandle
  with RestGeneric
  with RestPermissions
  with RestDescriptions
  with RestAnnotations
  with RestVirtualCollections
  with RestLinks
  with RestEvents
  with RestSocial
  with RestVisibility
  with RestPromotion {

  private val admin = new AdminDAO(eventHandler, cache, config, ws)

  override def withEventHandler(eventHandler: EventHandler) = this.copy(eventHandler = eventHandler)

  // Direct API query
  override def query(urlPart: String, headers: Headers = Headers(), params: Map[String,Seq[String]] = Map.empty): Future[WSResponse] =
    userCall(enc(baseUrl, urlPart) + (if(params.nonEmpty) "?" + joinQueryString(params) else ""))
      .withHeaders(headers.headers: _*).get()

  override def stream(urlPart: String, headers: Headers = Headers(), params: Map[String,Seq[String]] = Map.empty): Future[(WSResponseHeaders, Enumerator[Array[Byte]])] =
    userCall(enc(baseUrl, urlPart) + (if(params.nonEmpty) "?" + joinQueryString(params) else ""))
      .withHeaders(headers.headers: _*).withMethod("GET").stream()

  // Helpers
  override def createNewUserProfile[T <: WithId](data: Map[String,String] = Map.empty, groups: Seq[String] = Seq.empty)(implicit rd: Readable[T]): Future[T] =
    admin.createNewUserProfile[T](data, groups)

  // Fetch any type of object. This doesn't really belong here...
  override def getAny[MT](id: String)(implicit rd: Readable[MT]): Future[MT] = {
    val url: String = enc(baseUrl, "entities", id)
    BackendRequest(url).withHeaders(authHeaders.toSeq: _*).get().map { response =>
      checkErrorAndParse(response, context = Some(url))(rd.restReads)
    }
  }
}

object RestBackend {
  def withNoopHandler(cache: CacheApi, config: play.api.Configuration, ws: WSClient): Backend = new RestBackend(new EventHandler {
    def handleCreate(id: String) = ()
    def handleUpdate(id: String) = ()
    def handleDelete(id: String) = ()
  }, cache: CacheApi, config, ws)
}
