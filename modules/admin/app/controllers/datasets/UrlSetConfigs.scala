package controllers.datasets

import actors.harvesting.UrlSetHarvester.{UrlSetData, UrlSetJob}
import actors.harvesting.{HarvesterManager, UrlSetHarvester}
import akka.actor.{ActorContext, Props}
import akka.stream.Materializer
import akka.stream.scaladsl.Sink
import controllers.AppComponents
import controllers.base.AdminController
import controllers.generic.Update
import models.{FileStage, Repository, UrlSetConfig}
import play.api.libs.json.Json
import play.api.libs.ws.WSClient
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import services.harvesting.{HarvestEventService, UrlSetConfigService}
import services.storage.FileStorage

import java.util.UUID
import javax.inject.{Inject, Named, Singleton}
import scala.concurrent.Future

@Singleton
case class UrlSetConfigs @Inject()(
  controllerComponents: ControllerComponents,
  @Named("dam") storage: FileStorage,
  appComponents: AppComponents,
  ws: WSClient,
  configs: UrlSetConfigService,
  harvestEvents: HarvestEventService,
)(implicit mat: Materializer) extends AdminController with StorageHelpers with Update[Repository] {

  def get(id: String, ds: String): Action[AnyContent] = EditAction(id).async { implicit request =>
    configs.get(id, ds).map { opt =>
      Ok(Json.toJson(opt))
    }
  }

  def save(id: String, ds: String): Action[UrlSetConfig] = EditAction(id).async(parse.json[UrlSetConfig]) { implicit request =>
    configs.save(id, ds, request.body).map { r =>
      Ok(Json.toJson(r))
    }
  }

  def delete(id: String, ds: String): Action[AnyContent] = EditAction(id).async { implicit request =>
    configs.delete(id, ds).map(_ => NoContent)
  }

  def test(id: String, ds: String): Action[UrlSetConfig] = WithUserAction.async(parse.json[UrlSetConfig]) { implicit request =>
    ???
  }

  def sync(id: String, ds: String): Action[UrlSetConfig] = EditAction(id).apply(parse.json[UrlSetConfig]) { implicit request =>
    val endpoint = request.body
    val jobId = UUID.randomUUID().toString
    val data = UrlSetData(endpoint, prefix = prefix(id, ds, FileStage.Input))
    val job = UrlSetJob(id, ds, jobId, data = data)
    val init = (context: ActorContext) => context.actorOf(Props(UrlSetHarvester(ws, storage)))
    mat.system.actorOf(Props(HarvesterManager(job, init, harvestEvents)), jobId)

    Ok(Json.obj(
      "url" -> controllers.admin.routes.Tasks
        .taskMonitorWS(jobId).webSocketURL(conf.https),
      "jobId" -> jobId
    ))
  }

  def clean(id: String, ds: String): Action[UrlSetConfig] = WithUserAction.async(parse.json[UrlSetConfig]) { implicit request =>
    val pre = prefix(id, ds, FileStage.Input)
    val remote: Set[String] = request.body.urls.map(_.name).toSet

    val storedF: Future[Set[String]] = storage.streamFiles(Some(pre))
      .map(fm => fm.key.replace(pre, ""))
      .runWith(Sink.seq)
      .map(_.toSet)

    val diffF = for { stored <- storedF } yield stored -- remote
    diffF.map { diff => Ok(Json.toJson(diff)) }.recover {
      case e: Exception => BadRequest(Json.obj("error" -> e.getMessage))
    }
  }
}
