package controllers.base

import play.api.mvc._
import play.api.test.{FakeApplication, WithApplication, FakeRequest, PlaySpecification}
import scala.concurrent.Future
import play.api.libs.json.{Format, Json}

case class TestPrefs(
  foo: String,
  bar: List[String]
)

object TestPrefs {
  implicit val fmt: Format[TestPrefs] = Json.format[TestPrefs]
  def default = new TestPrefs("bar", List("baz"))
}

// NB These config keys are needed at the moment in order to use
// the FakeApplication because we use the Mailer plugin, which
// depends on smtp.host, and crypto (for session reading/writing)
// which needs a dummy secret.
case class FakeApp() extends WithApplication(new FakeApplication(
  additionalConfiguration = Map("smtp.host" -> "localhost", "play.crypto.secret" -> "foobar")))

trait PrefTest extends SessionPreferences[TestPrefs] {
  this: Controller =>

  val defaultPreferences = TestPrefs.default

  def testGetPrefs() = Action { implicit request =>
    Ok(request.preferences.toString)
  }

  def testSavePrefs(langs: List[String]) = Action { implicit request =>
    val prefs = request.preferences.copy(bar = langs)
    Ok(langs.toString).withPreferences(prefs)
  }
}

/**
 * Test session loading/saving behaviour.
 *
 * @author Mike Bryant (http://github.com/mikesname)
 */
class SessionPreferencesSpec extends PlaySpecification with Results {
  class PrefTestController() extends Controller with PrefTest

  "Session preferences" should {
    "show the default" in {
      val controller = new PrefTestController()
      val result: Future[Result] = controller.testGetPrefs().apply(FakeRequest())
      val bodyText: String = contentAsString(result)
      bodyText must be equalTo TestPrefs.default.toString
    }

    "fetch correct set value" in new FakeApp {
      val controller = new PrefTestController()
      val prefs = TestPrefs("marty", List("mcfly"))
      val req = FakeRequest().withSession(
        SessionPreferences.DEFAULT_STORE_KEY -> Json.stringify(Json.toJson(prefs))
      )
      val result: Future[Result] = controller.testGetPrefs().apply(req)
      val bodyText: String = contentAsString(result)
      bodyText must be equalTo prefs.toString
    }

    "update correctly" in new FakeApp {
      val langs = List("eng")
      val controller = new PrefTestController()
      val result: Future[Result] = controller.testSavePrefs(langs).apply(FakeRequest())
      val bodyText: String = contentAsString(result)
      session(result).get(SessionPreferences.DEFAULT_STORE_KEY) must beSome.which { str =>
        val prefs = Json.parse(str).as[TestPrefs]
        prefs.bar must equalTo(langs)
      }
    }
  }
}
