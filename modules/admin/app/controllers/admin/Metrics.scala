package controllers.admin

import auth.AccountManager
import client.json.ClientWriteable
import play.api.libs.concurrent.Execution.Implicits._
import models.Isaar
import models.base.{Description, AnyModel}
import controllers.generic.Search
import defines.EntityType
import play.api.i18n.{MessagesApi, Messages}
import play.api.libs.json.{Writes, Json}
import play.api.mvc.{AnyContent, Request, Result}
import utils.{Page, search}
import views.Helpers
import utils.search._

import javax.inject._
import play.api.cache.{CacheApi, Cached}
import backend.Backend
import controllers.base.AdminController


@Singleton
case class Metrics @Inject()(
  implicit app: play.api.Application,
  cache: CacheApi,
  globalConfig: global.GlobalConfig,
  searchEngine: SearchEngine,
  searchResolver: SearchItemResolver,
  backend: Backend,
  accounts: AccountManager,
  pageRelocator: utils.MovedPageLookup,
  messagesApi: MessagesApi
) extends AdminController
  with Search {

  private val metricCacheTime = 60 * 60 // 1 hour

  private val statusCached = new Cached(cache)

  val searchEntities = List(
    EntityType.DocumentaryUnit,
    EntityType.Repository,
    EntityType.HistoricalAgent
  )

  private def jsonResponse[T](result: SearchResult[(T, SearchHit)])(implicit request: Request[AnyContent], w: ClientWriteable[T]): Result = {
    render {
      case Accepts.Json() | Accepts.JavaScript() => Ok(Json.obj(
        "page" -> Json.toJson(result.mapItems(_._1).page)(Page.pageWrites(w.clientFormat)),
        "params" -> Json.toJson(result.params),
        "appliedFacets" -> Json.toJson(result.facets),
        "facetClasses" -> Json.toJson(result.facetClasses)
      )).as(play.api.http.ContentTypes.JSON)
      case _ => UnsupportedMediaType
    }
  }

  // For all of the metrics we're just using facet counts,
  // so set the result limit to be zero.
  private val defaultParams = SearchParams(count=Some(0))

  private val langCountFacets: FacetBuilder = { implicit request =>
    List(
      FieldFacetClass(
        key=Description.LANG_CODE,
        name=Messages("documentaryUnit." + Description.LANG_CODE),
        param="lang",
        render= (s: String) => Helpers.languageCodeToName(s)
      )
    )
  }

  def languageOfMaterial = statusCached.status(_ => "pages:langMetric", OK, metricCacheTime) {
    OptionalUserAction.async { implicit request =>
      find[AnyModel](
        defaultParams = defaultParams,
        entities = List(EntityType.DocumentaryUnit),
        facetBuilder = langCountFacets
      ).map(jsonResponse[AnyModel])
    }
  }


  private val holdingRepoFacets: FacetBuilder = { implicit request =>
    List(
      // Holding repository
      FieldFacetClass(
        key="repositoryName",
        name=Messages("documentaryUnit.heldBy"),
        param="holder"
      )
    )
  }

  def holdingRepository = statusCached.status(_ => "pages:repoMetric", OK, metricCacheTime) {
    OptionalUserAction.async { implicit request =>
      find[AnyModel](
        defaultParams = defaultParams,
        entities = List(EntityType.DocumentaryUnit),
        facetBuilder = holdingRepoFacets
      ).map(jsonResponse[AnyModel])
    }
  }

  private val countryRepoFacets: FacetBuilder = { implicit request =>
    List(

      // Repositories by country
      FieldFacetClass(
        key="countryCode",
        name=Messages("repository.countryCode"),
        param="country",
        render=Helpers.countryCodeToName
      )
    )
  }

  def repositoryCountries = statusCached.status(_ => "pages:repoCountryMetric", OK, metricCacheTime) {
    OptionalUserAction.async { implicit request =>
      find[AnyModel](
        defaultParams = defaultParams,
        entities = List(EntityType.Repository),
        facetBuilder = countryRepoFacets
      ).map(jsonResponse[AnyModel])
    }
  }

  private val restrictedFacets: FacetBuilder = { implicit request =>
    List(
      // Historical agent type
      FieldFacetClass(
        key=search.SearchConstants.RESTRICTED_FIELD,
        name=Messages("search.isRestricted"),
        param="restricted",
        render=s => Messages("restricted" + "." + s)
      )
    )
  }

  def restricted = statusCached.status(_ => "pages:restrictedMetric", OK, metricCacheTime) {
    OptionalUserAction.async { implicit request =>
      find[AnyModel](
        defaultParams = defaultParams,
        entities = List(EntityType.HistoricalAgent,
            EntityType.DocumentaryUnit, EntityType.HistoricalAgent),
        facetBuilder = restrictedFacets
      ).map(jsonResponse[AnyModel])
    }
  }


  private val agentTypeFacets: FacetBuilder = { implicit request =>
    List(
      // Historical agent type
      FieldFacetClass(
        key=models.Isaar.ENTITY_TYPE,
        name=Messages("historicalAgent." + Isaar.ENTITY_TYPE),
        param="cpf",
        render=s => Messages("historicalAgent." + s)
      )
    )
  }

  def agentTypes = statusCached.status(_ => "pages:agentTypeMetric", OK, metricCacheTime) {
    OptionalUserAction.async { implicit request =>
      find[AnyModel](
        entities = List(EntityType.HistoricalAgent),
        facetBuilder = agentTypeFacets
      ).map(jsonResponse[AnyModel])
    }
  }

  def clearCached = AdminAction { implicit request =>
    // Hack around lack of manual expiry
    cache.remove("pages:agentTypeMetric")
    cache.remove("pages:restrictedMetric")
    cache.remove("pages:repoCountryMetric")
    cache.remove("pages:repoMetric")
    cache.remove("pages:langMetric")
    Redirect(controllers.admin.routes.Home.index())
  }
}
