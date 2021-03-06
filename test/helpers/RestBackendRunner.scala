package helpers

import org.specs2.mutable.Specification
import org.specs2.specification.BeforeEach
import org.specs2.specification.core.Fragments
import eu.ehri.extension.test.helpers.ServerRunner
import eu.ehri.extension.AbstractAccessibleEntityResource


/**
 * Specs2 magic to provide equivalent of JUnit's beforeClass/afterClass.
 */
trait BeforeAllAfterAll extends Specification with BeforeEach {
  // see http://bit.ly/11I9kFM (specs2 User Guide)
  override def map(fragments: => Fragments) =
    step(beforeAll()) ^ fragments ^ step(afterAll())

  protected def beforeAll()
  protected def afterAll()
}

object RestBackendRunner {
  val testPort = 7575
  val testEndpoint = "ehri"

  val backendConfig: Map[String, Any] = Map(
    "neo4j.server.host" -> "localhost",
    "neo4j.server.port" -> testPort,
    "neo4j.server.endpoint" -> testEndpoint
  )
}

/**
 * @author Mike Bryant (http://github.com/mikesname)
 */
trait RestBackendRunner extends BeforeAllAfterAll {

  import RestBackendRunner._
  private val runner = ServerRunner.getInstance(testPort,
    classOf[AbstractAccessibleEntityResource[_]].getPackage.getName, testEndpoint)

  /**
   * Tear down and setup fixtures before every test
   */
  def before = {
    runner.tearDownData()
    runner.setUpData()
  }

  /**
   * Start the server before every class test
   */
  def beforeAll() = runner.start()

  /**
   * Stop the server after every class test
   */
  def afterAll() = runner.stop()
}
