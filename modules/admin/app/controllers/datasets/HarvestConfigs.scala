package controllers.datasets

import actors.harvesting.OaiPmhHarvester.{OaiPmhHarvestData, OaiPmhHarvestJob}
import actors.harvesting.ResourceSyncHarvester.{ResourceSyncData, ResourceSyncJob}
import actors.harvesting.UrlSetHarvester.{UrlSetData, UrlSetJob}
import actors.harvesting.{HarvesterManager, OaiPmhHarvester, ResourceSyncHarvester, UrlSetHarvester}
import akka.actor.{ActorContext, Props}
import akka.http.scaladsl.model.Uri
import akka.stream.Materializer
import akka.stream.scaladsl.Sink
import controllers.AppComponents
import controllers.base.AdminController
import controllers.generic.Update
import models.HarvestEvent.HarvestEventType
import models._
import play.api.http.{HeaderNames, MimeTypes}
import play.api.i18n.Messages
import play.api.libs.json.Json
import play.api.libs.ws.{WSAuthScheme, WSClient, WSRequest}
import play.api.mvc._
import services.datasets.ImportDatasetService
import services.harvesting._
import services.storage.FileStorage

import java.time.Instant
import java.util.UUID
import javax.inject.{Inject, Named, Singleton}
import scala.concurrent.Future
import scala.concurrent.Future.{successful => immediate}

@Singleton
case class HarvestConfigs @Inject()(
  controllerComponents: ControllerComponents,
  @Named("dam") storage: FileStorage,
  appComponents: AppComponents,
  datasets: ImportDatasetService,
  oaipmhConfigs: OaiPmhConfigService,
  oaiPmhClient: OaiPmhClient,
  rsConfigs: ResourceSyncConfigService,
  rsClient: ResourceSyncClient,
  urlSetConfigs: UrlSetConfigService,
  ws: WSClient,
  harvestEvents: HarvestEventService,
)(implicit mat: Materializer) extends AdminController with StorageHelpers with Update[Repository] {

  val ALLOWED_TYPES = Seq("text/xml", "application/xml")

  case class DatasetRequest[A](
    item: Repository,
    userOpt: Option[UserProfile],
    dataset: ImportDataset,
    request: Request[A]
  ) extends WrappedRequest[A](request)
    with WithOptionalUser

  private def DatasetAction(id: String, ds: String): ActionBuilder[DatasetRequest, AnyContent] =
    EditAction(id) andThen new CoreActionTransformer[ItemPermissionRequest, DatasetRequest] {
      override protected def transform[A](request: ItemPermissionRequest[A]): Future[DatasetRequest[A]] = {
        implicit val req: ItemPermissionRequest[A] = request
        datasets.get(id, ds).map { dataset =>
          DatasetRequest(req.item, req.userOpt, dataset, req)
        }
      }
    }

  private def withCheckedPayload(config: HarvestConfig, dataset: ImportDataset)(f: HarvestConfig => Future[Result]): Future[Result] = {
    if (dataset.src == config.src) f(config)
    else immediate(BadRequest(Json.obj("error" -> s"body does not match dataset source type: ${dataset.src}")))
  }

  def get(id: String, ds: String): Action[AnyContent] = DatasetAction(id, ds).async { implicit request =>
    val configF: Future[Option[HarvestConfig]] = request.dataset.src match {
      case ImportDataset.Src.OaiPmh => oaipmhConfigs.get(id, ds)
      case ImportDataset.Src.Rs => rsConfigs.get(id, ds)
      case ImportDataset.Src.UrlSet => urlSetConfigs.get(id, ds)
      case _ => immediate(Option.empty[HarvestConfig])
    }
    configF.map(r => Ok(Json.toJson(r)))
  }

  def save(id: String, ds: String): Action[HarvestConfig] = DatasetAction(id, ds).async(parse.json[HarvestConfig]) { implicit request =>
    withCheckedPayload(request.body, request.dataset) {
      case c: OaiPmhConfig => oaipmhConfigs.save(id, ds, c).map(r => Ok(Json.toJson(r)))
      case c: ResourceSyncConfig => rsConfigs.save(id, ds, c).map(r => Ok(Json.toJson(r)))
      case c: UrlSetConfig => urlSetConfigs.save(id, ds, c).map(r => Ok(Json.toJson(r)))
      case _ => immediate(BadRequest(Json.obj("error" -> "unsupported config type: ")))
    }
  }

  def delete(id: String, ds: String): Action[AnyContent] = DatasetAction(id, ds).async { implicit request =>
    request.dataset.src match {
      case ImportDataset.Src.OaiPmh => oaipmhConfigs.delete(id, ds).map(_ => NoContent)
      case ImportDataset.Src.Rs => rsConfigs.delete(id, ds).map(_ => NoContent)
      case ImportDataset.Src.UrlSet => urlSetConfigs.delete(id, ds).map(_ => NoContent)
      case _ => immediate(BadRequest(Json.obj("error" -> s"unsupported config type: ${request.dataset.src}")))
    }
  }

  def clean(id: String, ds: String): Action[HarvestConfig] = DatasetAction(id, ds).async(parse.json[HarvestConfig]) { implicit request =>
    withCheckedPayload(request.body, request.dataset) {
      case c: ResourceSyncConfig => cleanRs(id, ds, c)
      case c: UrlSetConfig => cleanUrlSet(id, ds, c)
      case _ => immediate(BadRequest(Json.obj("error" -> s"unsupported config type: ${request.body.src}")))
    }
  }

  def test(id: String, ds: String): Action[HarvestConfig] = DatasetAction(id, ds).async(parse.json[HarvestConfig]) { implicit request =>
    withCheckedPayload(request.body, request.dataset) {
      case c: OaiPmhConfig => testOaiPmh(c)
      case c: ResourceSyncConfig => testRs(c)
      case c: UrlSetConfig => testUrlSet(c)
      case _ => immediate(BadRequest(Json.obj("error" -> s"unsupported config type: ${request.body.src}")))
    }
  }

  def harvest(id: String, ds: String, fromLast: Boolean): Action[HarvestConfig] = DatasetAction(id, ds).async(parse.json[HarvestConfig]) { implicit request =>
    withCheckedPayload(request.body, request.dataset) {
      case c: OaiPmhConfig => harvestOaiPmh(id, ds, c, fromLast)
      case c: ResourceSyncConfig => harvestRs(id, ds, c)
      case c: UrlSetConfig => harvestUrlSet(id, ds, c)
      case _ => immediate(BadRequest(Json.obj("error" -> s"unsupported config type: ${request.body.src}")))
    }
  }

  private def testOaiPmh(config: OaiPmhConfig)(implicit messages: Messages): Future[Result] = {
    val getIdentF = oaiPmhClient.identify(config)
    val listIdentF = oaiPmhClient.listIdentifiers(config)
    (for (ident <- getIdentF; _ <- listIdentF)
      yield Ok(Json.toJson(ident))).recover {
      case e: OaiPmhError => BadRequest(Json.obj("error" -> e.errorMessage))
      case e => InternalServerError(Json.obj("error" -> e.getMessage))
    }
  }

  def testRs(config: ResourceSyncConfig): Future[Result] = {
    val wSRequest = ws.url(config.url)
    config.auth.fold(wSRequest) { auth =>
      wSRequest.withAuth(auth.username, auth.password, WSAuthScheme.BASIC)
    }.head().map { r =>
      checkRemoteFile(r).fold(Ok(Json.obj("ok" -> true))) { err =>
        BadRequest(Json.obj("error" -> err))
      }
    }
  }

  def testUrlSet(config: UrlSetConfig): Future[Result] = {
    def req(url: String): Future[Option[(String, String)]] = {
      val wsRequest = ws.url(url)
      config.auth.fold(wsRequest) { auth =>
        wsRequest.withAuth(auth.username, auth.password, WSAuthScheme.BASIC)
      }.head().map{ r =>
        checkRemoteFile(r).map(err => url -> err)
      }
    }

    val errs: Seq[Future[Option[(String, String)]]] = config.urls.map(um => req(um.url))
    val s: Future[Option[String]] = Future.sequence(errs).map { errs =>
      errs.collectFirst {
        case Some((url, err)) => s"$url: $err"
      }
    }

    s.map { errOpt =>
      errOpt.fold(Ok(Json.obj("ok" -> true))) { err =>
        BadRequest(Json.obj("error" -> err))
      }
    }
  }

  def cleanRs(id: String, ds: String, config: ResourceSyncConfig)(implicit req: RequestHeader): Future[Result] = {
    val pre = prefix(id, ds, FileStage.Input)
    val remoteF: Future[Set[String]] = rsClient.list(config).map { links =>
      links.map(item => Uri(item.loc).path.dropChars(1).toString).toSet
    }

    cleanFiles(pre, remoteF).map { diff => Ok(Json.toJson(diff)) }.recover {
      case e: ResourceSyncError => BadRequest(Json.obj("error" -> e.errorMessage))
    }
  }

  def cleanUrlSet(id: String, ds: String, config: UrlSetConfig)(implicit req: RequestHeader): Future[Result] = {
    val pre = prefix(id, ds, FileStage.Input)
    val remoteF: Future[Set[String]] = immediate(config.urls.map(_.name).toSet)

    cleanFiles(pre, remoteF).map { diff => Ok(Json.toJson(diff)) }.recover {
      case e: Exception => BadRequest(Json.obj("error" -> e.getMessage))
    }
  }

  def harvestRs(id: String, ds: String, endpoint: ResourceSyncConfig)(implicit req: RequestHeader, userOpt: Option[UserProfile]): Future[Result] = immediate {
    val jobId = UUID.randomUUID().toString
    val data = ResourceSyncData(endpoint, prefix = prefix(id, ds, FileStage.Input))
    val job = ResourceSyncJob(id, ds, jobId, data = data)
    val init = (context: ActorContext) => context.actorOf(Props(ResourceSyncHarvester(rsClient, storage)))
    mat.system.actorOf(Props(HarvesterManager(job, init, harvestEvents)), jobId)

    Ok(Json.obj(
      "url" -> controllers.admin.routes.Tasks
        .taskMonitorWS(jobId).webSocketURL(conf.https),
      "jobId" -> jobId
    ))
  }

  def harvestOaiPmh(id: String, ds: String, config: OaiPmhConfig, fromLast: Boolean)(implicit req: RequestHeader, userOpt: Option[UserProfile]): Future[Result] = {
    val lastHarvest: Future[Option[Instant]] =
      if (fromLast) harvestEvents.get(id, Some(ds)).map(events =>
        events
          .filter(_.eventType == HarvestEventType.Completed)
          .map(_.created)
          .lastOption
      ) else immediate(Option.empty[Instant])

    lastHarvest.map { last =>
      val jobId = UUID.randomUUID().toString
      val data = OaiPmhHarvestData(config, prefix = prefix(id, ds, FileStage.Input), from = last)
      val job = OaiPmhHarvestJob(id, ds, jobId, data = data)
      val init = (context: ActorContext) => context.actorOf(Props(OaiPmhHarvester(oaiPmhClient, storage)))
      mat.system.actorOf(Props(HarvesterManager(job, init, harvestEvents)), jobId)

      Ok(Json.obj(
        "url" -> controllers.admin.routes.Tasks
          .taskMonitorWS(jobId).webSocketURL(conf.https),
        "jobId" -> jobId
      ))
    }
  }

  def harvestUrlSet(id: String, ds: String, config: UrlSetConfig)(implicit req: RequestHeader, userOpt: Option[UserProfile]): Future[Result] = immediate {
    val jobId = UUID.randomUUID().toString
    val data = UrlSetData(config, prefix = prefix(id, ds, FileStage.Input))
    val job = UrlSetJob(id, ds, jobId, data = data)
    val init = (context: ActorContext) => context.actorOf(Props(UrlSetHarvester(ws, storage)))
    mat.system.actorOf(Props(HarvesterManager(job, init, harvestEvents)), jobId)

    Ok(Json.obj(
      "url" -> controllers.admin.routes.Tasks
        .taskMonitorWS(jobId).webSocketURL(conf.https),
      "jobId" -> jobId
    ))
  }

  private def cleanFiles(pre: String, remoteF: Future[Set[String]]): Future[Set[String]] = {
    val storedF: Future[Set[String]] = storage.streamFiles(Some(pre))
      .map(fm => fm.key.replace(pre, ""))
      .runWith(Sink.seq)
      .map(_.toSet)

    for { stored <- storedF; remote <- remoteF}
      yield stored -- remote
  }

  private def checkRemoteFile(r: WSRequest#Response): Option[String] = {
    if (r.status != 200)
      Some(s"Unexpected HTTP response status code: ${r.status}")
    else if (r.header(HeaderNames.CONTENT_LENGTH).map(_.toLong).getOrElse(0L) <= 0)
      Some("Changelist is of zero or unknown length")
    else if (!ALLOWED_TYPES.exists(r.contentType.toLowerCase.startsWith)) {
      Some(s"Unknown or unexpected content type: '${r.contentType}'")
    } else None
  }
}
