package eu.ehri.project.xml

import org.specs2.mutable.Specification

class BaseXTabularTransformerSpec extends Specification {

  private val header = "op\tcolumns\tto\texpr"

  "Tabular transformer should" should {

    "reject two rows that both produce the same output column" in {
      val transformer = BaseXTabularTransformer()
      val input = "id,creator_first,creator_last\np1,Anna,de Vries\n"
      val config = Seq(
        header,
        "merge\tcreator_first\tcreator\t;",
        "merge\tcreator_last\tcreator\t"
      ).mkString("\n")
      transformer.transform(input, config, Map("input.separator" -> "comma")) must
        throwA[InvalidMappingError].like { case e => e.getMessage must contain("creator") }
    }

    "reflavour delimiters with no pipeline rows at all" in {
      val transformer = BaseXTabularTransformer()
      val input = "a,b,c\n1,2,3\n"
      val out = transformer.transform(input, header + "\n",
        Map("input.separator" -> "comma", "output.separator" -> "tab"))
      out must contain("a\tb\tc")
      out must contain("1\t2\t3")
    }

    "run a select / merge / derive / filter pipeline, each row reading the input directly" in {
      val transformer = BaseXTabularTransformer()
      val input =
        """id,title,creator_first,creator_last,country,start_year,end_year
          |NL-001,Letters,Anna,de Vries,NL,1940,1945
          |DE-002,Diary,Hans,Müller,DE,1941,1943
          |NL-003,Photos,Jan,Bakker,NL,1938,1940
          |""".stripMargin
      val config = Seq(
        header,
        "filter\t\t\t?country = \"NL\"",
        "select\tid\tidentifier\t",
        "select\ttitle\t\t",
        "merge\tcreator_first,creator_last\tcreator\t ",
        "derive\t\tdates\tconcat(?start_year, \"/\", ?end_year)"
      ).mkString("\n")
      val out = transformer.transform(input, config,
        Map("input.separator" -> "comma", "output.separator" -> "tab"))

      out must contain("identifier\ttitle\tcreator\tdates")
      out must contain("NL-001\tLetters\tAnna de Vries\t1940/1945")
      out must contain("NL-003\tPhotos\tJan Bakker\t1938/1940")
      out must not(contain("DE-002"))
      out must not(contain("Müller"))
    }

    "merge several columns in one row, skipping blank values" in {
      val transformer = BaseXTabularTransformer()
      val input =
        """id,creator_first,creator_last
          |p1,Anna,de Vries
          |p2,,Müller
          |""".stripMargin
      val config = Seq(header, "select\tid\t\t", "merge\tcreator_first,creator_last\tcreator\t ").mkString("\n")
      val out = transformer.transform(input, config,
        Map("input.separator" -> "comma", "output.separator" -> "comma"))

      out must contain("id,creator")
      out must contain("p1,Anna de Vries")
      // blank creator_first skipped: no leading separator
      out must contain("p2,Müller")
      out must not(contain("creator_first"))
    }

    "handle several independent merge/select rows, order-independent of each other" in {
      val transformer = BaseXTabularTransformer()
      val input = "id,creator_first,creator_last,street,city\np1,Anna,de Vries,Prinsengracht 1,Amsterdam\n"
      val config = Seq(
        header,
        "merge\tcreator_first,creator_last\tcreator\t ",
        "merge\tstreet,city\taddress\t, ",
        "select\tid\t\t"
      ).mkString("\n")
      val out = transformer.transform(input, config,
        Map("input.separator" -> "comma", "output.separator" -> "comma"))

      out must contain("creator,address,id")
      out must contain("Anna de Vries,\"Prinsengracht 1, Amsterdam\",p1")
    }

    "split one column into several fixed columns" in {
      val transformer = BaseXTabularTransformer()
      val config = Seq(header, "select\tid\t\t", "split\tcoords\tlat,lon\t||").mkString("\n")
      val out = transformer.transform("id,coords\nX1,52.37||4.90\n", config,
        Map("input.separator" -> "comma", "output.separator" -> "comma"))
      out must contain("id,lat,lon")
      out must contain("X1,52.37,4.90")
    }

    "omit unwanted columns simply by not producing them" in {
      val transformer = BaseXTabularTransformer()
      val config = Seq(header, "select\ta\t\t", "select\tc\t\t").mkString("\n")
      val out = transformer.transform("a,b,c,d\n1,2,3,4\n", config,
        Map("input.separator" -> "comma", "output.separator" -> "comma"))
      out must contain("a,c")
      out must contain("1,3")
    }

    "AND together multiple filter rows, wherever they appear" in {
      val transformer = BaseXTabularTransformer()
      val input =
        """id,country,start_year
          |NL-001,NL,1940
          |NL-002,NL,1938
          |DE-003,DE,1945
          |""".stripMargin
      val config = Seq(
        header,
        "filter\t\t\t?country = \"NL\"",
        "select\tid\t\t",
        "filter\t\t\txs:integer(?start_year) ge 1940"
      ).mkString("\n")
      val out = transformer.transform(input, config,
        Map("input.separator" -> "comma", "output.separator" -> "comma"))
      out must contain("NL-001")
      out must not(contain("NL-002"))
      out must not(contain("DE-003"))
    }

    "skip rows with an empty op" in {
      val transformer = BaseXTabularTransformer()
      val config = Seq(header, "select\tid\tidentifier\t", "\t\t\t", "select\ttitle\t\t").mkString("\n")
      val out = transformer.transform("id,title,extra\n1,Foo,bar\n", config,
        Map("input.separator" -> "comma", "output.separator" -> "comma"))
      out must contain("identifier,title")
      out must contain("1,Foo")
      out must not(contain("extra"))
    }

    "report errors with the op name" in {
      val transformer = BaseXTabularTransformer()
      val config = header + "\nbogus\t\t\t"
      transformer.transform("a,b\n1,2\n", config, Map("input.separator" -> "comma")) must
        throwA[InvalidMappingError].like { case e => e.getMessage must contain("Unknown op: bogus") }
    }
  }
}
