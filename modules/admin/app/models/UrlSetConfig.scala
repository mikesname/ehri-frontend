package models

import play.api.libs.json.JsonConfiguration.Aux
import play.api.libs.json.JsonNaming.SnakeCase

case class UrlNameMap(url: String, name: String)

case class UrlSetConfig(
  urlMap: Map[String, String],
  auth: Option[BasicAuthConfig] = None,
) {
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



