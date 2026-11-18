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

package com.facebook.litho

import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.facebook.litho.LithoVisibilityEventsController.LithoVisibilityState
import com.facebook.litho.testing.testrunner.LithoTestRunner
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode

/**
 * Registration behaviour of [AOSPLithoVisibilityEventsController] when it is built off the main
 * thread, which is what [allowOffMainThreadRegistration] permits.
 */
@RunWith(LithoTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class AOSPLithoVisibilityEventsControllerOffMainThreadTest {

  private class TestOwner : LifecycleOwner {
    val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle
      get() = registry
  }

  private fun buildOffMainThread(owner: TestOwner): AOSPLithoVisibilityEventsController {
    val executor = Executors.newSingleThreadExecutor()
    try {
      return executor
          .submit<AOSPLithoVisibilityEventsController> {
            AOSPLithoVisibilityEventsController(owner, allowOffMainThreadRegistration = true)
          }
          .get(10, TimeUnit.SECONDS)
    } finally {
      executor.shutdown()
    }
  }

  private fun idleMain() {
    shadowOf(Looper.getMainLooper()).idle()
  }

  @Test
  fun `registration is deferred to the main thread and still observes`() {
    val owner = TestOwner()
    owner.registry.currentState = Lifecycle.State.RESUMED

    val controller = buildOffMainThread(owner)
    assertThat(owner.registry.observerCount).isEqualTo(0)

    idleMain()

    assertThat(owner.registry.observerCount).isEqualTo(1)
    owner.registry.currentState = Lifecycle.State.CREATED
    assertThat(controller.visibilityState).isEqualTo(LithoVisibilityState.HINT_INVISIBLE)
  }

  @Test
  fun `an owner destroyed before the deferred registration lands does not leak the observer`() {
    val owner = TestOwner()
    owner.registry.currentState = Lifecycle.State.RESUMED

    val controller = buildOffMainThread(owner)

    // The window: the owner tears down while registration is still queued.
    owner.registry.currentState = Lifecycle.State.DESTROYED
    idleMain()

    assertThat(owner.registry.observerCount).isEqualTo(0)
    assertThat(controller.visibilityState).isEqualTo(LithoVisibilityState.DESTROYED)
  }

  @Test
  fun `release before the deferred registration lands does not leak the observer`() {
    val owner = TestOwner()
    owner.registry.currentState = Lifecycle.State.RESUMED

    val controller = buildOffMainThread(owner)
    controller.release()
    idleMain()

    assertThat(owner.registry.observerCount).isEqualTo(0)
  }
}
