package models

import base._

import models.base.Persistable
import defines.{ContentTypes, EntityType}
import models.json._
import play.api.i18n.Lang
import play.api.libs.json._
import play.api.libs.functional.syntax._
import play.api.data.Form
import play.api.data.Forms._
import backend._
import play.api.libs.json.JsObject


object CountryF {

  val ABSTRACT = "abstract"
  val HISTORY = "report" // FIXME: Rename to "history"
  val SITUATION = "situation"
  val DATA_SUMMARY = "dataSummary"
  val DATA_EXTENSIVE = "dataExtensive"

  import Entity._

  implicit val countryWrites: Writes[CountryF] = new Writes[CountryF] {
    def writes(d: CountryF): JsValue = {
      Json.obj(
        ID -> d.id,
        TYPE -> d.isA,
        DATA -> Json.obj(
          IDENTIFIER -> d.identifier,
          ABSTRACT -> d.abs,
          HISTORY -> d.history,
          SITUATION -> d.situation,
          DATA_SUMMARY -> d.summary,
          DATA_EXTENSIVE -> d.extensive
        )
      )
    }
  }

  lazy implicit val countryReads: Reads[CountryF] = (
    (__ \ TYPE).readIfEquals(EntityType.Country) and
    (__ \ ID).readNullable[String] and
    (__ \ DATA \ IDENTIFIER).read[String] and
    (__ \ DATA \ ABSTRACT).readNullable[String] and
    (__ \ DATA \ HISTORY).readNullable[String] and
    (__ \ DATA \ SITUATION).readNullable[String] and
    (__ \ DATA \ DATA_SUMMARY).readNullable[String] and
    (__ \ DATA \ DATA_EXTENSIVE).readNullable[String]
  )(CountryF.apply _)

  implicit object Converter extends BackendWriteable[CountryF] {
    lazy val restFormat = Format(countryReads,countryWrites)
  }
}

case class CountryF(
  isA:EntityType.Value = EntityType.Country,
  id: Option[String],
  identifier: String,
  abs: Option[String],
  history: Option[String],
  situation: Option[String],                   
  summary: Option[String],
  extensive: Option[String]                   
) extends Model with Persistable {

  def displayText = abs orElse situation
}


object Country {
  import eu.ehri.project.definitions.Ontology._
  import Entity._
  import CountryF._

  implicit val metaReads: Reads[Country] = (
    __.read[CountryF](countryReads) and
    // Latest event
    (__ \ RELATIONSHIPS \ IS_ACCESSIBLE_TO).nullableSeqReads(Accessor.Converter.restReads) and
    (__ \ RELATIONSHIPS \ ENTITY_HAS_LIFECYCLE_EVENT).nullableHeadReads[SystemEvent] and
    (__ \ META).readWithDefault(Json.obj())
  )(Country.apply _)

  implicit object CountryResource extends BackendContentType[Country]  {
    val entityType = EntityType.Country
    val contentType = ContentTypes.Country
    val restReads = metaReads
  }

  val form = Form(
    mapping(
      ISA -> ignored(EntityType.Country),
      ID -> optional(nonEmptyText),
      IDENTIFIER -> nonEmptyText(minLength=2,maxLength=2), // ISO 2-letter field
      ABSTRACT -> optional(nonEmptyText),
      HISTORY -> optional(nonEmptyText),
      SITUATION -> optional(nonEmptyText),
      DATA_SUMMARY -> optional(nonEmptyText),
      DATA_EXTENSIVE -> optional(nonEmptyText)
    )(CountryF.apply)(CountryF.unapply)
  )
}


// Stub
case class Country(
  model: CountryF,
  accessors: Seq[Accessor] = Nil,
  latestEvent: Option[SystemEvent] = None,
  meta: JsObject = JsObject(Seq())
) extends AnyModel
  with MetaModel[CountryF]
  with Accessible
  with Holder[Repository] {

  override def toStringLang(implicit lang: Lang) = views.Helpers.countryCodeToName(id)
}