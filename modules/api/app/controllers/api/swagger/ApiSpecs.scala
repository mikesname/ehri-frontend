package controllers.api.swagger

//import play.api.Configuration
//import com.iheart.playSwagger.SwaggerSpecGenerator
//import play.api.libs.json.JsString
//import play.api.mvc._
//
//class ApiSpecs(cc: ControllerComponents, config: Configuration) extends AbstractController(cc) {
//  implicit val cl = getClass.getClassLoader
//
//  val domainPackage = "YOUR.DOMAIN.PACKAGE"
//  val otherDomainPackage = "YOUR.OtherDOMAIN.PACKAGE"
//  lazy val generator = SwaggerSpecGenerator(domainPackage, otherDomainPackage)
//
//  // Get's host configuration.
//  val host = config.get[String]("swagger.host")
//
//  lazy val swagger = Action { request =>
//    generator.generate().map(_ + ("host" -> JsString(host))).fold(
//      e => InternalServerError("Couldn't generate swagger."),
//      s => Ok(s))
//  }
//
//  def specs = swagger
//}
