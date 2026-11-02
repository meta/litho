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
 * A layout-phase state read has to survive a layout-cache hit.
 *
 * `Style.height(Value<Dimen>)` defers reading its value to the layout walk, so the read is recorded
 * on the node's layout output and republished into `LayoutState.stateReads` every pass. That is
 * what lets the next frame's tick invalidate the node. A pass that serves the node from the layout
 * cache must republish it too: a cache hit says the node's reads are unchanged, not that it has
 * none. Drop them once and the node can never be dirtied for that value again — so it can never
 * re-record them, and the animation stops where it stood.
 */
@OptIn(ExperimentalLithoApi::class)
@LooperMode(LooperMode.Mode.PAUSED)
@RunWith(LithoTestRunner::class)
class LayoutPhaseStateReadCacheTest {

  @Rule @JvmField val lithoTestRule = LithoTestRule()

  @Rule @JvmField val animationRule = LithoAnimationTestRule { shadowOf(getMainLooper()).idle() }

  private var expanded: State<Boolean>? = null
  private var neighbor: State<Int>? = null

  @Test
  fun animatedDimen_commitFromSiblingMidAnimation_stillConverges() {
    val testView = lithoTestRule.render { HostComponent() }
    shadowOf(getMainLooper()).idle()
    testView.measure().layout()
    val growHeight = { checkNotNull(testView.findViewWithTagOrNull(GROW_TAG)).height }
    assertThat(growHeight()).isEqualTo(100)

    checkNotNull(expanded).updateSync { true }
    shadowOf(getMainLooper()).idle()
    testView.measure().layout()

    animationRule.advanceBy(72.ms)
    testView.measure().layout()
    val mid = growHeight()
    assertThat(mid).isGreaterThan(100)
    assertThat(mid).isLessThan(250)

    // The animating node's container is untouched by this update, so it is served from the layout
    // cache and writes no props — the pass on which its read would be dropped.
    checkNotNull(neighbor).updateSync { it + 1 }
    shadowOf(getMainLooper()).idle()
    testView.measure().layout()

    animationRule.advanceBy(400.ms)
    testView.measure().layout()
    assertThat(growHeight())
        .describedAs("later frames must still reach the node, so it settles on its target")
        .isEqualTo(250)
  }

  private inner class HostComponent : KComponent() {
    override fun ComponentScope.render(): Component =
        Column(style = Style.width(200.px)) {
          child(AnimatedHeightComponent())
          // Owns the state, so only this subtree is dirty when it updates.
          child(NeighborComponent())
        }
  }

  private inner class AnimatedHeightComponent : KComponent() {
    override fun ComponentScope.render(): Component {
      val expandedState = useState { false }.also { expanded = it }
      // render() hands the Value over without reading it; the read happens in copyLayoutProps.
      val animatedHeight = useAnimatedDimen(
          targetValue = if (expandedState.value) 250.px else 100.px,
          spec = tween(duration = 144.ms, easing = Easing.Linear),
      )
      return Column {
        child(
            Row(
                style =
                    Style.width(200.px)
                        .height(animatedHeight)
                        .backgroundColor(Color.RED)
                        .viewTag(GROW_TAG),
            ),
        )
      }
    }
  }

  private inner class NeighborComponent : KComponent() {
    override fun ComponentScope.render(): Component {
      val count = useState { 0 }.also { neighbor = it }
      return Row(style = Style.width(200.px).height(20.px).viewTag("neighbor-${count.value}"))
    }
  }

  companion object {
    private const val GROW_TAG = "animated_height"
  }
}
