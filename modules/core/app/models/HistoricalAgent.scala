package models

/**
 * Classes representing an ISDIAH collection-holding institution
 */

import defines.{ContentTypes, EntityType}
import base._

import play.api.libs.json._
import models.json._
import play.api.libs.functional.syntax._
import eu.ehri.project.definitions.Ontology
import play.api.data.Form
import play.api.data.Forms._
import utils.forms._
import backend._
import play.api.libs.json.JsObject

object HistoricalAgentF {

  final val UNNAMED_PLACEHOLDER = "UNNAMED Authority"

  val PUBLICATION_STATUS = "publicationStatus"

  import Entity._
  import Ontology._

  implicit val historicalAgentFormat: Format[HistoricalAgentF] = (
    (__ \ TYPE).formatIfEquals(EntityType.HistoricalAgent) and
    (__ \ ID).formatNullable[String] and
    (__ \ DATA \ IDENTIFIER).format[String] and
    (__ \ DATA \ PUBLICATION_STATUS).formatNullable[PublicationStatus.Value] and
    (__ \ RELATIONSHIPS \ DESCRIPTION_FOR_ENTITY).formatSeqOrEmpty[HistoricalAgentDescriptionF]
  )(HistoricalAgentF.apply _, unlift(HistoricalAgentF.unapply))

  implicit object Converter extends Writable[HistoricalAgentF] {
    lazy val restFormat = historicalAgentFormat
  }
}

case class HistoricalAgentF(
  isA: EntityType.Value = EntityType.HistoricalAgent,
  id: Option[String],
  identifier: String,
  publicationStatus: Option[PublicationStatus.Value] = None,

  @models.relation(Ontology.DESCRIPTION_FOR_ENTITY)
  descriptions: Seq[HistoricalAgentDescriptionF] = Nil
) extends Model
  with Persistable
  with Described[HistoricalAgentDescriptionF]


object HistoricalAgent {
  import play.api.libs.functional.syntax._
  import Entity._
  import DescribedMeta._
  import HistoricalAgentF._
  import Ontology._
  import defines.EnumUtils.enumMapping

  private implicit val systemEventReads = SystemEvent.SystemEventResource.restReads
  private implicit val authoritativeSetReads = AuthoritativeSet.AuthoritativeSetResource.restReads

  implicit val metaReads: Reads[HistoricalAgent] = (
    __.read[HistoricalAgentF] and
    (__ \ RELATIONSHIPS \ ITEM_IN_AUTHORITATIVE_SET).readHeadNullable[AuthoritativeSet] and
    (__ \ RELATIONSHIPS \ IS_ACCESSIBLE_TO).readSeqOrEmpty(Accessor.Converter.restReads) and
    (__ \ RELATIONSHIPS \ ENTITY_HAS_LIFECYCLE_EVENT).readHeadNullable[SystemEvent] and
    (__ \ META).readWithDefault(Json.obj())
  )(HistoricalAgent.apply _)

  implicit object HistoricalAgentResource extends backend.ContentType[HistoricalAgent]  {
    val entityType = EntityType.HistoricalAgent
    val contentType = ContentTypes.HistoricalAgent
    val restReads = metaReads
  }

  val form = Form(
    mapping(
      ISA -> ignored(EntityType.HistoricalAgent),
      ID -> optional(nonEmptyText),
      IDENTIFIER -> nonEmptyText(minLength=2), // TODO: Increase to > 2, not done yet 'cos of test fixtures,
      PUBLICATION_STATUS -> optional(enumMapping(models.PublicationStatus)),
      DESCRIPTIONS -> seq(HistoricalAgentDescription.form.mapping)
    )(HistoricalAgentF.apply)(HistoricalAgentF.unapply)
  )
}


case class HistoricalAgent(
  model: HistoricalAgentF,
  set: Option[AuthoritativeSet],
  accessors: Seq[Accessor] = Nil,
  latestEvent: Option[SystemEvent],
  meta: JsObject = JsObject(Seq())
) extends AnyModel
  with MetaModel[HistoricalAgentF]
  with DescribedMeta[HistoricalAgentDescriptionF,HistoricalAgentF]
  with Accessible


