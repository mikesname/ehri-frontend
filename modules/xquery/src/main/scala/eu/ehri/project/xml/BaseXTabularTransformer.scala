package eu.ehri.project.xml

import org.basex.core.Context
import org.basex.query.value.`type`.AtomType
import org.basex.query.value.item.{Str => BaseXString}
import org.basex.query.value.map.{Map => BaseXMap}
import org.basex.query.{QueryException, QueryProcessor}
import org.slf4j.{Logger, LoggerFactory}

import java.io.ByteArrayOutputStream
import java.net.URI

/**
  * Transform tabular data (CSV/TSV) into other tabular data using a declarative
  * pipeline of operations. Operates on BaseX's `csv:parse` XML representation and
  * serialises back with `csv:serialize`, so changing delimiters, headers and
  * quoting is a matter of parse/serialise options, while structural edits
  * (select, drop, rename, filter, derive, split, merge, explode) are pipeline
  * operations.
  *
  * The pipeline config is a TSV with columns: op, columns, to, expr.
  * Parse/serialise options (input.separator, output.separator, array.separator,
  * ...) are supplied as params.
  */
case class BaseXTabularTransformer(scriptOpt: Option[String] = None, funcOpt: Option[URI] = None) extends XQueryTabularTransformer with Timer {

  private val logger: Logger = LoggerFactory.getLogger(classOf[BaseXTabularTransformer])
  override def logTime(s: String): Unit = logger.debug(s)
  import BaseXXQueryXmlTransformer.{INPUT, LIB_URI, uriResolver, using}

  val script: String = scriptOpt.getOrElse {
    using(scala.io.Source.fromResource("transform-tabular.xqy"))(_.mkString)
  }
  val utilLibUrl: URI = funcOpt.getOrElse(getClass.getResource("/xtra.xqm").toURI)

  @throws(classOf[InvalidMappingError])
  override def transform(data: String, config: String, params: Map[String, String] = Map.empty): String = {
    try {
      logger.trace(s"Input: $data")
      logger.trace(s"Config: $config")
      logger.trace(s"Params: $params")
      time("Transformation") {
        using(new QueryProcessor(script, new Context()).uriResolver(uriResolver)) { proc =>
          var opts = BaseXMap.EMPTY
          params.foreach { case (k, v) =>
            opts = opts.put(
              new BaseXString(k.getBytes, AtomType.STR),
              new BaseXString(v.getBytes, AtomType.STR),
              null
            )
          }

          proc.bind(INPUT, data, "xs:string")
          proc.bind("config", config, "xs:string")
          proc.bind("options", opts, "map()")
          proc.bind(LIB_URI, utilLibUrl, "xs:anyURI")

          logger.debug(s"Module URL: $utilLibUrl")

          val iter = proc.iter()

          val bytes = new ByteArrayOutputStream()
          using(proc.getSerializer(bytes)) { ser =>
            var item = iter.next()
            while (item != null) {
              ser.serialize(item)
              item = iter.next()
            }
          }
          bytes.flush()
          bytes.toString()
        }
      }
    } catch {
      case e: QueryException =>
        throw InvalidMappingError(e.getLocalizedMessage)
    }
  }
}
