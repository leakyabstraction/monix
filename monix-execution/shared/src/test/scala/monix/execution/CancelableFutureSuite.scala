/*
 * Copyright (c) 2014-2016 by its authors. Some rights reserved.
 * See the project homepage at: https://monix.io
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package monix.execution

import minitest.TestSuite
import monix.execution.schedulers.TestScheduler
import scala.concurrent.duration._
import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success}

object CancelableFutureSuite extends TestSuite[TestScheduler] {
  def setup() = TestScheduler()
  def tearDown(env: TestScheduler): Unit =
    assert(env.state.get.tasks.isEmpty, "should not have tasks left to execute")

  test("CancelableFuture.fromTry(success)") { implicit s =>
    val f = CancelableFuture.fromTry(Success(1))
    assertEquals(f.value, Some(Success(1)))
  }

  test("CancelableFuture.fromTry(failure)") { implicit s =>
    val ex = new RuntimeException("dummy")
    val f = CancelableFuture.fromTry(Failure(ex))
    assertEquals(f.value, Some(Failure(ex)))
  }

  test("CancelableFuture.successful is already completed") { implicit s =>
    val f = CancelableFuture.successful(1)
    assertEquals(f.isCompleted, true)
    assertEquals(f.value, Some(Success(1)))
    f.cancel()
    val f2 = f.failed.value
    assert(f2.isDefined && f2.get.isFailure, "f.failed should be completed as well")
  }

  test("cancellation works") { implicit s =>
    val p = Promise[Unit]()
    val task = s.scheduleOnce(10.seconds)(p.success(()))
    val f = CancelableFuture(p.future, task)

    s.tick()
    assertEquals(f.value, None)

    assert(s.state.get.tasks.nonEmpty, "tasks.nonEmpty")
    f.cancel()
    assert(s.state.get.tasks.isEmpty, "tasks.isEmpty")
    assertEquals(f.value, None)
  }

  test("now.failed") { implicit s =>
    val dummy = new RuntimeException("dummy")
    val f = CancelableFuture.failed(dummy).failed
    assertEquals(f.value, Some(Success(dummy)))
  }

  test("async.failed") { implicit s =>
    val dummy = new RuntimeException("dummy")
    val f = CancelableFuture(Future(throw dummy), Cancelable.empty).failed
    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(dummy)))
  }

  test("now.map.failed") { implicit s =>
    val dummy = new RuntimeException("dummy")
    val f = CancelableFuture.failed[Int](dummy).map(_+1).failed
    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(dummy)))
  }

  test("now.transform") { implicit s =>
    val f = CancelableFuture.successful(1).transform(_+1, ex => ex)
    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(2)))
  }

  test("async.transform") { implicit s =>
    val f = CancelableFuture(Future(1), Cancelable.empty).transform(_+1, ex => ex)
    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(2)))
  }

  test("now.map.transform") { implicit s =>
    val f = CancelableFuture.successful(1).map(_+1).transform(_+1, ex => ex)
    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(3)))
  }

  test("now.map") { implicit s =>
    val f = CancelableFuture.successful(1).map(_+1)
    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(2)))
  }

  test("async.map") { implicit s =>
    val f = CancelableFuture(Future(1), Cancelable.empty).map(_+1)
    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(2)))
  }

  test("now.map.map") { implicit s =>
    val f = CancelableFuture.successful(1).map(_+1).map(_+1)
    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(3)))
  }

  test("now.flatMap") { implicit s =>
    val f = CancelableFuture.successful(1)
      .flatMap(x => CancelableFuture.successful(x+1))

    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(2)))
  }

  test("async.flatMap") { implicit s =>
    val f = CancelableFuture(Future(1), Cancelable.empty)
      .flatMap(x => CancelableFuture.successful(x+1))

    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(2)))
  }

  test("now.map.flatMap") { implicit s =>
    val f = CancelableFuture.successful(1).map(_+1)
      .flatMap(x => CancelableFuture.successful(x+1))

    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(3)))
  }

  test("now.filter") { implicit s =>
    val f = CancelableFuture.successful(1).filter(_ == 1)
    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(1)))
  }

  test("async.filter") { implicit s =>
    val f = CancelableFuture(Future(1), Cancelable.empty).filter(_ == 1)
    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(1)))
  }

  test("now.map.filter") { implicit s =>
    val f = CancelableFuture.successful(1).map(_+1).filter(_ == 2)
    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(2)))
  }

  test("now.collect") { implicit s =>
    val f = CancelableFuture.successful(1).collect { case x => x + 1 }
    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(2)))
  }

  test("async.collect") { implicit s =>
    val f = CancelableFuture(Future(1), Cancelable.empty).collect { case x => x + 1 }
    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(2)))
  }

  test("now.map.collect") { implicit s =>
    val f = CancelableFuture.successful(1).map(_+1).collect { case x => x + 1 }
    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(3)))
  }

  test("now.recover") { implicit s =>
    val ex = new RuntimeException("dummy")
    val f = CancelableFuture.failed(ex).recover { case _ => 1 }
    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(1)))
  }

  test("async.recover") { implicit s =>
    val ex = new RuntimeException("dummy")
    val f = CancelableFuture(Future(throw ex), Cancelable.empty).recover { case _ => 1 }
    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(1)))
  }

  test("now.map.recover") { implicit s =>
    val ex = new RuntimeException("dummy")
    val f = CancelableFuture.failed[Int](ex).map(_+1).recover { case _ => 1 }
    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(1)))
  }

  test("now.recoverWith") { implicit s =>
    val ex = new RuntimeException("dummy")
    val f = CancelableFuture.failed(ex)
      .recoverWith { case _ => CancelableFuture.successful(1) }

    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(1)))
  }

  test("async.recoverWith") { implicit s =>
    val ex = new RuntimeException("dummy")
    val f = CancelableFuture(Future(throw ex), Cancelable.empty)
      .recoverWith { case _ => CancelableFuture.successful(1) }

    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(1)))
  }

  test("now.map.recoverWith") { implicit s =>
    val ex = new RuntimeException("dummy")
    val f = CancelableFuture.failed[Int](ex).map(_+1)
      .recoverWith { case _ => CancelableFuture.successful(1) }

    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(1)))
  }

  test("now.zip(now)") { implicit s =>
    val f = CancelableFuture.successful(1)
      .zip(CancelableFuture.successful(1))
      .map { case (x,y) => x + y }

    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(2)))
  }

  test("async.zip(Async)") { implicit s =>
    val f = CancelableFuture(Future(1), Cancelable.empty)
      .zip(CancelableFuture(Future(1), Cancelable.empty))
      .map { case (x,y) => x + y }

    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(2)))
  }

  test("now.map.zip(now.map)") { implicit s =>
    val f = CancelableFuture.successful(1).map(_+1)
      .zip(CancelableFuture.successful(1).map(_+1))
      .map { case (x,y) => x + y }

    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(4)))
  }

  test("now.fallbackTo") { implicit s =>
    val ex = new RuntimeException("dummy")
    val f = CancelableFuture.failed(ex)
      .fallbackTo(CancelableFuture.successful(1))

    assertEquals(f.value, Some(Success(1)))
  }

  test("async.fallbackTo") { implicit s =>
    val ex = new RuntimeException("dummy")
    val f = CancelableFuture(Future(throw ex), Cancelable.empty)
      .fallbackTo(CancelableFuture.successful(1))

    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(1)))
  }

  test("now.map.fallbackTo") { implicit s =>
    val ex = new RuntimeException("dummy")
    val f = CancelableFuture.failed[Int](ex).map(_+1)
      .fallbackTo(CancelableFuture.successful(1))

    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(1)))
  }

  test("now.mapTo") { implicit s =>
    val f = CancelableFuture.successful(1).mapTo[Int]
    assertEquals(f.value, Some(Success(1)))
  }

  test("async.mapTo") { implicit s =>
    val f = CancelableFuture(Future(1), Cancelable.empty).mapTo[Int]
    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(1)))
  }

  test("now.map.mapTo") { implicit s =>
    val f = CancelableFuture.successful(1).map(_+1).mapTo[Int]
    assertEquals(f.value, None)
    s.tick()
    assertEquals(f.value, Some(Success(2)))
  }

  test("now.andThen") { implicit s =>
    val f = CancelableFuture.successful(1).andThen { case Success(x) => x+1 }
    s.tick()
    assertEquals(f.value, Some(Success(1)))
  }

  test("async.andThen") { implicit s =>
    val f = CancelableFuture(Future(1), Cancelable.empty).andThen { case Success(x) => x+1 }
    s.tick()
    assertEquals(f.value, Some(Success(1)))
  }

  test("now.map.andThen") { implicit s =>
    val f = CancelableFuture.successful(1).map(_+1).andThen { case Success(x) => x+1 }
    s.tick()
    assertEquals(f.value, Some(Success(2)))
  }
}
