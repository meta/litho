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

package com.facebook.litho.visibility

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.testing.TestLifecycleOwner
import com.facebook.litho.ComponentTree
import com.facebook.litho.LithoView
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.testrunner.LithoTestRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Tests for the LithoView default visibility events controller. */
@RunWith(LithoTestRunner::class)
class DefaultVisibilityEventsControllerTest {

  @Rule @JvmField val lithoTestRule = LithoTestRule()

  private var previousEnableDefaultVisibilityEventsController = false

  @Before
  fun setUp() {
    previousEnableDefaultVisibilityEventsController =
        ComponentsConfiguration.enableDefaultVisibilityEventsController
    ComponentsConfiguration.enableDefaultVisibilityEventsController = true
  }

  @After
  fun tearDown() {
    ComponentsConfiguration.enableDefaultVisibilityEventsController =
        previousEnableDefaultVisibilityEventsController
  }

  @Test
  fun attachDetachCycles_doNotLeakLifecycleObservers() {
    val owner = TestLifecycleOwner(Lifecycle.State.RESUMED)
    val lithoView = LithoView(lithoTestRule.context)
    lithoView.setViewTreeLifecycleOwner(owner)
    lithoView.componentTree = ComponentTree.create(lithoTestRule.context).build()
    val baseline = owner.lifecycle.observerCount

    lithoView.onAttachedToWindowForTest()
    assertThat(owner.lifecycle.observerCount).isEqualTo(baseline + 1)
    lithoView.onDetachedFromWindowForTest()
    assertThat(owner.lifecycle.observerCount).isEqualTo(baseline)

    repeat(10) {
      lithoView.onAttachedToWindowForTest()
      lithoView.onDetachedFromWindowForTest()
    }

    assertThat(owner.lifecycle.observerCount).isEqualTo(baseline)
  }

  @Test
  fun release_removesLifecycleObserver() {
    val owner = TestLifecycleOwner(Lifecycle.State.RESUMED)
    val lithoView = LithoView(lithoTestRule.context)
    lithoView.setViewTreeLifecycleOwner(owner)
    lithoView.componentTree = ComponentTree.create(lithoTestRule.context).build()
    val baseline = owner.lifecycle.observerCount

    lithoView.onAttachedToWindowForTest()
    @Suppress("DEPRECATION") lithoView.release()

    assertThat(owner.lifecycle.observerCount).isEqualTo(baseline)
  }
}
