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

import com.facebook.litho.Component
import com.facebook.litho.ComponentScope
import com.facebook.litho.KComponent
import com.facebook.litho.Style
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.core.height
import com.facebook.litho.core.width
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.layout.flexbox.Row
import com.facebook.litho.testing.LithoAnimationTestRule
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.view.viewTag
import com.facebook.rendercore.ms
import com.facebook.rendercore.px
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.LooperMode

/** Tests for the public [AnimatedVisibility] container-owned enter/exit API. */
@OptIn(ExperimentalLithoApi::class)
@LooperMode(LooperMode.Mode.PAUSED)
@RunWith(LithoTestRunner::class)
class AnimatedVisibilityTest {

  @Rule @JvmField val lithoTestRule = LithoTestRule()

  @Rule @JvmField val animationRule = LithoAnimationTestRule { lithoTestRule.idle() }

  private val linearFade = tween<Float>(duration = 1_000.ms, easing = Easing.Linear)

  @Test
  fun visibleContent_isMounted() {
    val testView = lithoTestRule.render { wrapped(visible = true) }
    lithoTestRule.idle()
    assertThat(testView.findComponent(Marker::class)).isNotNull()
  }

  @Test
  fun exitWithAnimation_retainsContentUntilSettleThenUnmounts() {
    val testView = lithoTestRule.render { wrapped(visible = true) }
    lithoTestRule.idle()
    assertThat(testView.findComponent(Marker::class)).isNotNull()

    testView.setRoot(wrapped(visible = false))

    animationRule.advanceBy(500.ms)
    assertThat(testView.findComponent(Marker::class)).isNotNull()

    animationRule.advanceBy(500.ms)
    lithoTestRule.idle()
    assertThat(testView.findComponent(Marker::class)).isNull()
  }

  @Test
  fun exitNone_unmountsImmediately() {
    val testView = lithoTestRule.render { wrapped(visible = true, exit = ExitTransition.None) }
    lithoTestRule.idle()
    assertThat(testView.findComponent(Marker::class)).isNotNull()

    testView.setRoot(wrapped(visible = false, exit = ExitTransition.None))
    lithoTestRule.idle()
    assertThat(testView.findComponent(Marker::class)).isNull()
  }

  @Test
  fun reverseMidExit_neverUnmounts() {
    val testView = lithoTestRule.render { wrapped(visible = true) }
    lithoTestRule.idle()

    testView.setRoot(wrapped(visible = false))
    animationRule.advanceBy(500.ms)
    assertThat(testView.findComponent(Marker::class)).isNotNull()

    testView.setRoot(wrapped(visible = true))
    animationRule.advanceBy(2_000.ms)
    lithoTestRule.idle()
    assertThat(testView.findComponent(Marker::class)).isNotNull()
  }

  @Test
  fun rapidToggle_doesNotLeakOrDoubleUnmount() {
    val testView = lithoTestRule.render { wrapped(visible = true) }
    lithoTestRule.idle()

    testView.setRoot(wrapped(visible = false))
    testView.setRoot(wrapped(visible = true))
    animationRule.advanceBy(2_000.ms)
    lithoTestRule.idle()
    assertThat(testView.findComponent(Marker::class)).isNotNull()

    testView.setRoot(wrapped(visible = false))
    animationRule.advanceBy(2_000.ms)
    lithoTestRule.idle()
    assertThat(testView.findComponent(Marker::class)).isNull()
  }

  @Test
  fun fadeOut_appliesDecreasingAlpha() {
    val testView = lithoTestRule.render { wrapped(visible = true) }
    lithoTestRule.idle()
    assertThat(testView.findViewWithTagOrNull(WRAPPER_TAG)?.alpha).isEqualTo(1f)

    testView.setRoot(wrapped(visible = false))
    animationRule.advanceBy(500.ms)

    // Mid fade-out: the wrapper's alpha is partway between opaque and transparent.
    val alpha = testView.findViewWithTagOrNull(WRAPPER_TAG)?.alpha
    assertThat(alpha).isNotNull()
    assertThat(alpha!!).isGreaterThan(0f).isLessThan(1f)
  }

  @Test
  fun `spring slide and size keep the final progress before publishing their endpoints`() {
    val settled = mutableListOf<Boolean>()
    fun container(visible: Boolean) = AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally { 1080 } + expandIn(),
        exit = slideOutHorizontally { 1080 } + shrinkOut(),
        style = Style.viewTag(WRAPPER_TAG),
        onSettled = { settled.add(it) },
    ) {
      Marker()
    }
    val testView = lithoTestRule.render { container(false) }
    testView.setRoot(container(true))
    animationRule.advanceBy(650.ms)
    val wrapper = checkNotNull(testView.findViewWithTagOrNull(WRAPPER_TAG))
    assertThat(wrapper.translationX).isGreaterThan(0.5f).isLessThan(2f)
    assertThat(checkNotNull(wrapper.clipBounds).width()).isLessThan(wrapper.width)
    assertThat(settled).isEmpty()
    animationRule.advanceBy(50.ms)
    assertThat(wrapper.translationX).isZero()
    assertThat(wrapper.clipBounds).isNull()
    assertThat(settled).containsExactly(true)

    testView.setRoot(container(false))
    animationRule.advanceBy(650.ms)
    assertThat(testView.findComponent(Marker::class)).isNotNull()
    assertThat(settled).containsExactly(true)
    animationRule.advanceBy(50.ms)
    assertThat(testView.findComponent(Marker::class)).isNull()
    assertThat(settled).containsExactly(true, false)
  }

  // --- Helpers ---

  private fun wrapped(visible: Boolean, exit: ExitTransition = fadeOut(linearFade)): Component =
      Column {
        child(
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(linearFade),
                exit = exit,
                style = Style.viewTag(WRAPPER_TAG),
            ) {
              Marker()
            },
        )
      }

  private class Marker : KComponent() {
    override fun ComponentScope.render(): Component = Row(style = Style.width(50.px).height(50.px))
  }

  companion object {
    private const val WRAPPER_TAG = "avWrapper"
  }
}
