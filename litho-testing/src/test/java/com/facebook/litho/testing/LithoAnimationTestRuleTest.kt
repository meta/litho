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

package com.facebook.litho.testing

import android.os.Looper.getMainLooper
import com.facebook.litho.Component
import com.facebook.litho.ComponentScope
import com.facebook.litho.KComponent
import com.facebook.litho.Style
import com.facebook.litho.animation.Animatable
import com.facebook.litho.animation.Easing
import com.facebook.litho.animation.FloatTypeConverter
import com.facebook.litho.animation.alpha as animatedAlpha
import com.facebook.litho.animation.transition
import com.facebook.litho.animation.tween
import com.facebook.litho.animation.useAnimatable
import com.facebook.litho.animation.useAnimatedValue
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.core.height
import com.facebook.litho.core.width
import com.facebook.litho.layout.flexbox.Row
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.view.alpha as viewAlpha
import com.facebook.litho.view.viewTag
import com.facebook.rendercore.ms
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
class LithoAnimationTestRuleTest {

  @Rule @JvmField val lithoTestRule = LithoTestRule()

  @Rule @JvmField val animationRule = LithoAnimationTestRule { shadowOf(getMainLooper()).idle() }

  private var capturedAnimatable: Animatable<Float>? = null

  @Test
  fun advanceBy_drivesUseAnimatedValueHookAndRunsAfterFrame() {
    val testView = lithoTestRule.render { AnimatedValueAlphaComponent(target = 0f) }
    shadowOf(getMainLooper()).idle()
    assertThat(testView.lithoView.alpha).isEqualTo(0f)

    testView.setRoot(AnimatedValueAlphaComponent(target = 1f))

    animationRule.advanceBy(250.ms)

    assertThat(testView.lithoView.alpha).isEqualTo(0.25f)
    assertThat(animationRule.hasScheduledFrames()).isTrue()

    animationRule.advanceBy(750.ms)

    assertThat(testView.lithoView.alpha).isEqualTo(1f)
    assertThat(animationRule.hasScheduledFrames()).isFalse()
  }

  @Test
  fun advanceBy_drivesUseAnimatableAnimationAndRunsAfterFrame() {
    val testView = lithoTestRule.render { AnimatableAlphaComponent() }
    shadowOf(getMainLooper()).idle()
    assertThat(testView.lithoView.alpha).isEqualTo(0f)

    checkNotNull(capturedAnimatable)
        .startAnimation(1f, tween(duration = 1_000.ms, easing = Easing.Linear))

    assertThat(checkNotNull(capturedAnimatable).isRunning).isFalse()

    animationRule.advanceBy(250.ms)

    assertThat(testView.lithoView.alpha).isEqualTo(0.25f)
    assertThat(animationRule.hasScheduledFrames()).isTrue()

    animationRule.advanceBy(750.ms)

    assertThat(testView.lithoView.alpha).isEqualTo(1f)
    assertThat(checkNotNull(capturedAnimatable).isRunning).isFalse()
    assertThat(animationRule.hasScheduledFrames()).isFalse()
  }

  @Test
  fun advanceBy_drivesStyleTransitionAndRunsAfterFrame() {
    val testView = lithoTestRule.render { StyleTransitionAlphaComponent(target = 1f) }
    shadowOf(getMainLooper()).idle()
    val view = testView.findViewWithTag(STYLE_TRANSITION_TAG)

    assertThat(view.alpha).isEqualTo(1f)

    testView.setRoot(StyleTransitionAlphaComponent(target = 0f))
    shadowOf(getMainLooper()).idle()

    assertThat(view.alpha).isEqualTo(1f)

    animationRule.advanceBy(250.ms)

    assertThat(view.alpha).isEqualTo(0.75f)
    assertThat(animationRule.hasScheduledFrames()).isTrue()

    animationRule.advanceBy(750.ms)

    assertThat(view.alpha).isEqualTo(0f)
    assertThat(animationRule.hasScheduledFrames()).isFalse()
  }

  @Test
  fun consecutiveAdvanceBy_bootstrapsRunningAnimationOnlyOnce() {
    val testView = lithoTestRule.render { AnimatableAlphaComponent() }
    shadowOf(getMainLooper()).idle()

    checkNotNull(capturedAnimatable)
        .startAnimation(1f, tween(duration = 1_000.ms, easing = Easing.Linear))

    animationRule.advanceBy(100.ms)
    animationRule.advanceBy(100.ms)

    assertThat(testView.lithoView.alpha).isEqualTo(0.2f)
    assertThat(animationRule.hasScheduledFrames()).isTrue()
  }

  private class AnimatedValueAlphaComponent(private val target: Float) : KComponent() {
    override fun ComponentScope.render(): Component {
      val animatedAlpha = useAnimatedValue(
          targetValue = target,
          typeConverter = FloatTypeConverter,
          spec = tween(duration = 1_000.ms, easing = Easing.Linear),
          label = "alpha",
      )
      return Row(style = Style.width(100.px).height(100.px).animatedAlpha(animatedAlpha))
    }
  }

  private inner class AnimatableAlphaComponent : KComponent() {
    override fun ComponentScope.render(): Component = Row(
        style =
            Style.width(100.px)
                .height(100.px)
                .animatedAlpha(
                    useAnimatable(0f, FloatTypeConverter, label = "alpha")
                        .also { capturedAnimatable = it }
                        .animatedValue,
                ),
    )
  }

  private class StyleTransitionAlphaComponent(
      private val target: Float,
  ) : KComponent() {
    override fun ComponentScope.render(): Component = Row(
        style =
            Style.width(100.px)
                .height(100.px)
                .viewAlpha(target)
                .transition { alpha = tween(duration = 1_000.ms, easing = Easing.Linear) }
                .viewTag(STYLE_TRANSITION_TAG),
    )
  }

  private companion object {
    const val STYLE_TRANSITION_TAG = "style_transition"
  }
}
