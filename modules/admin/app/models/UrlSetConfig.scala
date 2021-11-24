package models

import play.api.data.Form
import play.api.data.Forms.{mapping, nonEmptyText, optional}

case class UrlSetConfig(
  urls: String,
  nameRe: Option[String],
  auth: Option[BasicAuthConfig] = None,
) {
  def set: Set[String] = urls.trim.split('\n').toSet
}

object UrlSetConfig {
  val URLS = "urls"
  val NAME_RE = "name_regex"
  val AUTH = "auth"

  import play.api.libs.functional.syntax._
  import play.api.libs.json._

  implicit val _reads: Reads[UrlSetConfig] = (
    (__ \ URLS).read[String] and
    (__ \ NAME_RE).readNullable[String] and
    (__ \ AUTH).readNullable[BasicAuthConfig]
  ) (UrlSetConfig.apply _)

  implicit val _writes: Writes[UrlSetConfig] = Json.writes[UrlSetConfig]
  implicit val _format: Format[UrlSetConfig] = Format(_reads, _writes)

  val form: Form[UrlSetConfig] = Form(mapping(
    URLS -> nonEmptyText.verifying("errors.invalidUrl", _.split('\n').forall(s => forms.isValidUrl(s.trim))),
    NAME_RE -> optional(nonEmptyText),
    AUTH -> optional(BasicAuthConfig.form.mapping), // TODO: ensure this compiles
  )(UrlSetConfig.apply)(UrlSetConfig.unapply).verifying(config => {
    // FIXME: ensure URLs end in unique file names or can be transformed thus
    true
  }))

}



