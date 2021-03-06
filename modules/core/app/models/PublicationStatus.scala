package models

object PublicationStatus extends Enumeration {
	type Status = Value
	val Draft = Value("Draft")
  val Published = Value("Published")

  implicit val _format = defines.EnumUtils.enumFormat(this)
}