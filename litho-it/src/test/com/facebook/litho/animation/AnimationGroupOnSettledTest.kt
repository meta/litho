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

package com.facebook.litho.animation

import android.os.Looper.getMainLooper
import com.facebook.litho.Component
import com.facebook.litho.ComponentScope
import com.facebook.litho.KComponent
import com.facebook.litho.Style
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.core.height
import com.facebook.litho.core.width
import com.facebook.litho.layout.flexbox.Row
import com.facebook.litho.testing.LithoAnimationTestRule
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.rendercore.ms
import com.facebook.rendercore.px
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode

/**
 * Tests that the group callback fires once after every property settles, never during the run, and
 * again for each subsequent target generation.
 */
@OptIn(ExperimentalLithoApi::class)
@LooperMode(LooperMode.Mode.PAUSED)
@RunWith(LithoTestRunner::class)
class AnimationGroupOnSettledTest {

  @Rule @JvmField val lithoTestRule = LithoTestRule()

  @Rule @JvmField val animationRule = LithoAnimationTestRule { shadowOf(getMainLooper()).idle() }

  private var settleCount = 0

  @Before
  fun setUp() {
    settleCount = 0
  }

  @Test
  fun onSettled_doesNotFireForInitialSettledState() {
    // First render starts settled (currentState == targetState), so there is nothing to settle.
    lithoTestRule.render { SettleComponent(expanded = false) }
    shadowOf(getMainLooper()).idle()

    assertThat(settleCount).isEqualTo(0)
  }

  @Test
  fun onSettled_firesExactlyOnceWhenTransitionCompletes() {
    val testView = lithoTestRule.render { SettleComponent(expanded = false) }
    shadowOf(getMainLooper()).idle()

    testView.setRoot(SettleComponent(expanded = true))

    // Mid-flight: the group has not settled, so the callback must not have fired.
    animationRule.advanceBy(500.ms)
    assertThat(settleCount).isEqualTo(0)
    assertThat(animationRule.hasScheduledFrames()).isTrue()

    // Completes at 1000ms: callback fires exactly once.
    animationRule.advanceBy(500.ms)
    assertThat(animationRule.hasScheduledFrames()).isFalse()
    assertThat(settleCount).isEqualTo(1)

    // Extra frames after settle must not re-fire it.
    animationRule.advanceBy(500.ms)
    assertThat(settleCount).isEqualTo(1)
  }

  @Test
  fun onSettled_firesAgainOnSubsequentTransition() {
    val testView = lithoTestRule.render { SettleComponent(expanded = false) }
    shadowOf(getMainLooper()).idle()

    testView.setRoot(SettleComponent(expanded = true))
    animationRule.advanceBy(1_000.ms)
    assertThat(settleCount).isEqualTo(1)

    testView.setRoot(SettleComponent(expanded = false))
    animationRule.advanceBy(1_000.ms)
    assertThat(settleCount).isEqualTo(2)
  }

  @Test
  fun onSettled_firesOnceForMultiSlotGroupAfterAllSlotsSettle() {
    val testView = lithoTestRule.render { MultiSlotSettleComponent(expanded = false) }
    shadowOf(getMainLooper()).idle()

    testView.setRoot(MultiSlotSettleComponent(expanded = true))

    // The short (alpha, 1000ms) slot has finished but the long (rotation, 2000ms) slot has not, so
    // the group has not settled yet — no fire.
    animationRule.advanceBy(1_000.ms)
    assertThat(settleCount).isEqualTo(0)
    assertThat(animationRule.hasScheduledFrames()).isTrue()

    // Once the longest slot finishes, the group settles and the callback fires exactly once.
    animationRule.advanceBy(1_500.ms)
    assertThat(animationRule.hasScheduledFrames()).isFalse()
    assertThat(settleCount).isEqualTo(1)
  }

  // --- Test components ---

  private inner class SettleComponent(private val expanded: Boolean) : KComponent() {
    override fun ComponentScope.render(): Component {
      val group = useAnimationGroup(expanded, onSettled = { settleCount++ })
      val alpha =
          group.animateFloat(
              transitionSpec = { tween(duration = 1_000.ms, easing = Easing.Linear) },
          ) { state ->
            if (state) 1f else 0f
          }
      return Row(style = Style.width(100.px).height(100.px).alpha(alpha))
    }
  }

  private inner class MultiSlotSettleComponent(private val expanded: Boolean) : KComponent() {
    override fun ComponentScope.render(): Component {
      val group = useAnimationGroup(expanded, onSettled = { settleCount++ })
      val alpha =
          group.animateFloat(
              transitionSpec = { tween(duration = 1_000.ms, easing = Easing.Linear) },
          ) { state ->
            if (state) 1f else 0f
          }
      val rotation =
          group.animateFloat(
              transitionSpec = { tween(duration = 2_000.ms, easing = Easing.Linear) },
          ) { state ->
            if (state) 180f else 0f
          }
      return Row(style = Style.width(100.px).height(100.px).alpha(alpha).rotation(rotation))
    }
  }
}
