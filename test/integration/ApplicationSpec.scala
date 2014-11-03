package integration

import org.specs2.mutable._
import play.api.test._
import play.api.test.Helpers._

import helpers.{UserFixtures, TestConfiguration}
import play.api.i18n.Messages
import models.MockAccount
import play.api.test.FakeApplication

/**
 * Basic app helpers which don't require a running DB.
 */
class ApplicationSpec extends Specification with TestConfiguration with UserFixtures {
  sequential

  // Settings specific to this spec...
  override def getConfig = super.getConfig ++ Map[String,Any]("ehri.secured" -> true)

  "Application" should {
    "send 404 on a bad request" in {
      running(FakeApplication(withGlobal = Some(getGlobal))) {
        route(FakeRequest(GET, "/NOTHINGHERE")) must beNone
      }
    }

    "show readonly warning when in READONLY mode" in {
      running(FakeApplication(withGlobal = Some(getGlobal))) {
        // NB: The location of this file is set in the application.conf
        // and overridden in production configs by prod.conf. The default
        // one is a relative file called "READONLY". We can therefore
        // create this in the CWD to test.
        val f = new java.io.File("READONLY")
        f.createNewFile()
        // Since we're not running the DB for this test we just
        // try a route that doesn't need the backend - there's
        // nothing significant about using the /forgot endpoint
        // here, only that it's a simple layout
        try {
          val pageReadOnly = route(FakeRequest(GET,
            controllers.portal.routes.Profile.forgotPassword().url)).get
          status(pageReadOnly) must equalTo(OK)
          contentAsString(pageReadOnly) must contain(Messages("portal.readonly"))

          // Deleting the file should make the message go away
          f.delete()

          val page = route(FakeRequest(GET,
            controllers.portal.routes.Profile.forgotPassword().url)).get
          status(page) must equalTo(OK)
          contentAsString(page) must not contain Messages("portal.readonly")
        } finally {
          f.deleteOnExit()
        }
      }
    }

    "redirect 301 for trailing-slash URLs" in {
      running(FakeApplication(withGlobal = Some(getGlobal))) {
        val home = route(fakeLoggedInHtmlRequest(mocks.publicUser, GET,
          controllers.admin.routes.Home.index().url + "/")).get
        status(home) must equalTo(MOVED_PERMANENTLY)
      }
    }

    "deny non-staff users access to admin areas" in {
      running(FakeApplication(withGlobal = Some(getGlobal), additionalPlugins = getPlugins)) {
        val home = route(fakeLoggedInHtmlRequest(mocks.publicUser, GET,
          controllers.admin.routes.Home.index().url)).get
        status(home) must equalTo(UNAUTHORIZED)
      }
    }

    "deny non-verified users access to admin areas" in {
      var user = MockAccount("pete", "unverified@example.com", verified = false, staff = true)
      mocks.userFixtures += user.id -> user

      running(FakeApplication(withGlobal = Some(getGlobal), additionalPlugins = getPlugins)) {
        val home = route(fakeLoggedInHtmlRequest(user, GET,
          controllers.admin.routes.Home.index().url)).get
        status(home) must equalTo(UNAUTHORIZED)
      }
    }

    "redirect to default URL when accessing login page when logged in" in {
      running(FakeApplication(withGlobal = Some(getGlobal), additionalPlugins = getPlugins)) {
        val login = route(fakeLoggedInHtmlRequest(mocks.publicUser, GET,
          controllers.portal.routes.Profile.login().url)).get
        status(login) must equalTo(SEE_OTHER)
      }
    }

    "not redirect to login at admin home when unsecured" in {
      running(FakeApplication(withGlobal = Some(getGlobal),
        additionalConfiguration = Map("ehri.secured" -> false))) {
        val home = route(FakeRequest(GET,
          controllers.admin.routes.Home.index().url)).get
        status(home) must equalTo(OK)
      }
    }

    "allow access to the openid callback url, and return a bad request" in {
      running(FakeApplication(withGlobal = Some(getGlobal))) {
        val home = route(FakeRequest(GET,
          controllers.portal.routes.Profile.openIDCallback().url)
          .withSession(CSRF_TOKEN_NAME -> fakeCsrfString)).get
        status(home) must equalTo(BAD_REQUEST)
      }
    }
    
    "give a capture error submitting a forgot password form" in {
      running(FakeApplication(withGlobal = Some(getGlobal),
        additionalConfiguration = Map("recaptcha.skip" -> false),
        additionalPlugins = getPlugins)) {
        val data: Map[String,Seq[String]] = Map(
          "email" -> Seq("test@example.com"),
          CSRF_TOKEN_NAME -> Seq(fakeCsrfString)
        )
        val forgot = route(FakeRequest(POST,
          controllers.portal.routes.Profile.forgotPasswordPost().url)
          .withSession(CSRF_TOKEN_NAME -> fakeCsrfString), data).get
        status(forgot) must equalTo(BAD_REQUEST)
        contentAsString(forgot) must contain(Messages("error.badRecaptcha"))
      }
    }

    "give an error submitting a forgot password form with the right capcha but the wrong email" in {
      running(FakeApplication(withGlobal = Some(getGlobal),
          additionalConfiguration = Map("recaptcha.skip" -> true),
          additionalPlugins = getPlugins)) {
        val data: Map[String,Seq[String]] = Map(
          "email" -> Seq("test@example.com"),
          CSRF_TOKEN_NAME -> Seq(fakeCsrfString)
        )
        val forgot = route(FakeRequest(POST,
          controllers.portal.routes.Profile.forgotPasswordPost().url)
          .withSession(CSRF_TOKEN_NAME -> fakeCsrfString), data).get
        status(forgot) must equalTo(BAD_REQUEST)
        contentAsString(forgot) must contain(Messages("error.emailNotFound"))
      }
    }

    "create a reset token on password reset with correct email" in {
      running(FakeApplication(withGlobal = Some(getGlobal),
        additionalConfiguration = Map("recaptcha.skip" -> true),
        additionalPlugins = getPlugins)) {
        val numSentMails = mailBuffer.size
        val data: Map[String,Seq[String]] = Map(
          "email" -> Seq(mocks.unprivilegedUser.email),
          CSRF_TOKEN_NAME -> Seq(fakeCsrfString)
        )
        val forgot = route(FakeRequest(POST,
          controllers.portal.routes.Profile.forgotPasswordPost().url)
          .withSession(CSRF_TOKEN_NAME -> fakeCsrfString), data).get
        status(forgot) must equalTo(SEE_OTHER)
        mailBuffer.size must beEqualTo(numSentMails + 1)
        mailBuffer.last.to must contain(mocks.unprivilegedUser.email)
      }
    }

    "check password reset token works (but only once)" in {
      running(FakeApplication(withGlobal = Some(getGlobal),
        additionalConfiguration = Map("recaptcha.skip" -> true),
        additionalPlugins = getPlugins)) {
        val numSentMails = mailBuffer.size
        val data: Map[String,Seq[String]] = Map(
          "email" -> Seq(mocks.unprivilegedUser.email),
          CSRF_TOKEN_NAME -> Seq(fakeCsrfString)
        )
        val forgot = route(FakeRequest(POST,
          controllers.portal.routes.Profile.forgotPasswordPost().url)
          .withSession(CSRF_TOKEN_NAME -> fakeCsrfString), data).get
        status(forgot) must equalTo(SEE_OTHER)
        mailBuffer.size must beEqualTo(numSentMails + 1)
        mailBuffer.last.to must contain(mocks.unprivilegedUser.email)

        val token = mocks.tokens.last._1
        val resetForm = route(FakeRequest(GET,
          controllers.portal.routes.Profile.resetPassword(token).url)
          .withSession(CSRF_TOKEN_NAME -> fakeCsrfString)).get
        status(resetForm) must equalTo(OK)

        val rstData: Map[String,Seq[String]] = Map(
          "password" -> Seq("hellokitty"),
          "confirm"  -> Seq("hellokitty"),
          CSRF_TOKEN_NAME -> Seq(fakeCsrfString)
        )
        val resetPost = route(FakeRequest(POST,
          controllers.portal.routes.Profile.resetPassword(token).url)
          .withSession(CSRF_TOKEN_NAME -> fakeCsrfString), rstData).get
        status(resetPost) must equalTo(SEE_OTHER)

        val expired = route(FakeRequest(GET,
          controllers.portal.routes.Profile.resetPassword(token).url)
          .withSession(CSRF_TOKEN_NAME -> fakeCsrfString)).get
        status(expired) must equalTo(SEE_OTHER)
        val err = flash(expired).get("error")
        err must beSome
        err.get must equalTo(Messages("login.expiredOrInvalidResetToken"))
      }
    }
  }
}