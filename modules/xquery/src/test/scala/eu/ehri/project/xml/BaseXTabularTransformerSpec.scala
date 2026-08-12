package eu.ehri.project.xml

import org.specs2.mutable.Specification

class BaseXTabularTransformerSpec extends Specification {

  private val header = "op\tcolumns\tto\texpr"

  "Tabular transformer should" should {

    "reflavour delimiters with no pipeline (Tier-0)" in {
      val transformer = BaseXTabularTransformer()
      val input = "a,b,c\n1,2,3\n"
      val out = transformer.transform(input, header + "\n",
        Map("input.separator" -> "comma", "output.separator" -> "tab"))
      out must contain("a\tb\tc")
      out must contain("1\t2\t3")
    }

    "run a select / rename / merge / derive / filter pipeline" in {
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
        "merge\tcreator_first,creator_last\tcreator\t ",
        "derive\t\tdates\tconcat(?start_year, \"/\", ?end_year)",
        "select\tid,title,creator,dates\t\t",
        "rename\tid\tidentifier\t"
      ).mkString("\n")
      val out = transformer.transform(input, config,
        Map("input.separator" -> "comma", "output.separator" -> "tab"))

      out must contain("identifier\ttitle\tcreator\tdates")
      out must contain("NL-001\tLetters\tAnna de Vries\t1940/1945")
      out must contain("NL-003\tPhotos\tJan Bakker\t1938/1940")
      out must not(contain("DE-002"))
      out must not(contain("Müller"))
    }

    "explode a multi-valued column, preserving empty-cell records" in {
      val transformer = BaseXTabularTransformer()
      val input =
        """id,title,subjects
          |NL-001,Letters,war||resistance||letters
          |NL-003,Photos,
          |""".stripMargin
      val config = header + "\nexplode\tsubjects\tsubject\t"
      val out = transformer.transform(input, config,
        Map("input.separator" -> "comma", "output.separator" -> "comma", "array.separator" -> "||"))

      out must contain("NL-001,Letters,war")
      out must contain("NL-001,Letters,resistance")
      out must contain("NL-001,Letters,letters")
      // empty subjects: record preserved as a single row
      out must contain("NL-003,Photos,")
      out must contain("id,title,subject")
    }

    "report errors with the op name" in {
      val transformer = BaseXTabularTransformer()
      val config = header + "\nbogus\t\t\t"
      transformer.transform("a,b\n1,2\n", config, Map("input.separator" -> "comma")) must
        throwA[InvalidMappingError].like { case e => e.getMessage must contain("Unknown op: bogus") }
    }
  }
}
