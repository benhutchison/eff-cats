package org.atnos.eff.addon.cats.effect

import org.atnos.eff._
import org.atnos.eff.all._
import org.atnos.eff.syntax.all._

import org.specs2._
import org.specs2.concurrent._

import cats._
import cats.implicits._

import cats.laws.discipline.arbitrary._

import cats.effect._
import cats.effect.laws.discipline._
import cats.effect.laws.util._
import cats.effect.laws.util.TestInstances._

import IOEffect._
import org.atnos.eff.syntax.addon.cats.effect._

import org.typelevel.discipline.specs2._
import org.scalacheck._

import scala.concurrent.duration._

class IOEffectSpec(implicit ee: ExecutionEnv) extends Specification with ScalaCheck with Discipline { def is = "io".title ^ sequential ^ s2"""

 IO effects can work as normal values                    $e1
 IO effects can be attempted                             $e2
 IO effects are stacksafe with recursion                 $e3

 Async boundaries can be introduced between computations $e4
 IO effect is stacksafe with traverseA                   $e5
 IO effect is a lawful Async                             $e6

//disabled until the weaker Async laws are green
// IO effect is a lawful ConcurrentEffect

"""

  type S = Fx.fx2[IO, Option]

  def e1 = {
    def action[R :_io :_option]: Eff[R, Int] = for {
      a <- ioDelay(10)
      b <- ioDelay(20)
    } yield a + b

    action[S].runOption.unsafeRunTimed(5.seconds).flatten must beSome(30)
  }

  def e2 = {
    def action[R :_io :_option]: Eff[R, Int] = for {
      a <- ioDelay(10)
      b <- ioDelay { boom; 20 }
    } yield a + b

    action[S].ioAttempt.runOption.unsafeRunTimed(5.seconds).flatten must beSome(beLeft(boomException))
  }

  def e3 = {
    type R = Fx.fx1[IO]

    def loop(i: Int): IO[Eff[R, Int]] =
      if (i == 0) IO.pure(Eff.pure(1))
      else IO.pure(ioSuspend(loop(i - 1)).map(_ + 1))

    ioSuspend(loop(1000)).unsafeRunSync must not(throwAn[Exception])
  }

  def e4 = {
    def action[R :_io :_option]: Eff[R, Int] = for {
      a <- ioDelay(10).ioShift
      b <- ioDelay(20)
    } yield a + b

    action[S].runOption.unsafeRunTimed(5.seconds).flatten must beSome(30)
  }

  def e5 = {
    val action = (1 to 5000).toList.traverseA { i =>
      if (i % 5 == 0) ioDelay(i).ioShift
      else            ioDelay(i)
    }

    action.unsafeRunAsync(_ => ()) must not(throwA[Throwable])
  }

  implicit def runIO: Eff[S, Unit] => IO[Unit] = _.runOption.map(_.getOrElse(())).to[IO]
  implicit val ce = IOEffect.effectInstance[S]
  implicit def arbitraryEff[A: Arbitrary]: Arbitrary[Eff[S, A]] = Arbitrary(Arbitrary.arbitrary[A].map(_.pureEff[S]))
  implicit def eqEff[A: Eq]: Eq[Eff[S, A]] = Eq.instance((e1, e2) =>
    e1.runOption.unsafeRunSync === e2.runOption.unsafeRunSync)
  implicit val tc = TestContext()


  def e6 = checkAll("Eff", AsyncTests[Eff[S, ?]].async[Int, Int, Int])

//  def e7 = checkAll("Eff", ConcurrentEffectTests[Eff[S, ?]].concurrentEffect[Int, Int, Int])

  /**
   * HELPERS
   */
  def boom: Unit = throw boomException
  val boomException: Throwable = new Exception("boom")

  def sleepFor(duration: FiniteDuration) =
    try Thread.sleep(duration.toMillis) catch { case t: Throwable => () }

}

