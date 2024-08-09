package actors.datamodel

import actors.LongRunningJob.Cancel
import actors.datamodel.Auditor.{Cancelled, CheckBatch, Completed, RunAudit}
import actors.datamodel.AuditorManager.AuditorJob
import akka.actor.SupervisorStrategy.Stop
import akka.actor.{Actor, ActorLogging, ActorRef, OneForOneStrategy, Props, SupervisorStrategy, Terminated}
import akka.stream.Materializer
import models.{EntityType, FieldMetadataSet, MissingMandatoryField, UserProfile}
import play.api.i18n.Messages
import services.search.{SearchEngine, SearchItemResolver}
import utils.WebsocketConstants

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt

object AuditorManager {

  case class AuditorJob(jobId: String, entityType: EntityType.Value, mandatoryOnly: Boolean = false)

}

case class AuditorManager(job: AuditorJob, searchEngine: SearchEngine, searchItemResolver: SearchItemResolver, fieldMetadataSet: FieldMetadataSet)(
  implicit userOpt: Option[UserProfile], messages: Messages, mat: Materializer, ec: ExecutionContext) extends Actor with ActorLogging {

  override def supervisorStrategy: SupervisorStrategy = OneForOneStrategy() {
    case e =>
      self ! e
      Stop
  }

  // Ready state: we've received a job but won't actually start
  // until there is a channel to talk through
  override def receive: Receive = {
    case chan: ActorRef =>
      log.debug("Received initial subscriber, starting...")
      val runner = context.actorOf(Props(Auditor(searchEngine, searchItemResolver, fieldMetadataSet)))
      context.become(running(runner, Set(chan)))
      runner ! RunAudit(job.entityType, None, job.mandatoryOnly)
  }

  /**
    * Running state.
    *
    * @param runner the harvest runner actor
    * @param subs   a set of subscribers to message w/ updates
    */
  def running(runner: ActorRef, subs: Set[ActorRef]): Receive = {

    // Add a new message subscriber
    case chan: ActorRef =>
      log.debug(s"Added new message subscriber, ${subs.size}")
      context.watch(chan)
      context.become(running(runner, subs + chan))

    case Terminated(actor) if actor == runner =>
      log.debug(s"Actor terminated: $actor")
      context.system.scheduler.scheduleOnce(5.seconds, self,
        "Convert runner unexpectedly shut down")

    // Remove terminated subscribers
    case Terminated(chan) =>
      log.debug(s"Removing subscriber: $chan")
      context.unwatch(chan)
      context.become(running(runner, subs - chan))

    // Cancel conversion... here we tell the runner to exit
    // and shut down on its termination signal...
    case Cancel =>
      runner ! Cancel

    // A file has been converted
    case CheckBatch(_, checks, _, _) =>
      if (checks.isEmpty) {
        log.info("No errors found")
        msg(Messages("dataModel.audit.noErrors"), subs)
      } else checks.foreach { check =>
        val countMandatory = check.errors.collect { case e: MissingMandatoryField => e }.size
        val countDesirable = check.errors.size - countMandatory
        msg(s"Check: ${check.item.id} - mandatory: $countMandatory, desirable: $countDesirable", subs)
      }

    // Received confirmation that the runner has shut down
    case Cancelled(count, secs) =>
      msg(Messages("dataModel.audit.cancelled", WebsocketConstants.ERR_MESSAGE, count, secs), subs)
      context.stop(self)

    // The runner has completed, so we log the
    // event and shut down too
    case Completed(count, secs) =>
      msg(Messages("conversion.completed", WebsocketConstants.DONE_MESSAGE, count, secs), subs)
      context.stop(self)

    // Cancel conversion... here we tell the runner to exit
    // and shut down on its termination signal...
    case Cancel =>
      runner ! Cancel

    case m =>
      log.error(s"Unexpected message: $m")
  }

  private def msg(s: String, subs: Set[ActorRef]): Unit = {
    log.info(s + s" (subscribers: ${subs.size})")
    subs.foreach(_ ! s)
  }
}
