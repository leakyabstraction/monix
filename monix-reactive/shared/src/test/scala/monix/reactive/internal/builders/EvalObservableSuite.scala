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

package monix.reactive.internal.builders

import monix.eval.Coeval
import monix.reactive.exceptions.DummyException
import monix.reactive.{BaseLawsTestSuite, Observable}

object EvalObservableSuite extends BaseLawsTestSuite {
  test("eval(now(value)) should work") { implicit s =>
    check1 { (value: Int) =>
      val obs1 = Observable.eval(Coeval.now(value))
      val obs2 = Observable.now(value)
      obs1 === obs2
    }
  }

  test("eval(evalAlways(value)) should work") { implicit s =>
    check1 { (value: Int) =>
      val obs1 = Observable.eval(Coeval.evalAlways(value))
      val obs2 = Observable.evalAlways(value)
      obs1 === obs2
    }
  }

  test("eval(evalOnce(value)) should work") { implicit s =>
    check1 { (value: Int) =>
      val obs1 = Observable.eval(Coeval.evalOnce(value))
      val obs2 = Observable.evalOnce(value)
      obs1 === obs2
    }
  }

  test("eval(raiseError(value)) should work") { implicit s =>
    check1 { (value: Int) =>
      val ex = DummyException(s"dummy $value")
      val obs1 = Observable.eval(Coeval.raiseError(ex))
      val obs2 = Observable.raiseError(ex)
      obs1 === obs2
    }
  }
}
