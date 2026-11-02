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

import android.graphics.Color
import android.os.Looper.getMainLooper
import com.facebook.litho.Component
import com.facebook.litho.ComponentScope
import com.facebook.litho.KComponent
import com.facebook.litho.State
import com.facebook.litho.Style
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.core.height
import com.facebook.litho.core.width
import com.facebook.litho.key
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.layout.flexbox.Row
import com.facebook.litho.testing.LithoAnimationTestRule
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.useState
import com.facebook.litho.view.backgroundColor
import com.facebook.litho.view.viewTag
import com.facebook.rendercore.ms
import com.facebook.rendercore.px
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode

/**
 * Regression coverage for the reflow "previous size" source. A reflow node's shrink must pin to its
 * own grown size even when a sibling is structurally removed in the same commit. The previous size
 * is keyed by `transitionGlobalKey` (via the reflow snapshot), so it survives the sibling shift —
 * unlike the position-aligned `DiffNode`, which would hand this node its former neighbor's size.
 */
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(ExperimentalLithoApi::class)
@RunWith(LithoTestRunner::class)
class StyleReflowStructuralTest {

  @JvmField @Rule val lithoTestRule = LithoTestRule()

  @JvmField @Rule val animationRule = LithoAnimationTestRule { shadowOf(getMainLooper()).idle() }

  private var expanded: State<Boolean>? = null

  @Test
  fun reflowShrink_withSiblingRemoved_pinsToGrownSize_thenAnimatesDown() {
    val testView = lithoTestRule.render { RoundTripComponent() }
    shadowOf(getMainLooper()).idle()
    testView.measure().layout()
    fun growHeight(): Int = checkNotNull(testView.findViewWithTagOrNull(GROW_TAG)).height

    assertThat(growHeight()).isEqualTo(100)

    // Expand: the banner appears (structural insert) and the reflow node grows 100 -> 250. Commit
    // (measure/layout runs the mount that retargets the reflow value), then advance to settle.
    checkNotNull(expanded).updateSync { true }
    shadowOf(getMainLooper()).idle()
    testView.measure().layout()
    animationRule.advanceBy(300.ms)
    testView.measure().layout()
    assertThat(growHeight()).isEqualTo(250)

    // Collapse: the banner is removed (structural) and the reflow node shrinks 250 -> 100. The
    // commit must pin the node to its own grown 250 — not the removed banner's height — so the
    // shrink animates down from 250. Observe the pinned commit frame before advancing the clock.
    checkNotNull(expanded).updateSync { false }
    shadowOf(getMainLooper()).idle()
    testView.measure().layout()
    assertThat(growHeight()).isEqualTo(250)

    // Halfway through the 144ms shrink it is mid-flight, strictly between grown and collapsed.
    animationRule.advanceBy(72.ms)
    testView.measure().layout()
    val mid = growHeight()
    assertThat(mid).isLessThan(250)
    assertThat(mid).isGreaterThan(100)

    // And it converges.
    animationRule.advanceBy(200.ms)
    testView.measure().layout()
    assertThat(growHeight()).isEqualTo(100)
  }

  private inner class RoundTripComponent : KComponent() {
    override fun ComponentScope.render(): Component {
      val exp = useState { false }.also { expanded = it }
      return Column(style = Style.width(100.px)) {
        if (exp.value) {
          child(
              key("banner") {
                Row(style = Style.width(100.px).height(80.px).backgroundColor(Color.YELLOW))
              },
          )
        }
        child(
            key("grow") {
              Row(
                  style =
                      Style.width(100.px)
                          .height(if (exp.value) 250.px else 100.px)
                          .backgroundColor(Color.RED)
                          .transition {
                            bounds(tween(duration = 144.ms, easing = Easing.Linear), reflow = true)
                          }
                          .viewTag(GROW_TAG),
              )
            },
        )
      }
    }
  }

  companion object {
    private const val GROW_TAG = "reflow_structural_grow"
  }
}
