package utils.db

import anorm.{ToStatement, TypeDoesNotMatch, MayErr, Column}

/**
 * @author Mike Bryant (http://github.com/mikesname)
 */
trait StorableEnum {
  self: Enumeration =>

  implicit def rowToEnum: Column[Value] = {
    Column.nonNull1[Value] { (value, meta) =>
      try {
        Right(withName(value.toString))
      } catch {
        case e: Throwable => Left(TypeDoesNotMatch(
          s"Cannot convert $value: ${value.asInstanceOf[AnyRef].getClass} to ${getClass.getName}"))
      }
    }
  }

  implicit def enumToStatement = new ToStatement[Value] {
    def set(s: java.sql.PreparedStatement, index: Int, value: Value): Unit =
      s.setObject(index, value.toString)
  }

}

