package models

import models.base._

import defines.EntityType
import models.json._
import play.api.libs.json._
import play.api.libs.functional.syntax._
import play.api.data.Form
import play.api.data.Forms._
import play.api.libs.json.JsObject
import backend.{Entity, Readable, Writable}
import play.api.i18n.Messages


object AccessPointF {

  val TYPE = "type"
  val DESCRIPTION = "description"
  val TARGET = "name" // Change to something better!

  object AccessPointType extends Enumeration {
    type Type = Value
    val CreatorAccess = Value("creatorAccess")
    val PersonAccess = Value("personAccess")
    val FamilyAccess = Value("familyAccess")
    val CorporateBodyAccess = Value("corporateBodyAccess")
    val SubjectAccess = Value("subjectAccess")
    val PlaceAccess = Value("placeAccess")
    val Other = Value("otherAccess")

    implicit val format = defines.EnumUtils.enumFormat(this)

    def exceptCreator: ValueSet = values.filterNot(_ == CreatorAccess)
  }

  import Entity.{TYPE => ETYPE,_}

  implicit val accessPointFormat: Format[AccessPointF] = (
    (__ \ ETYPE).formatIfEquals(EntityType.AccessPoint) and
    (__ \ ID).formatNullable[String] and
    (__ \ DATA \ TYPE).formatWithDefault(AccessPointType.Other) and
    (__ \ DATA \ TARGET).format[String] and
    (__ \ DATA \ DESCRIPTION).formatNullable[String]
  )(AccessPointF.apply, unlift(AccessPointF.unapply))

  implicit object Converter extends Writable[AccessPointF] {
    lazy val restFormat = accessPointFormat
  }
}

case class AccessPointF(
  isA: EntityType.Value = EntityType.AccessPoint,
  id: Option[String],
  accessPointType: AccessPointF.AccessPointType.Value,
  name: String,
  description: Option[String] = None
) extends Model with Persistable {

  /**
   * Given a set of links, see if we can find one with this access point
   * as a body.
   */
  def linkFor(links: Seq[Link]): Option[Link] = links.find(_.bodies.exists(body => body.model.id == id))

  /**
   * Given a set of links, see if we can find one with this access point
   * as a body.
   */
  def linksFor(links: Seq[Link]): Seq[Link] = links.filter(_.bodies.exists(body => body.model.id == id))

  /**
   * Given an item and a set of links, see if we can resolve the
   * opposing target item.
   */
  def target(item: AnyModel, links: Seq[Link]): Option[(Link,AnyModel)] = linkFor(links).flatMap { link =>
    link.opposingTarget(item).map { target =>
      (link, target)
    }
  }
}

object AccessPoint {
  import Entity._
  import AccessPointF.{TYPE => ETYPE, _}
  import defines.EnumUtils.enumMapping

  implicit val metaReads: Reads[AccessPoint] = (
    __.read[AccessPointF] and
      (__ \ META).readWithDefault(Json.obj())
    )(AccessPoint.apply _)

  implicit object Converter extends Readable[AccessPoint] {
    val restReads = metaReads
  }


  def linksOfType(links: Seq[Link], `type`: AccessPointF.AccessPointType.Value): Seq[Link]
      = links.filter(_.bodies.exists(body => body.model.accessPointType == `type`))

  val form = Form(mapping(
    ISA -> ignored(EntityType.AccessPoint),
    ID -> optional(nonEmptyText),
    ETYPE -> enumMapping(AccessPointType),
    TARGET -> nonEmptyText, // TODO: Validate this server side
    DESCRIPTION -> optional(nonEmptyText)
  )(AccessPointF.apply)(AccessPointF.unapply))
}


case class AccessPoint(
  model: AccessPointF,
  meta: JsObject = JsObject(Seq())
) extends AnyModel with MetaModel[AccessPointF] {

  override def toStringLang(implicit messages: Messages) = "Access Point: (" + id + ")"
}
