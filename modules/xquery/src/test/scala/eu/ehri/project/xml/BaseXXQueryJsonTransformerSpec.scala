package eu.ehri.project.xml

import org.specs2.mutable.Specification

import scala.io.Source

class BaseXXQueryJsonTransformerSpec extends Specification {

  private val testPayload =
    """{
      |  "id": "test-001",
      |  "meta": { "title": "Test Collection", "lang": "English" },
      |  "records": [
      |    { "ref": "a", "date": "1943-1945" },
      |    { "ref": "b", "date": "1950" }
      |  ]
      |}
      |""".stripMargin

  "JSON transformer should" should {
    "transform a simple document" in {
      val transformer = BaseXXQueryJsonTransformer()
      val map = Source.fromResource("simple-json-mapping.tsv").mkString
      val out = transformer.transform(testPayload, map, Map.empty)
      out must contain("test-001")
      out must contain("Test Collection")
    }

    "invoke custom xtra functions" in {
      val transformer = BaseXXQueryJsonTransformer()
      val map = Source.fromResource("simple-json-mapping.tsv").mkString
      val out = transformer.transform(testPayload, map, Map.empty)
      // language "English" mapped to code "eng" by xtra:language-name-to-code
      out must contain("eng")
    }

    "build nested arrays with normalised values" in {
      val transformer = BaseXXQueryJsonTransformer()
      val map = Source.fromResource("simple-json-mapping.tsv").mkString
      val out = transformer.transform(testPayload, map, Map.empty)
      // date interval "1943-1945" normalised to "1943/1945"
      out must contain("1943/1945")
      out must contain("\"ref\"")
    }

    "transform an array of objects into an array of different objects" in {
      val arrayPayload =
        """[
          |  { "ref": "a", "title": "First", "lang": "English" },
          |  { "ref": "b", "title": "Second", "lang": "German" }
          |]
          |""".stripMargin
      val transformer = BaseXXQueryJsonTransformer()
      val map = Source.fromResource("array-json-mapping.tsv").mkString
      val out = transformer.transform(arrayPayload, map, Map.empty)
      // Output is a bare JSON array (starts with '['), not an object wrapper
      out.trim must startWith("[")
      out must contain("\"identifier\": \"a\"")
      out must contain("\"name\": \"Second\"")
      out must contain("\"language\": \"deu\"")
      out must not(contain("\"ref\""))
    }

    "report errors with context" in {
      val transformer = BaseXXQueryJsonTransformer()
      val map = Source.fromResource("simple-json-mapping.tsv").mkString +
        "\n/\tbad\tvalue\t.\tinvalid-func()"
      transformer.transform(testPayload, map, Map.empty) must throwA[InvalidMappingError].like {
        case e => e.getMessage must contain("at /bad") and(e.getMessage must contain("Unknown function: fn:invalid-func"))
      }
    }
  }
}
