package rules.emr

import com.amazonaws.services.elasticmapreduce.model.StepConfig
import org.scalatest._
import rules.HasOwner
import rules.emr.ClusterStepStateWire.StepState
import rx.{Ctx, Var}

import scala.concurrent.duration._
import scala.concurrent.{Future, Promise}

class ClusterDemandInvariantAsyncSpec extends AsyncFlatSpec with Matchers {

  class TestInvariant extends ClusterDemandInvariant with HasOwner {
    override lazy val CLEANUP_DELAY_PERIOD: FiniteDuration = 300.millis
    override implicit lazy val owner: Ctx.Owner = rx.Ctx.Owner.Unsafe.Unsafe
  }

  def fakeStep(name: String): Step = new Step {
    override val stepName: StepName = StepName(name)
    override val config: StepConfig = new StepConfig().withName(name)
  }

  def nextAsFuture[T](inp: Var[T])(implicit owner: Ctx.Owner): Future[T] = {
    val promise: Promise[T] = Promise()
    inp.triggerLater {
      promise.success(inp.now)
    }
    promise.future
  }

  it should "clear scheduled if demand signals success" in {
    implicit val owner = rx.Ctx.Owner.Unsafe.Unsafe

    val test = new TestInvariant
    test.demand() = List(fakeStep("foo"))
    test.detected() = Some(Map.empty[StepName, StepState])
    test.invariant.now.length shouldBe 1

    //Pretend step was scheduled
    test.scheduled() = Set(fakeStep("foo").stepName)
    test.invariant.now.length shouldBe 0

    val wat = nextAsFuture(test.scheduled)

    Thread.sleep(test.CLEANUP_DELAY_PERIOD.toMillis)

    //Pretend step completed successfully
    test.demand() = List.empty

    //But there is jitter
    Thread.sleep(50)
    test.demand() = List(fakeStep("foo"))
    Thread.sleep(50)
    test.demand() = List(fakeStep("bar"))

    wat.map { omg =>
      assert(omg.isEmpty)
    }
  }

//  it should "blah" in {
//    implicit val owner = rx.Ctx.Owner.Unsafe.Unsafe
//
//    import rx.async._
//    import rx.async.Platform._
//    val xxx = Var(0)
////    xxx.debounce(3000.millis).reduce { case (prev,next) if prev != next =>
////      println(s"DEBOUNCED: prev = $prev next = $next")
////      next
////    case (_,n) => println(s"Why?: $n") ; n
////    }
//
//    xxx.delay(2000.millis).reduce { case (prev,next) =>
//      println(s"DELAY: prev = $prev next = $next")
//      next
//    }
//
//    //Thread.sleep(3000)
//    (0 to 30).foreach { x =>
//      //Thread.sleep(1000)
//      xxx() = x
//    }
//
//
//    assert(true)
//  }
}
