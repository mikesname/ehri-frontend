package services.datasets

import org.apache.pekko.util.ByteString
import com.google.inject.ImplementedBy
import models.{ImportDataset, ImportDatasetInfo}
import play.api.http.{MimeTypes, Writeable}
import play.api.libs.json.Json

import scala.concurrent.Future

case class ImportDatasetExists(id: String, cause: Throwable)
  extends Exception(s"A dataset with that id already exists: '$id'", cause)

object ImportDatasetExists {
  implicit val writeableOf_json: Writeable[ImportDatasetExists] =
    new Writeable(e =>
      ByteString.fromString(
        Json.stringify(Json.obj("error" -> e.getMessage, "field" -> "id"))), Some(MimeTypes.JSON))
}


@ImplementedBy(classOf[SqlImportDatasetService])
trait ImportDatasetService {

  /**
    * List all import datasets.
    * @return a map of sets keyed by repository ID
    */
  def listAll(): Future[Map[String, Seq[ImportDataset]]]

  /**
    * Find a dataset for a given repository and dataset ID
    */
  def find(repoId: String, datasetId: String): Future[Option[ImportDataset]]

  /**
    * Fetch a dataset for a given repository and dataset ID
    */
  def get(repoId: String, datasetId: String): Future[ImportDataset]

  /**
    * List datasets for the given repository
    */
  def list(repoId: String): Future[Seq[ImportDataset]]

  /**
    * Create a dataset for the given repository
    */
  def create(repoId: String, info: ImportDatasetInfo): Future[ImportDataset]

  /**
    * Update a dataset for the given repository and dataset ID
    */
  def update(repoId: String, datasetId: String, info: ImportDatasetInfo): Future[ImportDataset]

  /**
    * Delete a given dataset for the given repository and dataset ID
    */
  def delete(repoId: String, datasetId: String): Future[Boolean]

  /**
    * Batch create a set of datasets for the given repository
    */
  def batch(repoId: String, info: Seq[ImportDatasetInfo]): Future[Seq[Int]]
}
