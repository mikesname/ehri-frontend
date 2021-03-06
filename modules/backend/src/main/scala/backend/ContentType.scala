package backend

import defines.ContentTypes

import scala.annotation.implicitNotFound

/**
 * A type class for content type items that can be read
 * from the backend.
 *
 * @author Mike Bryant (http://github.com/mikesname)
 */
@implicitNotFound("No member of type class ContentType found for type ${T}")
trait ContentType[T] extends Resource[T] {
  /**
   * The content type of the resource.
   */
  def contentType: ContentTypes.Value
}

object ContentType {
  def apply[T: ContentType]: ContentType[T] = implicitly[ContentType[T]]
}
