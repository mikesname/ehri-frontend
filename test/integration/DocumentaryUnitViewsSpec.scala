package integration

import defines.{ContentTypes, EntityType}
import helpers.IntegrationTestRunner
import models.{Group, UserProfile, _}
import play.api.http.{HeaderNames, MimeTypes}
import play.api.i18n.Messages
import play.api.test.FakeRequest


class DocumentaryUnitViewsSpec extends IntegrationTestRunner {
  import mocks.{privilegedUser, unprivilegedUser}

  private val docRoutes = controllers.units.routes.DocumentaryUnits

  val userProfile = UserProfile(
    model = UserProfileF(id = Some(privilegedUser.id), identifier = "test", name="test user"),
    groups = List(Group(GroupF(id = Some("admin"), identifier = "admin", name="Administrators")))
  )

  // Common headers/strings
  val multipleItemsHeader = "Displaying items"
  val oneItemHeader = "One item found"
  val noItemsHeader = "No items found"

  "DocumentaryUnit views" should {

    "list should get some (world-readable) items" in new ITestApp {
      val list = route(fakeLoggedInHtmlRequest(unprivilegedUser, docRoutes.list())).get
      status(list) must equalTo(OK)
      contentAsString(list) must contain(multipleItemsHeader)
      contentAsString(list) must not contain "Documentary Unit 1"
      contentAsString(list) must contain("Documentary Unit 4")
      contentAsString(list) must contain("Documentary Unit m19")
    }

    "list when logged in should get more items" in new ITestApp {
      val list = route(fakeLoggedInHtmlRequest(privilegedUser, docRoutes.list())).get
      status(list) must equalTo(OK)
      contentAsString(list) must contain(multipleItemsHeader)
      contentAsString(list) must contain("Documentary Unit 1")
      contentAsString(list) must contain("Documentary Unit 2")
      contentAsString(list) must contain("Documentary Unit 3")
      contentAsString(list) must contain("Documentary Unit 4")
    }

    "search should find some items" in new ITestApp {
      val search = route(fakeLoggedInHtmlRequest(privilegedUser, docRoutes.search())).get
      status(search) must equalTo(OK)
      contentAsString(search) must contain(multipleItemsHeader)
      contentAsString(search) must contain("Documentary Unit 1")
      contentAsString(search) must contain("Documentary Unit 2")
      contentAsString(search) must contain("Documentary Unit 3")
      contentAsString(search) must contain("Documentary Unit 4")
    }

    "link to other privileged actions when logged in" in new ITestApp {
      val show = route(fakeLoggedInHtmlRequest(privilegedUser, docRoutes.get("c1"))).get
      status(show) must equalTo(OK)
      contentAsString(show) must contain(docRoutes.update("c1").url)
      contentAsString(show) must contain(docRoutes.delete("c1").url)
      contentAsString(show) must contain(docRoutes.createDoc("c1").url)
      contentAsString(show) must contain(docRoutes.visibility("c1").url)
      contentAsString(show) must contain(docRoutes.search().url)
    }

    "link to holder" in new ITestApp {
      val show = route(fakeLoggedInHtmlRequest(privilegedUser, docRoutes.get("c1"))).get
      status(show) must equalTo(OK)

      contentAsString(show) must contain(controllers.institutions.routes.Repositories.get("r1").url)
    }

    "link to holder when a child item" in new ITestApp {
      val show = route(fakeLoggedInHtmlRequest(privilegedUser, docRoutes.get("c2"))).get
      status(show) must equalTo(OK)

      contentAsString(show) must contain(controllers.institutions.routes.Repositories.get("r1").url)
    }

    "show history when logged in as privileged user" in new ITestApp {
      val show = route(fakeLoggedInHtmlRequest(privilegedUser, docRoutes.history("c1"))).get
      status(show) must equalTo(OK)
    }

    "throw a 404 when fetching items with the wrong type" in new ITestApp {
      // r1 is a repository, not a doc unit
      val show = route(fakeLoggedInHtmlRequest(privilegedUser, docRoutes.get("r1"))).get
      status(show) must equalTo(NOT_FOUND)
    }

    "allow EAD export" in new ITestApp {
      val ead = route(fakeLoggedInHtmlRequest(privilegedUser, docRoutes.exportEad("c1"))).get
      status(ead) must equalTo(OK)
      contentType(ead) must beSome.which { ct =>
        ct must equalTo(MimeTypes.XML)
      }
    }
  }

  "Documentary unit access functionality" should {

    "give access to c1 when logged in" in new ITestApp {
      val show = route(fakeLoggedInHtmlRequest(privilegedUser, docRoutes.get("c1"))).get
      status(show) must equalTo(OK)
      contentAsString(show) must contain("c1")
    }

    "deny access to c2 when logged in as an ordinary user" in new ITestApp {
      val show = route(fakeLoggedInHtmlRequest(unprivilegedUser, docRoutes.get("c2"))).get
      status(show) must equalTo(NOT_FOUND)
    }

    "allow deleting c4 when logged in" in new ITestApp {
      val del = route(fakeLoggedInHtmlRequest(privilegedUser, docRoutes.deletePost("c4"))).get
      status(del) must equalTo(SEE_OTHER)
    }
  }

  "Documentary unit CRUD functionality" should {

    "show correct default values in the form when creating new items" in new ITestApp(
      Map("documentaryUnit.rulesAndConventions" -> "SOME RANDOM VALUE")) {
      val form = route(fakeLoggedInHtmlRequest(privilegedUser,
        controllers.institutions.routes.Repositories.createDoc("r1"))).get
      status(form) must equalTo(OK)
      contentAsString(form) must contain("SOME RANDOM VALUE")
    }

    "NOT show default values in the form when editing items" in new ITestApp(
      Map("documentaryUnit.rulesAndConventions" -> "SOME RANDOM VALUE")) {
      val form = route(fakeLoggedInHtmlRequest(privilegedUser, docRoutes.update("c1"))).get
      status(form) must equalTo(OK)
      contentAsString(form) must not contain "SOME RANDOM VALUE"
    }

    "allow creating new items when logged in as privileged user" in new ITestApp {
      val testData: Map[String, Seq[String]] = Map(
        "identifier" -> Seq("hello-kitty"),
        "descriptions[0].languageCode" -> Seq("eng"),
        "descriptions[0].identityArea.name" -> Seq("Hello Kitty"),
        "descriptions[0].contentArea.scopeAndContent" -> Seq("Some content"),
        "descriptions[0].identityArea.dates[0].startDate" -> Seq("1939-01-01"),
        "descriptions[0].identityArea.dates[0].endDate" -> Seq("1945-01-01"),
        "publicationStatus" -> Seq("Published")
      )
      val cr = route(fakeLoggedInHtmlRequest(privilegedUser,
        controllers.institutions.routes.Repositories.createDocPost("r1")), testData).get
      status(cr) must equalTo(SEE_OTHER)

      val show = route(fakeLoggedInHtmlRequest(privilegedUser, GET, redirectLocation(cr).get)).get
      status(show) must equalTo(OK)

      contentAsString(show) must contain("Some content")
      contentAsString(show) must contain("Held By")
      // After having created an item it should contain a 'history' pane
      // on the show page
      contentAsString(show) must contain(docRoutes.history("nl-r1-hello_kitty").url)
      indexEventBuffer.last must equalTo("nl-r1-hello_kitty")
    }

    "allow creating new items with non-ASCII identifiers" in new ITestApp {
      val testData: Map[String, Seq[String]] = Map(
        "identifier" -> Seq("זהמזהה"),
        "descriptions[0].languageCode" -> Seq("heb"),
        "descriptions[0].identityArea.name" -> Seq("Hebrew Item")
      )
      val cr = route(fakeLoggedInHtmlRequest(privilegedUser,
        controllers.institutions.routes.Repositories.createDocPost("r1")), testData).get
      status(cr) must equalTo(SEE_OTHER)

      val show = route(fakeLoggedInHtmlRequest(privilegedUser, GET, redirectLocation(cr).get)).get
      status(show) must equalTo(OK)

      contentAsString(show) must contain(docRoutes.history("nl-r1-זהמזהה").url)
      indexEventBuffer.last must equalTo("nl-r1-זהמזהה")
    }

    "give a form error when creating items with the same id as existing ones" in new ITestApp {
      val testData: Map[String, Seq[String]] = Map(
        "identifier" -> Seq("c1"),
        "publicationStatus" -> Seq("Published")
      )
      // Since the item id is derived from the identifier field,
      // a form error should result from using the same identifier
      // twice within the given scope (in this case, r1)
      val call = fakeLoggedInHtmlRequest(privilegedUser,
        controllers.institutions.routes.Repositories.createDocPost("r1"))
      val cr1 = route(call, testData).get
      status(cr1) must equalTo(SEE_OTHER)
      // okay the first time
      val cr2 = route(call, testData).get
      status(cr2) must equalTo(BAD_REQUEST)
      // NB: This error string comes from the server, so might
      // not match if changed there - single quotes surround the value
      contentAsString(cr2) must contain("exists and must be unique")

      // Submit a third time with extra @Relation data, as a test for issue #124
      val testData2 = testData ++ Map(
        "descriptions[0].languageCode" -> Seq("eng"),
        "descriptions[0].identityArea.name" -> Seq("Hello Kitty"),
        "descriptions[0].identityArea.dates[0].startDate" -> Seq("1939-01-01"),
        "descriptions[0].identityArea.dates[0].endDate" -> Seq("1945-01-01")
      )
      val cr3 = route(call, testData2).get
      status(cr3) must equalTo(BAD_REQUEST)
      // NB: This error string comes from the server, so might
      // not match if changed there - single quotes surround the value
      contentAsString(cr3) must contain("exists and must be unique")
    }

    "give a form error when saving an item with with a bad date" in new ITestApp {
      val testData: Map[String, Seq[String]] = Map(
        "identifier" -> Seq("c1"),
        "descriptions[0].identityArea.dates[0].startDate" -> Seq("1945-01-01"),
        "descriptions[0].identityArea.dates[0].endDate" -> Seq("1945-12-32") // BAD!
      )
      // Since the item id is derived from the identifier field,
      // a form error should result from using the same identifier
      // twice within the given scope (in this case, r1)
      val cr = route(fakeLoggedInHtmlRequest(privilegedUser,
        controllers.institutions.routes.Repositories.createDocPost("r1")), testData).get
      status(cr) must equalTo(BAD_REQUEST)
      // If we were doing validating dates we'd use:
      contentAsString(cr) must contain(Messages("error.date"))
    }

    "allow updating items when logged in as privileged user" in new ITestApp {
      val testData: Map[String, Seq[String]] = Map(
        "identifier" -> Seq("c1"),
        "descriptions[0].languageCode" -> Seq("eng"),
        "descriptions[0].identityArea.name" -> Seq("Collection 1"),
        "descriptions[0].identityArea.parallelFormsOfName[0]" -> Seq("Collection 1 Parallel Name"),
        "descriptions[0].contentArea.scopeAndContent" -> Seq("New Content for c1"),
        "descriptions[0].contextArea.acquistition" -> Seq("Acquisistion info"),
        "descriptions[0].notes[0]" -> Seq("Test Note"),
        "publicationStatus" -> Seq("Draft")
      )
      val cr = route(fakeLoggedInHtmlRequest(privilegedUser, docRoutes.updatePost("c1")), testData).get
      status(cr) must equalTo(SEE_OTHER)

      val show = route(fakeLoggedInHtmlRequest(privilegedUser, GET, redirectLocation(cr).get)).get
      status(show) must equalTo(OK)
      contentAsString(show) must contain("Collection 1 Parallel Name")
      contentAsString(show) must contain("New Content for c1")
      contentAsString(show) must contain("Test Note")
      indexEventBuffer.last must equalTo("c1")
    }

    "allow updating an item with a custom log message" in new ITestApp {
      val msg = "Imma updating this item!"
      val testData: Map[String, Seq[String]] = Map(
        "identifier" -> Seq("c1"),
        "descriptions[0].languageCode" -> Seq("eng"),
        "descriptions[0].identityArea.name" -> Seq("Collection 1 - Updated"),
        "logMessage" -> Seq(msg)
      )
      val cr = route(fakeLoggedInHtmlRequest(privilegedUser, docRoutes.updatePost("c1")), testData).get
      status(cr) must equalTo(SEE_OTHER)

      // Get the item history page and check the message is there...
      val show = route(fakeLoggedInHtmlRequest(privilegedUser, docRoutes.history("c1"))).get
      status(show) must equalTo(OK)
      // Log message should be in the history section...
      contentAsString(show) must contain(msg)
      indexEventBuffer.last must equalTo("c1")
    }

    "disallow updating items when logged in as unprivileged user" in new ITestApp {
      val testData: Map[String, Seq[String]] = Map(
        "identifier" -> Seq("c4"),
        "descriptions[0].languageCode" -> Seq("eng"),
        "descriptions[0].identityArea.name" -> Seq("Collection 4"),
        "descriptions[0].contentArea.scopeAndContent" -> Seq("New Content for c4"),
        "publicationStatus" -> Seq("Draft")
      )

      val cr = route(fakeLoggedInHtmlRequest(unprivilegedUser, docRoutes.updatePost("c4")), testData).get
      status(cr) must equalTo(FORBIDDEN)

      // We can view the item when not logged in...
      val show = route(fakeLoggedInHtmlRequest(unprivilegedUser, docRoutes.get("c4"))).get
      status(show) must equalTo(OK)
      contentAsString(show) must not contain "New Content for c4"
      indexEventBuffer.last must not equalTo "c4"
    }

    "allow adding extra descriptions" in new ITestApp {
      val testItem = "c1"
      val testData: Map[String, Seq[String]] = Map(
        "languageCode" -> Seq("eng"),
        "identityArea.name" -> Seq("A Second Description"),
        "contentArea.scopeAndContent" -> Seq("This is a second description")
      )
      // Now try again to update the item, which should succeed
      // Check we can update the item
      val cr = route(fakeLoggedInHtmlRequest(privilegedUser,
        docRoutes.createDescriptionPost(testItem)), testData).get
      status(cr) must equalTo(SEE_OTHER)
      val getR = route(fakeLoggedInHtmlRequest(privilegedUser, GET, redirectLocation(cr).get)).get
      status(getR) must equalTo(OK)
      contentAsString(getR) must contain("This is a second description")
      indexEventBuffer.last must equalTo("c1")
    }

    "allow updating individual descriptions" in new ITestApp {
      val testItem = "c1"
      val testItemDesc = "cd1"
      val testData: Map[String, Seq[String]] = Map(
        "languageCode" -> Seq("eng"),
        "id" -> Seq("cd1"),
        "identityArea.name" -> Seq("An Updated Description"),
        "contentArea.scopeAndContent" -> Seq("This is an updated description")
      )
      // Now try again to update the item, which should succeed
      // Check we can update the item
      val cr = route(fakeLoggedInHtmlRequest(privilegedUser,
        docRoutes.updateDescriptionPost(testItem, testItemDesc)), testData).get
      status(cr) must equalTo(SEE_OTHER)
      val getR = route(fakeLoggedInHtmlRequest(privilegedUser, GET, redirectLocation(cr).get)).get
      status(getR) must equalTo(OK)
      contentAsString(getR) must contain("This is an updated description")
      contentAsString(getR) must not contain "Some description text for c1"
      indexEventBuffer.last must equalTo("c1")
    }

    "allow deleting individual descriptions" in new ITestApp {
      val testItem = "c1"
      val testItemDesc = "cd1-2"
      // Now try again to update the item, which should succeed
      // Check we can update the item
      val cr = route(fakeLoggedInHtmlRequest(privilegedUser,
        docRoutes.deleteDescriptionPost(testItem, testItemDesc))).get
      status(cr) must equalTo(SEE_OTHER)
      val getR = route(fakeLoggedInHtmlRequest(privilegedUser, GET, redirectLocation(cr).get)).get
      status(getR) must equalTo(OK)
      contentAsString(getR) must not contain "Some alternate description text for c1"
      indexEventBuffer.last must equalTo("cd1-2")
    }

    "allow updating visibility" in new ITestApp {
      val test1 = route(fakeLoggedInHtmlRequest(unprivilegedUser, docRoutes.get("c1"))).get
      status(test1) must equalTo(NOT_FOUND)
      // Make item visible to user
      val data = Map(backend.rest.Constants.ACCESSOR_PARAM -> Seq(unprivilegedUser.id))
      val cr = route(fakeLoggedInHtmlRequest(privilegedUser, docRoutes.visibilityPost("c1")), data).get
      status(cr) must equalTo(SEE_OTHER)
      val test2 = route(fakeLoggedInHtmlRequest(unprivilegedUser, docRoutes.get("c1"))).get
      status(test2) must equalTo(OK)
    }
  }
  
  "Documentary Unit link/annotate functionality" should {

    "contain correct access point links" in new ITestApp {
      val show = route(fakeLoggedInHtmlRequest(privilegedUser, docRoutes.get("c1"))).get
      contentAsString(show) must contain("access-point-links")
      contentAsString(show) must contain(
        controllers.authorities.routes.HistoricalAgents.get("a1").url)
    }

    "contain correct annotation links" in new ITestApp {
      val show = route(fakeLoggedInHtmlRequest(privilegedUser, docRoutes.get("c1"))).get
      contentAsString(show) must contain("annotation-links")
      contentAsString(show) must contain(
        docRoutes.get("c4").url)
    }

    "allow linking to items via annotation" in new ITestApp {
      val testItem = "c1"
      val linkSrc = "cvocc1"
      val body = "This is a link"
      val testData: Map[String, Seq[String]] = Map(
        LinkF.LINK_TYPE -> Seq(LinkF.LinkType.Associative.toString),
        LinkF.DESCRIPTION -> Seq(body)
      )
      val cr = route(fakeLoggedInHtmlRequest(privilegedUser,
        docRoutes.linkAnnotatePost(testItem, EntityType.Concept, linkSrc)), testData).get
      status(cr) must equalTo(SEE_OTHER)
      val getR = route(fakeLoggedInHtmlRequest(privilegedUser, GET, redirectLocation(cr).get)).get
      status(getR) must equalTo(OK)
      contentAsString(getR) must contain(linkSrc)
      contentAsString(getR) must contain(body)
    }

    "allow linking to multiple items via a single form submission" in new ITestApp {
      val testItem = "c1"
      val body1 = "This is a link 1"
      val body2 = "This is a link 2"
      val testData: Map[String, Seq[String]] = Map(
        "link[0].id" -> Seq("c2"),
        "link[0].data." + LinkF.LINK_TYPE -> Seq(LinkF.LinkType.Associative.toString),
        "link[0].data." + LinkF.DESCRIPTION -> Seq(body1),
        "link[1].id" -> Seq("c3"),
        "link[1].data." + LinkF.LINK_TYPE -> Seq(LinkF.LinkType.Associative.toString),
        "link[1].data." + LinkF.DESCRIPTION -> Seq(body2)
      )
      val cr = route(fakeLoggedInHtmlRequest(privilegedUser,
        docRoutes.linkMultiAnnotatePost(testItem)), testData).get
      status(cr) must equalTo(SEE_OTHER)
      val getR = route(fakeLoggedInHtmlRequest(privilegedUser, GET, redirectLocation(cr).get)).get
      status(getR) must equalTo(OK)
      contentAsString(getR) must contain("c2")
      contentAsString(getR) must contain(body1)
      contentAsString(getR) must contain("c3")
      contentAsString(getR) must contain(body2)
    }
  }
  
  "Documentary unit permissions functionality" should {

    "should redirect to login page when permission denied when not logged in" in new ITestApp {
      val show = route(FakeRequest(docRoutes.get("c1"))
        .withHeaders(HeaderNames.ACCEPT -> MimeTypes.HTML)).get
      status(show) must equalTo(SEE_OTHER)
    }

    "allow granting permissions to create a doc within the scope of r2" in new ITestApp {

      val testRepo = "r2"
      val testData: Map[String, Seq[String]] = Map(
        "identifier" -> Seq("test"),
        "descriptions[0].languageCode" -> Seq("eng"),
        "descriptions[0].identityArea.name" -> Seq("Test Item"),
        "descriptions[0].contentArea.scopeAndContent" -> Seq("A test"),
        "publicationStatus" -> Seq("Draft")
      )

      // Trying to create the item should fail initially.
      // Check we cannot create an item...
      val cr = route(fakeLoggedInHtmlRequest(unprivilegedUser,
        controllers.institutions.routes.Repositories.createDocPost("r2")), testData).get
      status(cr) must equalTo(FORBIDDEN)

      // Grant permissions to create docs within the scope of r2
      val permTestData: Map[String, List[String]] = Map(
        ContentTypes.DocumentaryUnit.toString -> List("create", "update", "delete")
      )
      val permReq = route(fakeLoggedInHtmlRequest(privilegedUser,
        controllers.institutions.routes.Repositories
          .setScopedPermissionsPost(testRepo, EntityType.UserProfile, unprivilegedUser.id)), permTestData).get
      status(permReq) must equalTo(SEE_OTHER)
      // Now try again and create the item... it should succeed.
      // Check we cannot create an item...
      val cr2 = route(fakeLoggedInHtmlRequest(unprivilegedUser,
        controllers.institutions.routes.Repositories.createDocPost(testRepo)), testData).get
      status(cr2) must equalTo(SEE_OTHER)
      val getR = route(fakeLoggedInHtmlRequest(unprivilegedUser, GET, redirectLocation(cr2).get)).get
      status(getR) must equalTo(OK)
    }

    "allow granting permissions on a specific item" in new ITestApp {

      val testItem = "c4"
      val testData: Map[String, Seq[String]] = Map(
        "identifier" -> Seq(testItem),
        "descriptions[0].languageCode" -> Seq("eng"),
        "descriptions[0].identityArea.name" -> Seq("Changed Name"),
        "descriptions[0].contentArea.scopeAndContent" -> Seq("A test"),
        "publicationStatus" -> Seq("Draft")
      )

      // Trying to create the item should fail initially.
      // Check we cannot create an item...
      val cr = route(fakeLoggedInHtmlRequest(unprivilegedUser, docRoutes.updatePost(testItem)), testData).get
      status(cr) must equalTo(FORBIDDEN)

      // Grant permissions to update item c1
      val permTestData: Map[String, List[String]] = Map(
        ContentTypes.DocumentaryUnit.toString -> List("update")
      )
      val permReq = route(fakeLoggedInHtmlRequest(privilegedUser,
        docRoutes.setItemPermissionsPost(testItem, EntityType.UserProfile, unprivilegedUser.id)), permTestData).get
      status(permReq) must equalTo(SEE_OTHER)
      // Now try again to update the item, which should succeed
      // Check we can update the item
      val cr2 = route(fakeLoggedInHtmlRequest(unprivilegedUser,
        docRoutes.updatePost(testItem)), testData).get
      status(cr2) must equalTo(SEE_OTHER)
      val getR = route(fakeLoggedInHtmlRequest(unprivilegedUser, GET, redirectLocation(cr2).get)).get
      status(getR) must equalTo(OK)
    }    
  }
}
