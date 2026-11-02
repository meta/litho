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
import com.facebook.litho.Value
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.core.height
import com.facebook.litho.core.width
import com.facebook.litho.layout.flexbox.Row
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.rendercore.px
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode

/**
 * Verifies that animation hooks derive a distinct read-tracking [com.facebook.litho.state.StateId]
 * per call site.
 *
 * Animated values build their StateId from `animationHookKey()`. Since `AnimatedValue`/`SlotValue`
 * equality is by StateId, two call sites must produce distinct IDs.
 */
@OptIn(ExperimentalLithoApi::class)
@LooperMode(LooperMode.Mode.PAUSED)
@RunWith(LithoTestRunner::class)
class AnimationHookIdentityTest {

  @Rule @JvmField val lithoTestRule = LithoTestRule()

  private var firstValue: Value<Float>? = null
  private var secondValue: Value<Float>? = null

  @Before
  fun setUp() {
    firstValue = null
    secondValue = null
  }

  @Test
  fun multipleUseAnimatedFloatInOneComponent_haveDistinctIdentity() {
    lithoTestRule.render { TwoAnimatedFloatsComponent() }
    shadowOf(getMainLooper()).idle()

    assertThat(firstValue).isNotNull
    assertThat(secondValue).isNotNull
    // Distinct StateIds -> not equal. A shared id (the pre-fix collision) would make them equal.
    assertThat(firstValue).isNotEqualTo(secondValue)
  }

  @Test
  fun multipleAnimationGroupsInOneComponent_haveDistinctSlotIdentity() {
    lithoTestRule.render { TwoGroupsComponent() }
    shadowOf(getMainLooper()).idle()

    assertThat(firstValue).isNotNull
    assertThat(secondValue).isNotNull
    assertThat(firstValue).isNotEqualTo(secondValue)
  }

  private inner class TwoAnimatedFloatsComponent : KComponent() {
    override fun ComponentScope.render(): Component {
      val a = useAnimatedFloat(1f)
      val b = useAnimatedFloat(2f)
      firstValue = a
      secondValue = b
      return Row(style = Style.width(100.px).height(100.px).alpha(a))
    }
  }

  private inner class TwoGroupsComponent : KComponent() {
    override fun ComponentScope.render(): Component {
      val groupA = useAnimationGroup(true, label = "a")
      val a = groupA.animateFloat { if (it) 1f else 0f }
      val groupB = useAnimationGroup(true, label = "b")
      val b = groupB.animateFloat { if (it) 1f else 0f }
      firstValue = a
      secondValue = b
      return Row(style = Style.width(100.px).height(100.px).alpha(a))
    }
  }
}
