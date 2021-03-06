package models

import models.base.AnyModel
import defines.EntityType
import models.json._
import play.api.libs.json._
import play.api.libs.functional.syntax._
import backend.{Entity, Readable}


object ContentType {
  val format: Format[ContentType] = (
    (__ \ Entity.ID).format[String] and
    (__ \ Entity.TYPE).formatIfEquals(EntityType.ContentType)
  )(ContentType.apply, unlift(ContentType.unapply))

  implicit object Converter extends Readable[ContentType] {
    val restReads = format
  }
}

case class ContentType(
  id: String,
  isA: EntityType.Value = EntityType.ContentType
) extends AnyModel
