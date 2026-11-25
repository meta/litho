/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

package com.facebook.litho.widget.collection

import com.facebook.litho.testing.testrunner.LithoTestRunner
import java.util.concurrent.atomic.AtomicReference
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.LooperMode

@LooperMode(LooperMode.Mode.LEGACY)
@RunWith(LithoTestRunner::class)
class ChildGroupEventsTest {

  private var nearEndCalls = 0

  private fun nearEndRange(start: Int, end: Int, state: ChildGroupEventState) =
      listOf(ChildGroupRange(start, end, state, OnNearCallback { nearEndCalls++ }))

  @Test
  fun onNearEnd_firesOnce_whileTheGroupsEndStaysInView() {
    val state = ChildGroupEventState()

    nearEndRange(10, 20, state).dispatchViewportChanged(15, 19)
    nearEndRange(10, 20, state).dispatchViewportChanged(16, 21)

    assertThat(nearEndCalls).isEqualTo(1)
  }

  @Test
  fun onNearEnd_doesNotFire_whenTheViewportIsPastTheGroup() {
    nearEndRange(10, 20, ChildGroupEventState()).dispatchViewportChanged(30, 35)

    assertThat(nearEndCalls).isEqualTo(0)
  }

  @Test
  fun onNearEnd_neverFires_forAnEmptyGroup() {
    nearEndRange(5, 5, ChildGroupEventState()).dispatchViewportChanged(0, 10)

    assertThat(nearEndCalls).isEqualTo(0)
  }

  @Test
  fun onNearEnd_firesAgain_forAGroupAddedBackWithFreshState() {
    nearEndRange(10, 20, ChildGroupEventState()).dispatchViewportChanged(15, 19)
    nearEndRange(10, 20, ChildGroupEventState()).dispatchViewportChanged(15, 19)

    assertThat(nearEndCalls).isEqualTo(2)
  }

  @Test
  fun viewportChanges_areOnlyDispatchedOnTheMainThread() {
    val failure = AtomicReference<Throwable?>()
    val thread = Thread {
      try {
        nearEndRange(10, 20, ChildGroupEventState()).dispatchViewportChanged(15, 19)
      } catch (t: Throwable) {
        failure.set(t)
      }
    }
    thread.start()
    thread.join()

    assertThat(failure.get()).isInstanceOf(IllegalStateException::class.java)
    assertThat(nearEndCalls).isEqualTo(0)
  }

  @Test
  fun eventState_isOnlyAccessedOnTheMainThread() {
    val state = ChildGroupEventState()
    val failures = mutableListOf<Throwable>()
    val thread = Thread {
      runCatching { state.nearEndFiredAtCount }.exceptionOrNull()?.let { failures.add(it) }
      runCatching { state.nearEndFiredAtCount = 3 }.exceptionOrNull()?.let { failures.add(it) }
    }
    thread.start()
    thread.join()

    assertThat(failures).hasSize(2).allMatch { it is IllegalStateException }
    assertThat(state.nearEndFiredAtCount).isEqualTo(-1)
  }
}
