package helpers

import auth.oauth2.{MockOAuth2Flow, OAuth2Flow}
import auth.{AccountManager, MockAccountManager}
import backend._
import backend.aws.MockFileStorage
import backend.helpdesk.{MockFeedbackDAO, MockHelpdeskDAO}
import backend.rest.RestBackend
import com.typesafe.plugin.MailerAPI
import controllers.base.AuthConfigImpl
import global.GlobalConfig
import jp.t2v.lab.play2.auth.test.Helpers._
import mocks.{MockBufferedMailer, _}
import models.{Account, Feedback}
import org.specs2.execute.{Result, AsResult}
import play.api.db.Database
import play.api.http.Writeable
import play.api.i18n.MessagesApi
import play.api.inject.guice.GuiceApplicationLoader
import play.api.test.Helpers._
import play.api.test._
import utils.MovedPageLookup
import utils.search.{MockSearchIndexer, _}
import scala.concurrent.{ExecutionContext, Future}


/**
 * Mixin trait that provides some handy methods to test actions that
 * have authorisation, such as fakeApplication and fakeLoggedInHtmlRequest.
 */
trait TestConfiguration extends play.api.i18n.I18nSupport {

  this: PlayRunners with RouteInvokers =>

  import RestBackendRunner._

  // Stateful buffers for capturing stuff like feedback, search
  // parameters, and reset tokens. These persist across tests in
  // a very unclean way but are useful for determining the last-used
  // whatsit etc...
  val feedbackBuffer = collection.mutable.HashMap.empty[Int,Feedback]
  val helpdeskBuffer = collection.mutable.HashMap.empty[Int, Seq[(String, Double)]]
  val mailBuffer = collection.mutable.ListBuffer.empty[MockMail]
  val storedFileBuffer = collection.mutable.ListBuffer.empty[java.net.URI]
  val searchParamBuffer = collection.mutable.ListBuffer.empty[ParamLog]
  val indexEventBuffer = collection.mutable.ListBuffer.empty[String]

  private def mockMailer: MailerAPI = new MockBufferedMailer(mailBuffer)
  private def mockIndexer: SearchIndexer = new MockSearchIndexer(indexEventBuffer)
  private def mockFeedback: FeedbackDAO = new MockFeedbackDAO(feedbackBuffer)
  private def mockHelpdesk: HelpdeskDAO = new MockHelpdeskDAO(helpdeskBuffer)
  //private def mockResolver: MockSearchResolver = new MockSearchResolver
  //private def mockSearchEngine: SearchEngine = new MockSearchEngine(testBackendFactory)
  // NB: The mutable state for the user DAO is still stored globally
  // in the mocks package.
  def mockAccounts: AccountManager = MockAccountManager()
  private def mockOAuth2Flow: OAuth2Flow = MockOAuth2Flow()
  private def mockRelocator: MovedPageLookup = MockMovedPageLookup()
  private def mockFileStorage: FileStorage = MockFileStorage(storedFileBuffer)
  private def mockHtmlPages: HtmlPages = MockHtmlPages()

  // More or less the same as run config but synchronous (so
  // we can validate the actions)
  // Note: this is defined as an implicit object here so it
  // can be used by the DAO classes directly.
  val testEventHandler = new EventHandler {
    def handleCreate(id: String) = mockIndexer.handle.indexId(id)
    def handleUpdate(id: String) = mockIndexer.handle.indexId(id)
    def handleDelete(id: String) = mockIndexer.handle.clearId(id)
  }

  val searchLogger = new SearchLogger {
    override def log(params: ParamLog): Unit = searchParamBuffer += params
  }

  import play.api.inject.bind

  val appBuilder = new play.api.inject.guice.GuiceApplicationBuilder()
    .overrides(
      bind[MailerAPI].toInstance(mockMailer),
      bind[OAuth2Flow].toInstance(mockOAuth2Flow),
      bind[FileStorage].toInstance(mockFileStorage),
      bind[MovedPageLookup].toInstance(mockRelocator),
      bind[AccountManager].toInstance(mockAccounts),
      bind[SearchEngine].to[MockSearchEngine],
      bind[HelpdeskDAO].toInstance(mockHelpdesk),
      bind[SearchItemResolver].to[MockSearchResolver],
      bind[FeedbackDAO].toInstance(mockFeedback),
      bind[EventHandler].toInstance(testEventHandler),
      bind[Backend].to[RestBackend],
      bind[SearchIndexer].toInstance(mockIndexer),
      bind[HtmlPages].toInstance(mockHtmlPages),
      bind[Database].toInstance(helpers.testDatabase)
    ).bindings(
      bind[SearchLogger].toInstance(searchLogger)
    )

  val integrationAppLoader = new GuiceApplicationLoader(appBuilder)

  implicit def messagesApi: MessagesApi =
    appBuilder.build().injector.instanceOf[play.api.i18n.MessagesApi]

  implicit def execContext(implicit app: play.api.Application): ExecutionContext =
    app.injector.instanceOf[ExecutionContext]

  // Might want to mock the backend at at some point!
  def testBackend(implicit app: play.api.Application, apiUser: ApiUser, executionContext: ExecutionContext): BackendHandle =
    app.injector.instanceOf[Backend].withContext(apiUser)(executionContext)

  // Dummy auth config for play-2-auth
  def authConfig(implicit _app: play.api.Application) = new AuthConfigImpl {
    val app = _app
    val globalConfig = app.injector.instanceOf[GlobalConfig]
    val accounts = app.injector.instanceOf[AccountManager]
  }

  val CSRF_TOKEN_NAME = "csrfToken"
  val CSRF_HEADER_NAME = "Csrf-Token"
  val CSRF_HEADER_NOCHECK = "nocheck"
  val fakeCsrfString = "fake-csrf-token"
  val testPassword = "testpass"

  /**
   * Override this value for configuration common to
   * an entire class of specs.
   */
  def getConfig = Map.empty[String,Any]

  /**
   * Test running Fake Application. We have general all-test configuration,
   * handled in `config`, and per-test configuration (`specificConfig`) that
   * will be merged.
   * @param specificConfig A map of config values for this test
   */
  abstract class ITestApp(val specificConfig: Map[String,Any] = Map.empty) extends WithApplicationLoader(
    new GuiceApplicationLoader(appBuilder.configure(backendConfig ++ getConfig ++ specificConfig)))

  /**
   * Test running Fake Application. We have general all-test configuration,
   * handled in `config`, and per-test configuration (`specificConfig`) that
   * will be merged.
   * @param specificConfig A map of config values for this test
   */
  abstract class DBTestApp(resource: String, specificConfig: Map[String,Any] = Map.empty) extends WithSqlFile(
    resource)(new GuiceApplicationLoader(appBuilder.configure(backendConfig ++ getConfig ++ specificConfig)))

  /**
   * Run a spec after loading the given resource name as SQL fixtures.
   */
  abstract class WithSqlFile(val resource: String)(implicit appLoader: play.api.ApplicationLoader)
    extends WithApplicationLoader(appLoader) {
    override def around[T: AsResult](t: => T): Result = {
      running(app) {
        withDatabaseFixture(resource) { implicit db =>
          AsResult.effectively(t)
        }
      }
    }
  }

  /**
   * Convenience extensions for the FakeRequest object.
   */
  implicit class FakeRequestExtensions[A](fr: FakeRequest[A]) {
    def withUser(user: Account)(implicit app: play.api.Application): FakeRequest[A] = {
      fr.withLoggedIn(authConfig(app))(user.id)
    }

    def withCsrf: FakeRequest[A] = if (fr.method == POST)
      fr.withSession(CSRF_TOKEN_NAME -> fakeCsrfString)
        .withHeaders(CSRF_HEADER_NAME -> CSRF_HEADER_NOCHECK) else fr

    def accepting(m: String*): FakeRequest[A] = m.foldLeft(fr) { (c, m) =>
      c.withHeaders(ACCEPT -> m)
    }

    def call()(implicit app: play.api.Application, w: Writeable[A]): Future[play.api.mvc.Result] =
      route(app, fr).getOrElse(sys.error(s"Unexpected null route for ${fr.uri} (${fr.method})"))

    def callWith[T](body: T)(implicit app: play.api.Application, w: Writeable[T]): Future[play.api.mvc.Result] =
      route(app, fr, body).getOrElse(sys.error(s"Unexpected null route for ${fr.uri} (${fr.method})"))
  }
}