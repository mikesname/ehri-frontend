package models

import play.api.data.Form
import play.api.data.Forms._


case class OaiPmhConfig(
  url: String,
  format: String,
  set: Option[String] = None,
  auth: Option[BasicAuthConfig] = None
)

object OaiPmhConfig {
  final val URL = "url"
  final val METADATA_FORMAT = "format"
  final val SET = "set"
  final val AUTH = "auth"

  import play.api.libs.functional.syntax._
  import play.api.libs.json._

  implicit val _reads: Reads[OaiPmhConfig] = (
    (__ \ URL).read[String](Reads.filter(JsonValidationError("errors.invalidUrl"))(forms.isValidUrl)) and
      (__ \ METADATA_FORMAT).read[String] and
      (__ \ SET).readNullable[String] and
      (__ \ AUTH).readNullable[BasicAuthConfig]
    ) (OaiPmhConfig.apply _)

  implicit val _writes: Writes[OaiPmhConfig] = Json.writes[OaiPmhConfig]
  implicit val _format: Format[OaiPmhConfig] = Format(_reads, _writes)

  val form: Form[OaiPmhConfig] = Form(mapping(
    URL -> nonEmptyText.verifying("errors.invalidUrl", forms.isValidUrl),
    METADATA_FORMAT -> nonEmptyText,
    SET -> optional(text).transform[Option[String]](_.filterNot(_.trim.isEmpty), identity),
    AUTH -> optional(BasicAuthConfig.form.mapping)
  )(OaiPmhConfig.apply)(OaiPmhConfig.unapply))
}
