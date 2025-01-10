package guice

import com.codahale.metrics.{ConsoleReporter, CsvReporter, MetricRegistry}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.Materializer
import com.google.inject.AbstractModule
import com.google.inject.name.Names
import views.AppConfig
import data.markdown.{CommonmarkMarkdownRenderer, RawMarkdownRenderer, SanitisingMarkdownRenderer}
import lifecycle.{GeocodingItemLifecycle, ItemLifecycle}
import play.api.libs.concurrent.PekkoGuiceSupport
import services.RateLimitChecker
import services.cypher.{CypherQueryService, CypherService, SqlCypherQueryService, WsCypherService}
import services.data._
import services.feedback.{FeedbackService, SqlFeedbackService}
import services.geocoding.{AwsGeocodingService, GeocodingService}
import services.htmlpages.{GoogleDocsHtmlPages, HtmlPages}
import services.redirects.{MovedPageLookup, SqlMovedPageLookup}
import services.search._
import services.storage.{FileStorage, S3CompatibleFileStorage}
import views.html.MarkdownRenderer

import javax.inject.{Inject, Provider, Singleton}
import scala.concurrent.ExecutionContext

private class PortalStorageProvider @Inject()(config: play.api.Configuration)(implicit as: ActorSystem, mat: Materializer, ec: ExecutionContext) extends Provider[FileStorage] {
  override def get(): FileStorage =
    S3CompatibleFileStorage(config.get[com.typesafe.config.Config]("storage.portal"))
}

private class DamStorageProvider @Inject()(config: play.api.Configuration)(implicit as: ActorSystem, mat: Materializer, ec: ExecutionContext) extends Provider[FileStorage] {
  override def get(): FileStorage =
    S3CompatibleFileStorage(config.get[com.typesafe.config.Config]("storage.dam"))
}

private class AwsGeocodingServiceProvider @Inject()(config: play.api.Configuration, ec: ExecutionContext) extends Provider[GeocodingService] {
  override def get(): GeocodingService = AwsGeocodingService(config.get[com.typesafe.config.Config]("services.geocoding"))(ec)
}

@Singleton
private class MetricsRegistryProvider @Inject()(config: play.api.Configuration) extends Provider[MetricRegistry] {
  override def get(): MetricRegistry = {
    val registry = new MetricRegistry()

    val csvDir = config.get[Option[String]]("metrics.dir")

    csvDir.map { dir =>
      // Log metrics to CSV file if defined in the configuration
      val csvReporter = CsvReporter.forRegistry(registry)
        .convertRatesTo(java.util.concurrent.TimeUnit.SECONDS)
        .convertDurationsTo(java.util.concurrent.TimeUnit.MILLISECONDS)
        .build(new java.io.File(dir))
      csvReporter.start(5, java.util.concurrent.TimeUnit.SECONDS)
      println(s"Logging metrics to $dir")
    }.getOrElse {
      // Log metrics to console
      val consoleReporter = ConsoleReporter.forRegistry(registry)
        .convertRatesTo(java.util.concurrent.TimeUnit.SECONDS)
        .convertDurationsTo(java.util.concurrent.TimeUnit.MILLISECONDS)
        .build()
      consoleReporter.start(5, java.util.concurrent.TimeUnit.MINUTES)
    }

    registry
  }
}

class AppModule extends AbstractModule with PekkoGuiceSupport {
  override def configure(): Unit = {
    bind(classOf[AppConfig])
    bind(classOf[RateLimitChecker])
    bind(classOf[EventHandler]).to(classOf[IndexingEventHandler])
    bind(classOf[DataServiceBuilder]).to(classOf[WsDataServiceBuilder])
    bind(classOf[FeedbackService]).to(classOf[SqlFeedbackService])
    bind(classOf[CypherQueryService]).to(classOf[SqlCypherQueryService])
    bind(classOf[IdGenerator]).to(classOf[CypherIdGenerator])
    bind(classOf[MovedPageLookup]).to(classOf[SqlMovedPageLookup])
    bind(classOf[FileStorage]).toProvider(classOf[PortalStorageProvider])
    bind(classOf[FileStorage]).annotatedWith(Names.named("dam")).toProvider(classOf[DamStorageProvider])
    bind(classOf[HtmlPages]).to(classOf[GoogleDocsHtmlPages])
    bind(classOf[RawMarkdownRenderer]).to(classOf[CommonmarkMarkdownRenderer])
    bind(classOf[MarkdownRenderer]).to(classOf[SanitisingMarkdownRenderer])
    bind(classOf[CypherService]).to(classOf[WsCypherService])
    bind(classOf[ItemLifecycle]).to(classOf[GeocodingItemLifecycle])
    bind(classOf[GeocodingService]).toProvider(classOf[AwsGeocodingServiceProvider])
    bind(classOf[MetricRegistry]).toProvider(classOf[MetricsRegistryProvider]).asEagerSingleton()
    bindActor[EventForwarder]("event-forwarder")
  }
}
