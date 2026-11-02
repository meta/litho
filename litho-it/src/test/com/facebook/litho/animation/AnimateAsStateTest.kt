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
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.rendercore.px
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode

@OptIn(ExperimentalLithoApi::class)
@LooperMode(LooperMode.Mode.PAUSED)
@RunWith(LithoTestRunner::class)
class AnimateAsStateTest {

  @Rule @JvmField val lithoTestRule = LithoTestRule()

  @Test
  fun useAnimatedFloat_initialValue_isApplied() {
    val testView = lithoTestRule.render { AlphaComponent(target = 0.5f) }
    shadowOf(getMainLooper()).idle()

    assertThat(testView.lithoView.alpha).isEqualTo(0.5f)
  }

  @Test
  fun useAnimatedFloat_withSnap_updatesOnTargetChange() {
    val testView = lithoTestRule.render { AlphaComponent(target = 0f) }
    shadowOf(getMainLooper()).idle()
    assertThat(testView.lithoView.alpha).isEqualTo(0f)

    testView.setRoot(AlphaComponent(target = 1f))
    shadowOf(getMainLooper()).idle()

    assertThat(testView.lithoView.alpha).isEqualTo(1f)
  }

  @Test
  fun useAnimatedFloat_sameTarget_doesNotAnimate() {
    val testView = lithoTestRule.render { AlphaComponent(target = 0.7f) }
    shadowOf(getMainLooper()).idle()
    assertThat(testView.lithoView.alpha).isEqualTo(0.7f)

    // Setting the same target should not start a new animation
    testView.setRoot(AlphaComponent(target = 0.7f))
    shadowOf(getMainLooper()).idle()

    assertThat(testView.lithoView.alpha).isEqualTo(0.7f)
  }

  private class AlphaComponent(
      private val target: Float,
  ) : KComponent() {
    override fun ComponentScope.render(): Component? {
      val animatedAlpha = useAnimatedFloat(target, spec = snap())
      return Row(style = Style.width(100.px).height(100.px).alpha(animatedAlpha))
    }
  }
}
