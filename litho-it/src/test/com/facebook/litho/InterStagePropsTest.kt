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

import com.facebook.litho.config.UnmountOnDetach
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.widget.MountSpecInterStagePropsTester
import com.facebook.litho.widget.SimpleStateUpdateEmulator
import com.facebook.litho.widget.SimpleStateUpdateEmulatorSpec
import com.facebook.rendercore.extensions.VisibilityChangeReason
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.LooperMode

@LooperMode(LooperMode.Mode.LEGACY)
@RunWith(LithoTestRunner::class)
class InterStagePropsTest {

  private lateinit var context: ComponentContext

  @JvmField @Rule val lithoViewRule = LithoTestRule()

  @Before
  fun setUp() {
    context = lithoViewRule.context
  }

  @Test
  fun interStageProp_FromPrepare_usedIn_OnBind() {
    val lifecycleTracker = LifecycleTracker()
    val stateUpdater = SimpleStateUpdateEmulatorSpec.Caller()
    val root = createComponent(lifecycleTracker, stateUpdater)
    val testLithoView = lithoViewRule.render { root }.attachToWindow().measure().layout()
    lifecycleTracker.reset()
    stateUpdater.increment()
    testLithoView.detachFromWindow()
    // We need to make sure layout happens because which triggers mount and bind together
    testLithoView.attachToWindow()
    val config = testLithoView.lithoView.configuration
    if (config != null && config.unmountOnDetach != UnmountOnDetach.Never) {
      testLithoView.lithoView.notifyVisibleBoundsChanged(VisibilityChangeReason.UNKNOWN)
      assertThat(lifecycleTracker.steps)
          .describedAs("On Bind should be called")
          .containsExactly(
              LifecycleStep.ON_UNBIND,
              LifecycleStep.ON_UNMOUNT,
              LifecycleStep.ON_MOUNT,
              LifecycleStep.ON_BIND,
          )
    } else {
      assertThat(lifecycleTracker.steps)
          .describedAs("On Bind should be called")
          .containsExactly(LifecycleStep.ON_UNBIND, LifecycleStep.ON_BIND)
    }
  }

  @Test
  fun interStageProp_FromBind_usedIn_OnUnbind() {
    val lifecycleTracker = LifecycleTracker()
    val stateUpdater = SimpleStateUpdateEmulatorSpec.Caller()
    val root = createComponent(lifecycleTracker, stateUpdater)
    val testLithoView = lithoViewRule.render { root }.attachToWindow().measure().layout()
    lifecycleTracker.reset()
    testLithoView.detachFromWindow()
    assertThat(lifecycleTracker.steps)
        .describedAs("On Unbind should be called")
        .contains(LifecycleStep.ON_UNBIND)
  }

  @Test
  fun interStageProp_FromMeasure_usedIn_OnMount() {
    val lifecycleTracker = LifecycleTracker()
    val stateUpdater = SimpleStateUpdateEmulatorSpec.Caller()
    val root = createComponent(lifecycleTracker, stateUpdater)
    lithoViewRule.render { root }.attachToWindow().measure().layout()
    assertThat(lifecycleTracker.steps)
        .describedAs("On Mount should be called")
        .contains(LifecycleStep.ON_MOUNT)
  }

  @Test
  fun interStageProp_FromBoundsDefined_usedIn_OnUnMount() {
    val lifecycleTracker = LifecycleTracker()
    val stateUpdater = SimpleStateUpdateEmulatorSpec.Caller()
    val root = createComponent(lifecycleTracker, stateUpdater)
    val testLithoView = lithoViewRule.render { root }.attachToWindow().measure().layout()
    lifecycleTracker.reset()
    testLithoView.lithoView.unmountAllItems()
    assertThat(lifecycleTracker.steps)
        .describedAs("On Unmount should be called")
        .contains(LifecycleStep.ON_UNMOUNT)
  }

  private fun createComponent(
      lifecycleTracker: LifecycleTracker,
      stateUpdater: SimpleStateUpdateEmulatorSpec.Caller,
  ): Component =
      Column.create(context)
          .child(MountSpecInterStagePropsTester.create(context).lifecycleTracker(lifecycleTracker))
          .child(SimpleStateUpdateEmulator.create(context).caller(stateUpdater))
          .build()
}
