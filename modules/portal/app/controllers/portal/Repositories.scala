package controllers.portal

import auth.AccountManager
import backend.DataApi
import backend.rest.cypher.Cypher
import com.google.inject.{Inject, Singleton}
import controllers.generic.Search
import controllers.portal.base.{Generic, PortalController}
import defines.EntityType
import models.{Repository, DocumentaryUnit}
import play.api.cache.CacheApi
import play.api.http.{HeaderNames, ContentTypes}
import play.api.i18n.MessagesApi
import play.api.libs.concurrent.Execution.Implicits._
import play.api.mvc.RequestHeader
import utils.MovedPageLookup
import utils.search._
import views.MarkdownRenderer

import scala.concurrent.Future.{successful => immediate}

@Singleton
case class Repositories @Inject()(
  implicit config: play.api.Configuration,
  cache: CacheApi,
  globalConfig: global.GlobalConfig,
  searchEngine: SearchEngine,
  searchResolver: SearchItemResolver,
  dataApi: DataApi,
  accounts: AccountManager,
  pageRelocator: MovedPageLookup,
  messagesApi: MessagesApi,
  markdown: MarkdownRenderer,
  cypher: Cypher
) extends PortalController
  with Generic[Repository]
  with Search
  with FacetConfig {

  private val portalRepoRoutes = controllers.portal.routes.Repositories

  private def filters(id: String)(implicit request: RequestHeader): Map[String,Any] =
    (if (!hasActiveQuery(request)) Map(SearchConstants.TOP_LEVEL -> true)
      else Map.empty[String,Any]) ++ Map(SearchConstants.HOLDER_ID -> id)


  def searchAll = UserBrowseAction.async { implicit request =>
    findType[Repository](
      facetBuilder = repositorySearchFacets
    ).map { result =>
      Ok(views.html.repository.list(result, portalRepoRoutes.searchAll(),
        request.watched))
    }
  }

  def searchAllByCountry = UserBrowseAction.async { implicit request =>
    findType[Repository](
      defaultParams = SearchParams(sort = Some(SearchOrder.Country)),
      facetBuilder = repositorySearchFacets
    ).map { result =>
      Ok(views.html.repository.listByCountry(result,
        portalRepoRoutes.searchAllByCountry(),
        request.watched))
    }
  }

  def browse(id: String) = GetItemAction(id).async { implicit request =>
    if (isAjax) immediate(Ok(views.html.repository.itemDetails(request.item, request.annotations, request.links, request.watched)))
    else findType[DocumentaryUnit](
      filters = filters(request.item.id),
      facetBuilder = localDocFacets,
      defaultOrder = SearchOrder.Id
    ).map { result =>
      Ok(views.html.repository.show(request.item, result, request.annotations,
        request.links, portalRepoRoutes.search(id), request.watched))
    }
  }

  def search(id: String) = GetItemAction(id).async { implicit request =>
    findType[DocumentaryUnit](
      filters = filters(request.item.id),
      facetBuilder = localDocFacets,
      defaultOrder = SearchOrder.Id
    ).map { result =>
      if (isAjax) Ok(views.html.repository.childItemSearch(request.item, result,
        portalRepoRoutes.search(id), request.watched))
      else Ok(views.html.repository.search(request.item, result,
        portalRepoRoutes.search(id), request.watched))
    }
  }

  def export(id: String) = OptionalUserAction.async { implicit request =>
    val format = "eag" // Hardcoded for now!
    val params = request.queryString.filterKeys(_ == "lang")
    userDataApi.stream(s"classes/${EntityType.Repository}/$id/$format", params = params).map { sr =>
      val ct = sr.headers.headers.get(HeaderNames.CONTENT_TYPE)
        .flatMap(_.headOption).getOrElse(ContentTypes.XML)
      Status(sr.headers.status).chunked(sr.body).as(ct)
        .withHeaders(sr.headers.headers.map(s => (s._1, s._2.head)).toSeq: _*)
    }
  }
}
