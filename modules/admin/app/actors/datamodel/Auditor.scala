package actors.datamodel

import actors.LongRunningJob.Cancel
import akka.actor.{Actor, ActorLogging, ActorRef}
import akka.pattern._
import models._
import services.data.DataUser
import services.search.SearchConstants.CREATION_PROCESS
import services.search._

import scala.concurrent.ExecutionContext

object Auditor {
  sealed trait Action


  case class RunAudit(entityType: EntityType.Value, query: Option[SearchQuery] = None, mandatoryOnly: Boolean = false) extends Action

  case class Batch(entityType: EntityType.Value, items: Seq[Entity], query: SearchQuery, more: Boolean, mandatoryOnly: Boolean) extends Action

  case class Check(item: Entity, errors: Seq[ValidationError])

  case class Completed(count: Int, secs: Long = 0) extends Action

  case class Cancelled(count: Int, secs: Long = 0) extends Action

  case class CheckBatch(entityType: EntityType.Value, checks: Seq[Check], query: SearchQuery, more: Boolean)
}

case class Auditor(searchEngine: SearchEngine, resolver: SearchItemResolver, fieldMetadataSet: FieldMetadataSet)(implicit userOpt: Option[UserProfile], ec: ExecutionContext) extends Actor with ActorLogging {
  import Auditor._
  private implicit val apiUser: DataUser = DataUser(userOpt.map(_.id))

  override def receive: Receive = {
    case e: RunAudit =>
      context.become(running(sender()))
      self ! e
  }

  def running(msgTo: ActorRef): Receive = {
    case RunAudit(entityType, queryOpt, mandatoryOnly) =>
      val params: SearchParams = SearchParams(entities = Seq(entityType))
      val cpFacet = AppliedFacet(CREATION_PROCESS, Seq("MANUAL"))
      val query: SearchQuery = queryOpt.getOrElse(SearchQuery(params = params, appliedFacets = Seq(cpFacet), user = userOpt))

      searchEngine.search(query).flatMap { res =>
        resolver.resolve[Entity](res.page.items).map { list =>
          Batch(entityType, list.flatten, query, res.page.hasMore, mandatoryOnly)
        }
      }.pipeTo(self)

    case Batch(et, items, query, more, mandatoryOnly) =>
      log.info(s"Found ${items.size} items for audit")
      val errors: Seq[Check] = items.flatMap { item =>
        val errs = fieldMetadataSet.validate(item)
        val pErrs = if (mandatoryOnly) errs.collect { case e@MissingMandatoryField(_) => e} else errs
        if (pErrs.nonEmpty) Some(Check(item, pErrs))
        else None
      }

      msgTo ! CheckBatch(et, errors, query, more)

      if (more) {
        val next = query.copy(paging = query.paging.next)
        self ! RunAudit(et, Some(next), mandatoryOnly)
      } else {
        msgTo ! Completed(0, 0)
      }

    case Cancel =>
      sender() ! Cancelled(0, 0)
  }
}
