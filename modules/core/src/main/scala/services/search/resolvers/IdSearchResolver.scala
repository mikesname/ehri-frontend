package services.search.resolvers

import models.Readable
import services.data.{DataServiceBuilder, DataUser}
import services.search.SearchHit

import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

/**
  * Resolve search hits to DB items by the itemId field
  */
case class IdSearchResolver @Inject()(dataApi: DataServiceBuilder)(implicit executionContext: ExecutionContext) extends SearchItemResolver {
  private val logger = play.api.Logger(classOf[IdSearchResolver])
  def resolve[MT: Readable](docs: Seq[SearchHit])(implicit apiUser: DataUser): Future[Seq[Option[MT]]] = {
    val start = System.currentTimeMillis()
    val f = dataApi.withContext(apiUser).fetch(ids = docs.map(_.itemId))
    f.onComplete(_ => logger.debug(s"IdSearchResolver.resolve took ${System.currentTimeMillis() - start}ms"))
    f
  }
}
