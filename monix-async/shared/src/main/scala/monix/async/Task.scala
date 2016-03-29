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

package monix.async

import monix.async.Task._
import monix.execution.{Cancelable, Scheduler}
import monix.execution.cancelables.{SingleAssignmentCancelable, StackedCancelable}
import org.sincron.atomic.Atomic
import scala.annotation.tailrec
import scala.concurrent.{Future, Promise}
import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success, Try}
import scala.util.control.NonFatal

/** `Task` represents a specification for an asynchronous computation,
  * which when executed will produce an `A` as a result, along with
  * possible side-effects.
  *
  * Compared with `Future` from Scala's standard library, `Task` does
  * not represent a running computation or a value detached from time,
  * as `Task` does not execute anything when working with its builders
  * or operators and it does not submit any work into any thread-pool,
  * the execution eventually taking place only after `runAsync`
  * is called and not before that.
  *
  * Note that `Task` is conservative in how it spawns logical threads.
  * Transformations like `map` and `flatMap` for example will default
  * to being executed on the logical thread on which the asynchronous
  * computation was started. But one shouldn't make assumptions about
  * how things will end up executed, as ultimately it is the
  * implementation's job to decide on the best execution model. All
  * you are guaranteed is asynchronous execution after executing
  * `runAsync`.
  */
sealed abstract class Task[+A] { self =>
  /** Triggers the asynchronous execution.
    *
    * @param cb is a callback that will be invoked upon completion.
    * @return a [[monix.execution.Cancelable Cancelable]] that can
    *         be used to cancel a running task
    */
  def runAsync(cb: Callback[A])(implicit s: Scheduler): Cancelable = {
    val conn = StackedCancelable()
    Task.resume[A](s, conn, this, Callback.safe(cb), Nil)
    conn
  }

  /** Triggers the asynchronous execution.
    *
    * @param f is a callback that will be invoked upon completion.
    * @return a [[monix.execution.Cancelable Cancelable]] that can
    *         be used to cancel a running task
    */
  def runAsync(f: Try[A] => Unit)(implicit s: Scheduler): Cancelable =
    runAsync(new Callback[A] {
      def onSuccess(value: A): Unit = f(Success(value))
      def onError(ex: Throwable): Unit = f(Failure(ex))
    })

  /** Triggers the asynchronous execution.
    *
    * @return a [[monix.async.CancelableFuture CancelableFuture]]
    *         that can be used to extract the result or to cancel
    *         a running task.
    */
  def runAsync(implicit s: Scheduler): CancelableFuture[A] = {
    val p = Promise[A]()
    val cancelable = runAsync(new Callback[A] {
      def onSuccess(value: A): Unit = p.trySuccess(value)
      def onError(ex: Throwable): Unit = p.tryFailure(ex)
    })

    CancelableFuture(p.future, cancelable)
  }

  /** Creates a new Task by applying a function to the successful result
    * of the source Task, and returns a task equivalent to the result
    * of the function.
    */
  def flatMap[B](f: A => Task[B]): Task[B] =
    self match {
      case Now(a) =>
        Suspend(() => try f(a) catch { case NonFatal(ex) => Error(ex) })
      case EvalOnce(result) =>
        Suspend(() => result match {
          case Success(a) => try f(a) catch { case NonFatal(ex) => Error(ex) }
          case Failure(ex) => Error(ex)
        })
      case EvalAlways(thunk) =>
        Suspend(() => try f(thunk()) catch {
          case NonFatal(ex) => Error(ex)
        })
      case Suspend(thunk) =>
        BindSuspend(thunk, f)
      case task @ MemoizeSuspend(_) =>
        BindSuspend(() => task, f)
      case BindSuspend(thunk, g) =>
        Suspend(() => BindSuspend(thunk, g andThen (_ flatMap f)))
      case Async(onFinish) =>
        BindAsync(onFinish, f)
      case BindAsync(listen, g) =>
        Suspend(() => BindAsync(listen, g andThen (_ flatMap f)))
      case error @ Error(_) =>
        error
    }

  /** Given a source Task that emits another Task, this function
    * flattens the result, returning a Task equivalent to the emitted
    * Task by the source.
    */
  def flatten[B](implicit ev: A <:< Task[B]): Task[B] =
    flatMap(a => a)

  /** Returns a task that waits for the specified `timespan` before
    * executing and mirroring the result of the source.
    */
  def delayExecution(timespan: FiniteDuration): Task[A] =
    Async { (scheduler, conn, cb) =>
      val c = SingleAssignmentCancelable()
      conn push c

      c := scheduler.scheduleOnce(timespan.length, timespan.unit, new Runnable {
        def run(): Unit = {
          conn.pop()
          Task.startAsync[A](scheduler, conn, self, cb)
        }
      })
    }

  /** Returns a task that waits for the specified `trigger` to succeed
    * before mirroring the result of the source.
    *
    * If the `trigger` ends in error, then the resulting task will also
    * end in error.
    */
  def delayExecutionWith(trigger: Task[Any]): Task[A] =
    Async { (scheduler, conn, cb) =>
      implicit val s = scheduler
      // N.B. fork ensures that the trigger is asynchronous
      Task.startAsync(scheduler, conn, trigger, new Callback[Any] {
        def onSuccess(value: Any): Unit =
          Task.startAsync(scheduler, conn, self, cb)
        def onError(ex: Throwable): Unit =
          cb.onError(ex)
      })
    }

  /** Returns a task that executes the source immediately on `runAsync`,
    * but before emitting the `onSuccess` result for the specified
    * duration.
    *
    * Note that if an error happens, then it is streamed immediately
    * with no delay.
    */
  def delayResult(timespan: FiniteDuration): Task[A] =
    Async { (scheduler, conn, cb) =>
      implicit val s = scheduler
      // Executing source
      Task.startAsync(scheduler, conn, self, new Callback[A] {
        def onSuccess(value: A): Unit = {
          val task = SingleAssignmentCancelable()
          conn push task

          // Delaying result
          task := scheduler.scheduleOnce(timespan.length, timespan.unit,
            new Runnable {
              def run(): Unit = {
                conn.pop()
                cb.onSuccess(value)
              }
            })
        }

        def onError(ex: Throwable): Unit =
          cb.onError(ex)
      })
    }

  /** Returns a task that executes the source immediately on `runAsync`,
    * but before emitting the `onSuccess` result for the specified
    * duration.
    *
    * Note that if an error happens, then it is streamed immediately
    * with no delay.
    */
  def delayResultBySelector[B](selector: A => Task[B]): Task[A] =
    Async { (scheduler, conn, cb) =>
      implicit val s = scheduler
      // Executing source
      Task.startAsync(scheduler, conn, self, new Callback[A] {
        def onSuccess(value: A): Unit = {
          var streamErrors = true
          try {
            val trigger = selector(value)
            streamErrors = false
            // Delaying result
            Task.startAsync(scheduler, conn, trigger, new Callback[B] {
              def onSuccess(b: B): Unit = cb.onSuccess(value)
              def onError(ex: Throwable): Unit = cb.onError(ex)
            })
          } catch {
            case NonFatal(ex) if streamErrors =>
              cb.onError(ex)
          }
        }

        def onError(ex: Throwable): Unit =
          cb.onError(ex)
      })
    }

  /** Returns a failed projection of this task.
    *
    * The failed projection is a future holding a value of type
    * `Throwable`, emitting a value which is the throwable of the
    * original task in case the original task fails, otherwise if the
    * source succeeds, then it fails with a `NoSuchElementException`.
    */
  def failed: Task[Throwable] =
    materialize.flatMap {
      case Error(ex) => Now(ex)
      case Now(_) => Error(new NoSuchElementException("failed"))
    }

  /** Returns a new Task that applies the mapping function to
    * the element emitted by the source.
    */
  def map[B](f: A => B): Task[B] =
    flatMap(a => try Now(f(a)) catch { case NonFatal(ex) => Error(ex) })

  /** Creates a new task that will handle any matching throwable that
    * this task might emit by executing another task.
    *
    * See [[onErrorRecoverWith]] for the version that takes a partial function.
    */
  def materialize: Task[Materialized[A]] = {
    self match {
      case now @ Now(_) =>
        Now(now)
      case EvalOnce(result) =>
        Suspend(() => Now(Materialized.fromTry(result)))
      case EvalAlways(thunk) =>
        Suspend(() => Now(Materialized(thunk())))
      case Error(ex) =>
        Now(Error(ex))
      case Suspend(thunk) =>
        Suspend(() => thunk().materialize)
      case task @ MemoizeSuspend(_) =>
        Suspend(() => task.materialize)
      case BindSuspend(thunk, g) =>
        BindSuspend[Materialized[Any], Materialized[A]](
          () => try thunk().materialize catch { case NonFatal(ex) => Now(Error(ex)) },
          result => result match {
            case Now(any) =>
              try { g.asInstanceOf[Any => Task[A]](any).materialize }
              catch { case NonFatal(ex) => Now(Error(ex)) }
            case Error(ex) =>
              Now(Error(ex))
          })
      case Async(onFinish) =>
        Async((s, conn, cb) => onFinish(s, conn, new Callback[A] {
          def onSuccess(value: A): Unit = cb.onSuccess(Now(value))
          def onError(ex: Throwable): Unit = cb.onSuccess(Error(ex))
        }))
      case BindAsync(onFinish, g) =>
        BindAsync[Materialized[Any], Materialized[A]](
          (s, conn, cb) => onFinish(s, conn, new Callback[Any] {
            def onSuccess(value: Any): Unit = cb.onSuccess(Now(value))
            def onError(ex: Throwable): Unit = cb.onSuccess(Error(ex))
          }),
          result => result match {
            case Now(any) =>
              try { g.asInstanceOf[Any => Task[A]](any).materialize }
              catch { case NonFatal(ex) => Now(Error(ex)) }
            case Error(ex) =>
              Now(Error(ex))
          })
    }
  }

  /** Dematerializes the source's result from a `Try`. */
  def dematerialize[B](implicit ev: A <:< Materialized[B]): Task[B] =
    self.asInstanceOf[Task[Materialized[B]]].flatMap(identity)

  /** Creates a new task that will try recovering from an error by
    * matching it with another task using the given partial function.
    *
    * See [[onErrorHandleWith]] for the version that takes a total function.
    */
  def onErrorRecoverWith[B >: A](pf: PartialFunction[Throwable, Task[B]]): Task[B] =
    onErrorHandleWith(ex => pf.applyOrElse(ex, Task.error))

  /** Creates a new task that will handle any matching throwable that
    * this task might emit by executing another task.
    *
    * See [[onErrorRecoverWith]] for the version that takes a partial function.
    */
  def onErrorHandleWith[B >: A](f: Throwable => Task[B]): Task[B] =
    self.materialize.flatMap {
      case now @ Now(_) => now
      case Error(ex) => try f(ex) catch { case NonFatal(err) => Error(err) }
    }

  /** Creates a new task that in case of error will fallback to the
    * given backup task.
    */
  def onErrorFallbackTo[B >: A](that: Task[B]): Task[B] =
    onErrorHandleWith(ex => that)

  /** Creates a new task that in case of error will retry executing the
    * source again and again, until it succeeds.
    *
    * In case of continuous failure the total number of executions
    * will be `maxRetries + 1`.
    */
  def onErrorRetry(maxRetries: Long): Task[A] =
    self.onErrorHandleWith(ex =>
      if (maxRetries > 0) self.onErrorRetry(maxRetries-1)
      else Error(ex))

  /** Creates a new task that in case of error will retry executing the
    * source again and again, until it succeeds.
    *
    * In case of continuous failure the total number of executions
    * will be `maxRetries + 1`.
    */
  def onErrorRetryIf(p: Throwable => Boolean): Task[A] =
    self.onErrorHandleWith(ex => if (p(ex)) self.onErrorRetryIf(p) else Error(ex))

  /** Creates a new task that will handle any matching throwable that
    * this task might emit.
    *
    * See [[onErrorRecover]] for the version that takes a partial function.
    */
  def onErrorHandle[U >: A](f: Throwable => U): Task[U] =
    onErrorHandleWith(ex => try Now(f(ex)) catch { case NonFatal(err) => Error(err) })

  /** Creates a new task that on error will try to map the error
    * to another value using the provided partial function.
    *
    * See [[onErrorHandle]] for the version that takes a total function.
    */
  def onErrorRecover[U >: A](pf: PartialFunction[Throwable, U]): Task[U] =
    onErrorRecoverWith(pf.andThen(Task.now))

  /** Memoizes the result on the computation and reuses it on subsequent
    * invocations of `runAsync`.
    */
  def memoize: Task[A] =
    self match {
      case ref @ Now(_) => ref
      case error @ Error(_) => error
      case EvalAlways(thunk) => new EvalOnce[A](thunk)
      case Suspend(thunk) => Suspend(() => thunk().memoize)
      case eval: EvalOnce[_] => self
      case memoized: MemoizeSuspend[_] => self
      case other => new MemoizeSuspend[A](() => other)
    }
}

object Task {
  /** Returns a new task that, when executed, will emit the result of
    * the given function executed asynchronously.
    */
  def apply[A](f: => A): Task[A] =
    fork(evalAlways(f))

  /** Returns a `Task` that on execution is always successful, emitting
    * the given strict value.
    */
  def now[A](a: A): Task[A] = Now(a)

  /** Returns a task that on execution is always finishing in error
    * emitting the specified exception.
    */
  def error[A](ex: Throwable): Task[A] =
    Error(ex)

  /** Promote a non-strict value representing a Task to a Task of the
    * same type.
    */
  def defer[A](task: => Task[A]): Task[A] =
    Suspend(() => task)

  /** Promote a non-strict value to a Task that is memoized on the first
    * evaluation, the result being then available on subsequent evaluations.
    */
  def evalOnce[A](f: => A): Task[A] =
    EvalOnce(f)

  /** Promote a non-strict value to a Task, catching exceptions in the
    * process.
    *
    * Note that since `Task` is not memoized, this will recompute the
    * value each time the `Task` is executed.
    */
  def evalAlways[A](f: => A): Task[A] =
    EvalAlways(f _)

  /** A [[Task]] instance that upon evaluation will never complete. */
  def never[A]: Task[A] =
    Async((_,_,_) => ())

  /** A `Task[Unit]` provided for convenience. */
  val unit: Task[Unit] = Now(())

  /** Mirrors the given source `Task`, but upon execution ensure
    * that evaluation forks into a separate (logical) thread.
    */
  def fork[A](fa: Task[A]): Task[A] =
    fa match {
      case async @ Async(_) => async
      case async @ BindAsync(_,_) => async
      case Suspend(thunk) =>
        Suspend(() => fork(thunk()))

      case memoize: MemoizeSuspend[_] =>
        if (memoize.isStarted)
          Async { (s, conn, cb) => Task.startAsync(s, conn, memoize, cb) }
        else
          memoize

      case other =>
        Async { (s, conn, cb) => Task.startAsync(s, conn, other, cb) }
    }

  /** Create a `Task` from an asynchronous computation, which takes the
    * form of a function with which we can register a callback.
    *
    * This can be used to translate from a callback-based API to a
    * straightforward monadic version. Note that execution of
    * the `register` callback always happens asynchronously.
    *
    * @param register is a function that will be called when this `Task`
    *        is executed, receiving a callback as a parameter, a
    *        callback that the user is supposed to call in order to
    *        signal the desired outcome of this `Task`.
    */
  def async[A](register: (Scheduler, Callback[A]) => Cancelable): Task[A] =
    Async { (scheduler, conn, cb) =>
      // Forced asynchronous execution.
      scheduler.execute(new Runnable {
        def run(): Unit = try {
          val c = SingleAssignmentCancelable()
          conn push c

          c := register(scheduler, new Callback[A] {
            def onError(ex: Throwable): Unit = {
              conn.pop()
              cb.onError(ex)
            }

            def onSuccess(value: A): Unit = {
              conn.pop()
              cb.onSuccess(value)
            }
          })
        } catch {
          case NonFatal(ex) =>
            conn.pop()
            scheduler.reportFailure(ex)
        }
      })
    }

  /** Constructs a lazy [[Task]] instance whose result
    * will be computed asynchronously.
    *
    * Unsafe to build directly, only use if you know what you're doing.
    * For building `Task` instances safely see [[async]].
    */
  def unsafeAsync[A](onFinish: OnFinish[A]): Task[A] =
    Async(onFinish)

  /** Converts the given Scala `Future` into a `Task`.
    *
    * NOTE: if you want to defer the creation of the future, use
    * in combination with [[defer]].
    */
  def fromFuture[A](f: Future[A]): Task[A] =
    Async { (s, conn, cb) =>
      f.onComplete {
        case Success(a) =>
          if (!conn.isCanceled) cb.onSuccess(a)
        case Failure(ex) =>
          if (!conn.isCanceled) cb.onError(ex)
      }(s)
    }

  /** Type alias representing callbacks for [[async]] tasks. */
  type OnFinish[+A] = (Scheduler, StackedCancelable, Callback[A]) => Unit

  /** A `Sync` task represents tasks that can be evaluated synchronously,
    * without forking logical threads.
    *
    * Can only be of 4 types:
    *
    *   1. [[Now]], for successful values, the equivalent of `scala.util.Success`
    *   2. [[Error]] for failures, the equivalent of `scala.util.Failure`
    *   3. [[EvalOnce]] (the result of the [[evalOnce]] builder)
    *   4. [[EvalAlways]] (the result of the [[evalAlways]] builder)
    */
  sealed abstract class Sync[+A] extends Task[A] {

  }

  /** A `Materialized` task represents a strict value, or the result of
    * a computation that has already been evaluated.
    *
    * Can only be of 2 types:
    *
    *   1. [[Now]], for successful values, the equivalent of `scala.util.Success`
    *   2. [[Error]] for failures, the equivalent of `scala.util.Failure`
    */
  sealed abstract class Materialized[+A] extends Sync[A] {
    /** Returns true if value is a successful one. */
    def isNow: Boolean = this match { case Now(_) => true; case _ => false }

    /** Returns true if result is an error. */
    def isError: Boolean = this match { case Error(_) => true; case _ => false }

    /** Converts this attempt into a `scala.util.Try`. */
    def asScala: Try[A] =
      this match {
        case Now(a) => Success(a)
        case Error(ex) => Failure(ex)
      }
  }

  object Materialized {
    /** Evaluates the non-strict argument. */
    def apply[A](f: => A): Materialized[A] =
      try Now(f) catch { case NonFatal(ex) => Error(ex) }

    /** Builds a [[Materialized]] instanced from a `Try`. */
    def fromTry[A](value: Try[A]): Materialized[A] =
      value match {
        case Success(a) => Now(a)
        case Failure(ex) => Error(ex)
      }
  }

  /** Constructs an eager [[Task]] instance whose result is already known.
    *
    * `Now` is a [[Materialized]] task state that represents a strict
    * successful value.
    */
  final case class Now[+A](a: A) extends Materialized[A] {
    // Overriding runAsync for efficiency reasons
    override def runAsync(cb: Callback[A])(implicit s: Scheduler): Cancelable = {
      try cb.onSuccess(a) catch { case NonFatal(ex) => s.reportFailure(ex) }
      Cancelable.empty
    }
  }

  /** Constructs an eager [[Task]] instance for a result that represents
    * an error.
    *
    * `Error` is a [[Materialized]] task state that represents a
    * computation that terminated in error.
    */
  final case class Error(ex: Throwable) extends Materialized[Nothing] {
    // Overriding runAsync for efficiency reasons
    override def runAsync(cb: Callback[Nothing])(implicit s: Scheduler): Cancelable = {
      try cb.onError(ex) catch { case NonFatal(err) => s.reportFailure(err) }
      Cancelable.empty
    }
  }

  /** Constructs a lazy [[Task]] instance that gets evaluated
    * only once.
    *
    * In some sense it is equivalent to using a lazy val.
    * When caching is not required or desired,
    * prefer [[EvalAlways]] or [[Now]].
    */
  final class EvalOnce[+A](f: () => A) extends Sync[A] {
    private[this] var thunk: () => A = f
    lazy val value: Try[A] = {
      val result = Try(thunk())
      thunk = null
      result
    }

    // Overriding runAsync for efficiency reasons
    override def runAsync(cb: Callback[A])(implicit s: Scheduler): Cancelable = {
      try cb(value) catch { case NonFatal(ex) => s.reportFailure(ex) }
      Cancelable.empty
    }

    override def equals(other: Any): Boolean = other match {
      case that: EvalOnce[_] => value == that.value
      case _ => false
    }

    override def hashCode(): Int =
      value.hashCode()
  }

  object EvalOnce {
    /** Builder for an [[EvalOnce]] instance. */
    def apply[A](a: => A): EvalOnce[A] =
      new EvalOnce[A](a _)

    /** Deconstructs an [[EvalOnce]] instance. */
    def unapply[A](task: Task[A]): Option[Try[A]] =
      task match {
        case ref: EvalOnce[_] => Some(ref.asInstanceOf[EvalOnce[A]].value)
        case _ => None
      }
  }

  /** Constructs a lazy [[Task]] instance.
    *
    * This type can be used for "lazy" values. In some sense it is
    * equivalent to using a Function0 value.
    */
  final case class EvalAlways[+A](f: () => A) extends Sync[A] {
    // Overriding runAsync for efficiency reasons
    override def runAsync(cb: Callback[A])(implicit s: Scheduler): Cancelable = {
      var streamErrors = true
      try {
        val result = f()
        streamErrors = false
        cb.onSuccess(result)
      } catch {
        case NonFatal(ex) if streamErrors =>
          cb.onError(ex)
      }
      Cancelable.empty
    }
  }

  /** Constructs a lazy [[Task]] instance whose result will
    * be computed asynchronously.
    *
    * Unsafe to build directly, only use if you know what you're doing.
    * For building `Async` instances safely, see [[async]].
    */
  private final case class Async[+A](onFinish: OnFinish[A]) extends Task[A]

  /** Defers the evaluation of a [[Task]].
    *
    * @see [[defer]]
    */
  final case class Suspend[+A](thunk: () => Task[A]) extends Task[A]

  /** Internal [[Task]] state that is the result of applying `flatMap`. */
  private final case class BindSuspend[A,B](thunk: () => Task[A], f: A => Task[B]) extends Task[B]

  /** Internal [[Task]] state that is the result of applying `flatMap`
    * over an [[Async]] value.
    */
  private final case class BindAsync[A,B](onFinish: OnFinish[A], f: A => Task[B]) extends Task[B]

  /** Internal [[Task]] state that defers the evaluation of the
    * given [[Task]] and upon execution memoize its result to
    * be available for later evaluations.
    */
  private final class MemoizeSuspend[A](f: () => Task[A]) extends Task[A] {
    private[this] var thunk: () => Task[A] = f
    private[this] val state = Atomic(null : AnyRef)

    def isStarted: Boolean =
      state.get != null

    def value: Option[Materialized[A]] =
      state.get match {
        case null => None
        case (p: Promise[_], _) =>
          p.asInstanceOf[Promise[A]].future.value match {
            case None => None
            case Some(value) => Some(Materialized.fromTry(value))
          }
        case result: Try[_] =>
          Some(Materialized.fromTry(result.asInstanceOf[Try[A]]))
      }

    override def runAsync(implicit s: Scheduler): CancelableFuture[A] =
      state.get match {
        case null => super.runAsync(s)
        case (p: Promise[_], c: StackedCancelable) =>
          val f = p.asInstanceOf[Promise[A]].future
          CancelableFuture(f, c)
        case result: Try[_] =>
          CancelableFuture.fromTry(result.asInstanceOf[Try[A]])
      }


    private def memoizeValue(value: Try[A]): Unit = {
      state.getAndSet(value) match {
        case (p: Promise[_], _) =>
          p.asInstanceOf[Promise[A]].complete(value)
        case _ =>
          () // do nothing
      }

      // GC purposes
      thunk = null
    }

    def runnable(scheduler: Scheduler, active: StackedCancelable, cb: Callback[A], binds: List[Bind]): Runnable =
      new Runnable {
        @tailrec def run(): Unit = {
          implicit val s = scheduler

          state.get match {
            case null =>
              val p = Promise[A]()

              if (state.compareAndSet(null, (p, active))) {
                val underlying = try thunk() catch { case NonFatal(ex) => Error(ex) }
                val callback = new Callback[A] {
                  def onError(ex: Throwable): Unit = {
                    try cb.onError(ex) finally
                      memoizeValue(Failure(ex))
                  }

                  def onSuccess(value: A): Unit = {
                    try cb.onSuccess(value) finally
                      memoizeValue(Success(value))
                  }
                }

                Task.resume(scheduler, active, underlying, callback, binds)
              }
              else {
                run() // retry
              }

            case (p: Promise[_], cancelable: StackedCancelable) =>
              // execution is pending completion
              active push cancelable
              p.asInstanceOf[Promise[A]].future.onComplete { r =>
                active.pop()
                if (r.isSuccess) cb.onSuccess(r.get)
                else cb.onError(r.failed.get)
              }

            case result: Try[_] =>
              val r = result.asInstanceOf[Try[A]]
              if (r.isSuccess) cb.onSuccess(r.get)
              else cb.onError(r.failed.get)
          }
        }
      }
  }

  object MemoizeSuspend {
    /** Extracts the memoized value, if available. */
    def unapply[A](source: Task.MemoizeSuspend[A]): Option[Option[Materialized[A]]] =
      Some(source.value)
  }

  private type Current = Task[Any]
  private type Bind = Any => Task[Any]

  /** Internal utility, starts the run-loop. */
  private def startAsync[A](scheduler: Scheduler, conn: StackedCancelable, source: Task[A], cb: Callback[A]): Unit =
    scheduler.execute(new Runnable {
      def run(): Unit = resume(scheduler, conn, source, cb, Nil)
    })

  /** Internal utility, resumes evaluation of the run-loop
    * from where it left off.
    */
  private def resume[A](
    scheduler: Scheduler, conn: StackedCancelable,
    source: Task[A], cb: Callback[A], binds: List[Bind]): Unit = {

    @tailrec  def reduceTask(
      scheduler: Scheduler,
      conn: StackedCancelable,
      source: Current,
      cb: Callback[Any],
      binds: List[Bind]): Runnable = {

      source match {
        case Now(a) =>
          binds match {
            case Nil =>
              cb.onSuccess(a)
              null // we are done
            case f :: rest =>
              val fa = try f(a) catch { case NonFatal(ex) => Error(ex) }
              reduceTask(scheduler, conn, fa, cb, rest)
          }

        case EvalOnce(result) =>
          result match {
            case Success(a) =>
              binds match {
                case Nil =>
                  cb.onSuccess(a)
                  null // we are done
                case f :: rest =>
                  val fa = try f(a) catch { case NonFatal(ex) => Error(ex) }
                  reduceTask(scheduler, conn, fa, cb, rest)
              }
            case Failure(ex) =>
              Error(ex)
              null // we are done
          }

        case EvalAlways(thunk) =>
          val fa = try Now(thunk()) catch { case NonFatal(ex) => Error(ex) }
          reduceTask(scheduler, conn, fa, cb, binds)

        case Error(ex) =>
          cb.onError(ex)
          null // we are done

        case Suspend(thunk) =>
          val fa = try thunk() catch { case NonFatal(ex) => Error(ex) }
          reduceTask(scheduler, conn, fa, cb, binds)

        case MemoizeSuspend(value) =>
          value match {
            case Some(materialized) => reduceTask(scheduler, conn, materialized, cb, binds)
            case None => source.asInstanceOf[MemoizeSuspend[Any]].runnable(scheduler, conn, cb, binds)
          }

        case BindSuspend(thunk, f) =>
          val fa = try thunk() catch { case NonFatal(ex) => Error(ex) }
          reduceTask(scheduler, conn, fa, cb, f.asInstanceOf[Bind] :: binds)

        case BindAsync(onFinish, f) =>
          new AsyncRunnable(scheduler, conn, cb, f.asInstanceOf[Bind] :: binds, onFinish)

        case Async(onFinish) =>
          new AsyncRunnable(scheduler, conn, cb, binds, onFinish)
      }
    }

    val r = reduceTask(scheduler, conn, source, cb.asInstanceOf[Callback[Any]], binds)
    if (r != null) scheduler.execute(r)
  }

  private final class AsyncRunnable(
    scheduler: Scheduler,
    conn: StackedCancelable,
    cb: Callback[Any],
    fs: List[Bind],
    onFinish: OnFinish[Any])
    extends Runnable {

    def run(): Unit =
      if (!conn.isCanceled) {
        onFinish(scheduler, conn, new Callback[Any] {
          def onSuccess(value: Any): Unit =
          // resuming loop
            resume(scheduler, conn, Now(value), cb, fs)
          def onError(ex: Throwable): Unit =
            cb.onError(ex)
        })
      }
  }}