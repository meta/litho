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

package com.facebook.litho.widget

import android.view.View
import com.facebook.litho.LithoPrimitive
import com.facebook.litho.PrimitiveComponent
import com.facebook.litho.PrimitiveComponentScope
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.LithoTestRuleResizeMode
import com.facebook.litho.widget.collection.LazyList
import com.facebook.rendercore.primitives.FixedSizeLayoutBehavior
import com.facebook.rendercore.primitives.ViewAllocator
import com.facebook.rendercore.px
import com.facebook.rendercore.utils.MeasureSpecUtils.atMost
import com.facebook.rendercore.utils.MeasureSpecUtils.exactly
import org.assertj.core.api.Assertions.assertThat
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** Tests to verify that we always prepare layouts in the same order we mount them. */
@LooperMode(LooperMode.Mode.LEGACY)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class RecyclerBinderPreparationOrderTest {

  @JvmField @Rule val lithoTestRule = LithoTestRule(resizeMode = LithoTestRuleResizeMode.MANUAL)

  @Test
  fun `default traversal order should match RecyclerView layout order for primitive`() {
    // This test is specific to RecyclerBinder behavior, skip when new collection is enabled
    assumeFalse(ComponentsConfiguration.defaultInstance.enableNewCollection)

    val prepareTracking = mutableListOf<Int>()
    val mountTracking = mutableListOf<Int>()

    val lithoView = lithoTestRule.createTestLithoView {
      LazyList {
        (1..10).forEach { tag ->
          child(
              id = tag,
              component =
                  PrepareTrackingPrimitiveComponent(
                      prepareTracking = prepareTracking,
                      mountTracking = mountTracking,
                      tag = tag,
                  ),
          )
        }
      }
    }

    lithoTestRule.act(lithoView) {
      lithoView.setSizeSpecs(exactly(1000), atMost(400))
      lithoView.measure()
    }

    assertThat(prepareTracking).hasSize(10)
    assertThat(mountTracking).isEmpty()

    lithoView.layout()

    // Assert that what was mounted was also the first set of rows prepared
    assertThat(mountTracking).isEqualTo(prepareTracking.subList(0, mountTracking.size))
  }

  @Test
  fun `reverseLayout traversal order should match RecyclerView layout order for primitive`() {
    // This test is specific to RecyclerBinder behavior, skip when new collection is enabled
    assumeFalse(ComponentsConfiguration.defaultInstance.enableNewCollection)

    val prepareTracking = mutableListOf<Int>()
    val mountTracking = mutableListOf<Int>()

    val lithoView = lithoTestRule.createTestLithoView {
      LazyList(reverse = true) {
        (1..10).forEach { tag ->
          child(
              id = tag,
              component =
                  PrepareTrackingPrimitiveComponent(
                      prepareTracking = prepareTracking,
                      mountTracking = mountTracking,
                      tag = tag,
                  ),
          )
        }
      }
    }

    lithoTestRule.act(lithoView) {
      lithoView.setSizeSpecs(exactly(1000), atMost(400))
      lithoView.measure()
    }

    assertThat(prepareTracking).hasSize(10)
    assertThat(mountTracking).isEmpty()

    lithoView.layout()

    // Assert that what was mounted was also the first set of rows prepared
    assertThat(mountTracking).isEqualTo(prepareTracking.subList(0, mountTracking.size))
  }
}

private class PrepareTrackingPrimitiveComponent(
    val prepareTracking: MutableList<Int>,
    val mountTracking: MutableList<Int>,
    val tag: Int,
) : PrimitiveComponent() {
  override fun PrimitiveComponentScope.render(): LithoPrimitive {
    prepareTracking.add(tag)
    return LithoPrimitive(
        layoutBehavior = FixedSizeLayoutBehavior(100.px, 100.px),
        mountBehavior =
            MountBehavior(ViewAllocator { context -> View(context) }) {
              bind(mountTracking, tag) {
                mountTracking.add(tag)
                onUnbind {}
              }
            },
        style = null,
    )
  }
}
