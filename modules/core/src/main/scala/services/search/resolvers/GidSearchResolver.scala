package services.search.resolvers

import models.Readable
import play.api.Logger
import services.data.{DataServiceBuilder, DataUser}
import services.search.SearchHit

import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

/**
  * Resolve search hits to DB items by the GID field
  */
case class GidSearchResolver @Inject()(dataApi: DataServiceBuilder)(implicit executionContext: ExecutionContext) extends SearchItemResolver {
  private val logger = Logger(classOf[GidSearchResolver])
  def resolve[MT: Readable](docs: Seq[SearchHit])(implicit apiUser: DataUser): Future[Seq[Option[MT]]] = {
    val start = System.currentTimeMillis()
    val f = dataApi.withContext(apiUser).fetch(gids = docs.map(_.gid))
    f.onComplete(_ => logger.debug(s"GidSearchResolver.resolve took ${System.currentTimeMillis() - start}ms"))
    f
  }
}
