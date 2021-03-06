package integration.admin

import helpers._
import play.api.test.FakeRequest

/**
 * Spec to test various page views operate as expected.
 */
class UtilsSpec extends IntegrationTestRunner {

  "Utils" should {
    "return a successful ping of the EHRI REST service" in new ITestApp {
      val ping = route(FakeRequest(controllers.admin.routes.Utils.checkDb())).get
      status(ping) must equalTo(OK)
    }

    "check user sync correctly" in new ITestApp {
      val check = route(FakeRequest(controllers.admin.routes.Utils.checkUserSync())).get
      status(check) must equalTo(OK)
      // User joeblogs exists in the account mocks but not the
      // graph DB fixtures, so the sync check should (correcly)
      // highlight this.
      contentAsString(check) must contain("joeblogs")
    }
  }
}
