package models

case class UrlNameMap(url: String, name: String)

case class UrlSetConfig(
  urlMap: Map[String, String],
  auth: Option[BasicAuthConfig] = None,
) extends HarvestConfig {
  override def src: ImportDataset.Src.Value = ImportDataset.Src.UrlSet
  def urls: Seq[UrlNameMap] = urlMap.toSeq
    .map { case (url, name) => UrlNameMap(url, name)}
}

object UrlSetConfig {
  val URLS = "urlMap"
  val AUTH = "auth"

  import play.api.libs.functional.syntax._
  import play.api.libs.json._

  implicit val _reads: Reads[UrlSetConfig] = (
    (__ \ URLS).read[Map[String, String]] and
    (__ \ AUTH).readNullable[BasicAuthConfig]
  ) (UrlSetConfig.apply _)

  implicit val _writes: Writes[UrlSetConfig] = Json.writes[UrlSetConfig]
  implicit val _format: Format[UrlSetConfig] = Format(_reads, _writes)
}



