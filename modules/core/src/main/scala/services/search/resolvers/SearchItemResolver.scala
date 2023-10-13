package services.search.resolvers

import models.Readable
import services.data.DataUser
import services.search.SearchHit

import scala.concurrent.Future

/**
 * Component responsible for resolving items from the
 * database from items returned from the search engine.
 *
 * Different resolvers could use either global identifiers
 * or more optimised internal graph IDs for faster resolution.
 */
trait SearchItemResolver {
  def resolve[MT: Readable](results: Seq[SearchHit])(implicit apiUser: DataUser): Future[Seq[Option[MT]]]
}
