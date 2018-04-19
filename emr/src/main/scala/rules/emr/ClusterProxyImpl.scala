package rules.emr

import akka.typed.{ActorRef, Behavior}
import akka.typed.scaladsl.{Actor, ActorContext, TimerScheduler}
import com.amazonaws.services.elasticmapreduce.AmazonElasticMapReduce
import rules.{HasOwner, Wire}
import com.amazonaws.services.elasticmapreduce.model.AddJobFlowStepsRequest
import rules.emr.ClusterManager.RunningCluster
import rules.emr.ClusterStepStateWire.StepState
import concurrent.duration._

class ClusterProxyImpl(
    emr: AmazonElasticMapReduce,
    target: RunningCluster,
    demandWire: ActorRef[Wire.Command[List[Step]]],
    stepsWire: ActorRef[Wire.Command[Map[StepName, StepState]]],
    timer: TimerScheduler[ClusterProxyImpl.Command],
    override val CLEANUP_DELAY_PERIOD: FiniteDuration
)(implicit override val owner: rx.Ctx.Owner)
    extends ClusterDemandInvariant
    with HasOwner {
  import ClusterProxyImpl._

  private def scheduleStep(ctx: ActorContext[ClusterProxyImpl.Command])(
      steps: List[Step]): Unit = if (steps.nonEmpty) {

    scheduled() = scheduled.now ++ steps.map(_.stepName)

    val req = new AddJobFlowStepsRequest()
      .withSteps(steps.map(_.config): _*)
      .withJobFlowId(target.clusterId.value)

    try {
      emr.addJobFlowSteps(req)
    } catch {
      case e: Exception =>
        ctx.system.log
          .warning("Attempting to addJobFlowStep failed: {}!", e.getMessage)
        val toRemove = steps.map(_.stepName).toSet

        //Prevent spamming reattempts when emr.addJobFlowStep fails (ie no internet access)
        timer.startSingleTimer(ScheduleReattemptKey,
                               AllowReattempt(toRemove),
                               2.minutes)
    }
  }

  val init: Behavior[Command] = Actor.deferred { ctx =>
    ctx.spawnAnonymous(Wire.toVar(demand, demandWire))
    ctx.spawnAnonymous(Wire.toOptionVar(detected, stepsWire))

    //Actually Schedule on emr
    invariant.foreach { scheduleStep(ctx) }

    Actor.immutable { (_, msg) =>
      msg match {
        case Refresh =>
          scheduled() = Set.empty[StepName] //todo: should be scheduled() = Set.empty -- fix scala.rx (variance!)

        case AllowReattempt(steps) =>
          scheduled() = scheduled.now diff steps
      }
      Actor.same
    }
  }

}

object ClusterProxyImpl {
  sealed trait Command
  case object Refresh extends Command

  sealed trait Internal extends Command
  case class AllowReattempt(steps: Set[StepName]) extends Internal
  case object ScheduleReattemptKey

  import concurrent.duration._

  def apply(
      emr: AmazonElasticMapReduce,
      target: RunningCluster,
      pollStepsInterval: FiniteDuration,
      demandWire: ActorRef[Wire.Command[List[Step]]],
      deboucePeriod: FiniteDuration
  )(implicit owner: rx.Ctx.Owner): Behavior[Command] = {
    Actor.deferred[Command] { ctx =>
      val stepsWire = ctx.spawn(
        ClusterStepStateWire(emr, target, pollStepsInterval),
        "cluster-steps"
      )
      Actor.withTimers { timer =>
        val proxy =
          new ClusterProxyImpl(emr, target, demandWire, stepsWire, timer, deboucePeriod)
        proxy.init
      }
    }
  }
}
