package guice

import com.google.inject.AbstractModule
import config.ServiceConfig
import eu.ehri.project.indexing.index.Index
import eu.ehri.project.indexing.index.impl.SolrIndex
import eu.ehri.project.search.solr._
import services.data.DataServiceBuilder
import services.search._
import services.search.resolvers.{GidSearchResolver, IdSearchResolver, SearchItemResolver}

import javax.inject.{Inject, Provider}
import scala.concurrent.ExecutionContext

private class SolrIndexProvider @Inject()(config: play.api.Configuration) extends Provider[Index] {
  override def get(): Index = new SolrIndex(ServiceConfig("solr", config).baseUrl)
}

private class SearchResolverProvider @Inject()(config: play.api.Configuration, dataApi: DataServiceBuilder, ec: ExecutionContext) extends Provider[SearchItemResolver] {
  override def get: SearchItemResolver = config.getOptional[String]("search.resolver") match {
    case Some("gid") =>
      // Look up items by their numeric database IDs (this is a bit less stable, due to GID re-use, but a bit faster)
      GidSearchResolver(dataApi)(ec)
    case Some("id") | None =>
      // Look up items by their string IDs (this is typically a bit slower, but more stable, since IDs aren't reused)
      IdSearchResolver(dataApi)(ec)
    case Some(other) =>
      throw new IllegalArgumentException(s"Unknown search resolver: $other")
  }
}

class SearchModule extends AbstractModule {
  override def configure(): Unit = {
    bind(classOf[Index]).toProvider(classOf[SolrIndexProvider])
    bind(classOf[ResponseParser]).to(classOf[SolrJsonResponseParser])
    bind(classOf[QueryBuilder]).to(classOf[SolrQueryBuilder])
    bind(classOf[SearchIndexMediator]).to(classOf[AkkaStreamsIndexMediator])
    bind(classOf[SearchEngine]).to(classOf[SolrSearchEngine])
    bind(classOf[SearchItemResolver]).toProvider(classOf[SearchResolverProvider])
  }
}
