package actors.transformation

import java.time.Instant

import actors.transformation.XmlConverter._
import actors.transformation.XmlConverterManager.XmlConvertJob
import akka.actor.SupervisorStrategy.Stop
import akka.actor.{Actor, ActorLogging, ActorRef, OneForOneStrategy, Props, SupervisorStrategy, Terminated}
import akka.stream.Materializer
import models.{DataTransformation, UserProfile}
import services.harvesting.HarvestEventHandle
import services.storage.FileStorage
import services.transformation.XmlTransformer
import utils.WebsocketConstants

import scala.concurrent.ExecutionContext


object XmlConverterManager {

  /**
    * A description of a conversion task.
    *
    * @param transformers the sequence of transformations
    * @param from         the starting date and time
    * @param classifier   the storage classifier on which to save files
    * @param inPrefix     the path prefix of input files
    * @param outPrefix    the replacement output path prefix
    * @param only         a single key to convert. If not given the whole
    *                     classifier will be converted.
    */
  case class XmlConvertData(
    transformers: Seq[(DataTransformation.TransformationType.Value, String)],
    classifier: String,
    inPrefix: String,
    outPrefix: String,
    from: Option[Instant] = None,
    only: Option[String] = None,
  )

  /**
    * A single convert job with a unique ID.
    */
  case class XmlConvertJob(
    repoId: String,
    datasetId: String,
    jobId: String,
    data: XmlConvertData
  )

}

case class XmlConverterManager(job: XmlConvertJob, transformer: XmlTransformer, storage: FileStorage)(
  implicit userOpt: Option[UserProfile], mat: Materializer, ec: ExecutionContext) extends Actor with ActorLogging {

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
      val runner = context.actorOf(Props(XmlConverter(job, transformer, storage)))
      context.become(running(runner, Set(chan), Option.empty))
      runner ! Initial
  }

  /**
    * Running state.
    *
    * @param runner the harvest runner actor
    * @param subs   a set of subscribers to message w/ updates
    * @param handle a handle through which the job logging can be concluded
    */
  def running(runner: ActorRef, subs: Set[ActorRef], handle: Option[HarvestEventHandle]): Receive = {

    // Add a new message subscriber
    case chan: ActorRef =>
      log.debug(s"Added new message subscriber, ${subs.size}")
      context.watch(chan)
      context.become(running(runner, subs + chan, handle))

    case Terminated(actor) if actor == runner =>
      context.stop(self)

    // Remove terminated subscribers
    case Terminated(chan) =>
      log.debug(s"Removing subscriber: $chan")
      context.unwatch(chan)
      context.become(running(runner, subs - chan, handle))

    case Counting =>
      msg(s"Counting files...", subs)

    case Counted(total) =>
      msg(s"Total of $total file(s)", subs)

    // Confirmation the runner has started
    case Starting =>
      msg(s"Starting convert with job id: ${job.jobId}", subs)

    // Cancel conversion... here we tell the runner to exit
    // and shut down on its termination signal...
    case Cancel => runner ! Cancel

    // A file has been converted
    case DoneFile(id) =>
      msg(id, subs)

    case Resuming(from) =>
      msg(s"Resuming from marker: $from", subs)

    // Received confirmation that the runner has shut down
    case Cancelled(count, secs) =>
      msg(s"${WebsocketConstants.ERR_MESSAGE}: cancelled after $count file(s) in $secs seconds", subs)
      context.stop(self)

    // The runner has completed, so we log the
    // event and shut down too
    case Completed(count, secs) =>
      msg(s"${WebsocketConstants.DONE_MESSAGE}: " +
        s"converted $count file(s) in $secs seconds", subs)
      context.stop(self)

    // The runner has thrown a conversion error. Log the event but do not shut down...
    case Error(id, e) =>
      msg(s"$id: conversion error: ${e.getMessage}", subs)

    // We've encountered an expected error...
    case e: Throwable =>
      msg(s"${WebsocketConstants.ERR_MESSAGE}: unexpected error in file conversion ${e.getMessage}", subs)
      context.stop(self)

    case m =>
      log.error(s"Unexpected message: $m")
  }

  private def msg(s: String, subs: Set[ActorRef]): Unit = {
    log.info(s + s" (subscribers: ${subs.size})")
    subs.foreach(_ ! s)
  }
}