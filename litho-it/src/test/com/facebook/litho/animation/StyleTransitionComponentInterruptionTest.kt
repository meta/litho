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
import android.view.View
import com.facebook.litho.Component
import com.facebook.litho.ComponentScope
import com.facebook.litho.DynamicValue
import com.facebook.litho.KComponent
import com.facebook.litho.Style
import com.facebook.litho.Value
import com.facebook.litho.animated.alpha as dynamicAlpha
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.core.height
import com.facebook.litho.core.width
import com.facebook.litho.key
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.layout.flexbox.Row
import com.facebook.litho.testing.LithoAnimationTestRule
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.view.alpha as viewAlpha
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
 * Component-level counterparts to [StyleTransitionInterruptionTest], exercising the same diff-based
 * `Style.transition` interruption behaviors end-to-end through real mounted [KComponent]s rather
 * than driving [StyleTransitionManager] directly. Frames are advanced via [LithoAnimationTestRule]
 * and assertions read the mounted view's alpha.
 */
@OptIn(ExperimentalLithoApi::class)
@LooperMode(LooperMode.Mode.PAUSED)
@RunWith(LithoTestRunner::class)
class StyleTransitionComponentInterruptionTest {

  @JvmField @Rule val lithoTestRule = LithoTestRule()

  @JvmField @Rule val animationRule = LithoAnimationTestRule { shadowOf(getMainLooper()).idle() }

  @Test
  fun reRenderWithUnchangedTargetMidFlightKeepsAnimationRunning() {
    val testView = lithoTestRule.render { FadeComponent(targetAlpha = 1f, bgColor = Color.RED) }
    shadowOf(getMainLooper()).idle()
    val view = testView.findViewWithTag(FADE_TAG)
    assertThat(view.alpha).isEqualTo(1f)

    // Start a 1f -> 0f fade and advance to mid-flight (0.75 at 250ms of a 1000ms linear tween).
    testView.setRoot(FadeComponent(targetAlpha = 0f, bgColor = Color.RED))
    shadowOf(getMainLooper()).idle()
    animationRule.advanceBy(250.ms)
    assertThat(view.alpha).isEqualTo(0.75f)

    // Re-render with the SAME alpha target (only an unrelated property changes). The in-flight
    // animation is preserved instead of restarting, so the value does not jump.
    testView.setRoot(FadeComponent(targetAlpha = 0f, bgColor = Color.BLUE))
    shadowOf(getMainLooper()).idle()
    assertThat(view.alpha).isEqualTo(0.75f)

    // It keeps running on its ORIGINAL clock: another 250ms lands at 0.5, not a restarted ramp.
    animationRule.advanceBy(250.ms)
    assertThat(view.alpha).isEqualTo(0.5f)
  }

  @Test
  fun keyedRemountWithUnchangedTargetMidFlightKeepsAnimationRunning() {
    val testView = lithoTestRule.render { RemountHostComponent(detailed = false, faded = false) }
    shadowOf(getMainLooper()).idle()
    assertThat(testView.findViewWithTag(CARD_TAG).alpha).isEqualTo(1f)

    // Start the fade and advance to mid-flight on the summary card.
    testView.setRoot(RemountHostComponent(detailed = false, faded = true))
    shadowOf(getMainLooper()).idle()
    animationRule.advanceBy(250.ms)
    assertThat(testView.findViewWithTag(CARD_TAG).alpha).isEqualTo(0.75f)

    // Keyed remount to the detailed card variant under the same key, with the SAME alpha target.
    // New content is mounted, but the running fade is carried straight onto it instead of snapping
    // to the static target.
    testView.setRoot(RemountHostComponent(detailed = true, faded = true))
    shadowOf(getMainLooper()).idle()
    assertThat(testView.findViewWithTag(CARD_TAG).alpha).isEqualTo(0.75f)

    // The fade continues on its original clock onto the remounted content.
    animationRule.advanceBy(250.ms)
    assertThat(testView.findViewWithTag(CARD_TAG).alpha).isEqualTo(0.5f)
  }

  @Test
  fun retargetMidFlightAdvancesOnTheNextFrameWithoutFreezing() {
    val testView = lithoTestRule.render { FadeComponent(targetAlpha = 1f, bgColor = Color.RED) }
    shadowOf(getMainLooper()).idle()
    val view = testView.findViewWithTag(FADE_TAG)

    testView.setRoot(FadeComponent(targetAlpha = 0f, bgColor = Color.RED))
    shadowOf(getMainLooper()).idle()
    animationRule.advanceBy(250.ms)
    val valueAtInterrupt = view.alpha
    assertThat(valueAtInterrupt).isEqualTo(0.75f)

    // Retarget back toward 1f mid-flight (a different target, so the animation is rebuilt). The
    // in-flight value is carried over as the new start.
    testView.setRoot(FadeComponent(targetAlpha = 1f, bgColor = Color.RED))
    shadowOf(getMainLooper()).idle()
    assertThat(view.alpha).isEqualTo(valueAtInterrupt)

    // The very next frame must already move toward the new target instead of re-emitting the start
    // value for one frozen frame.
    animationRule.runNextFrame()
    assertThat(view.alpha).isGreaterThan(valueAtInterrupt)
  }

  @Test
  fun undeclaredTransitionMidFlightSnapsToCommittedAlpha() {
    for (spec in listOf(FadeSpec.NULL, FadeSpec.ABSENT, FadeSpec.NO_TRANSITION)) {
      assertFadeSettles(spec, targetAlpha = 0f)
      assertFadeSettles(spec, targetAlpha = 1f)
      assertFadeSettles(spec, targetAlpha = 0f, width = 200)
      assertFadeSettles(spec, targetAlpha = 1f, alphaStyle = Style)
    }
  }

  @Test
  fun removingTransitionMidFlightPreservesBoundAlpha() {
    val value =
        object : Value<Float> {
          override val value: Float = 0.4f
        }
    assertFadeSettles(FadeSpec.NO_TRANSITION, targetAlpha = 0.4f, alphaStyle = Style.alpha(value))

    val dynamic = DynamicValue(0.4f)
    assertFadeSettles(
        FadeSpec.NO_TRANSITION,
        targetAlpha = 0.4f,
        alphaStyle = Style.dynamicAlpha(dynamic),
    ) { view ->
      dynamic.set(0.6f)
      assertThat(view.alpha).isEqualTo(0.6f)
    }
  }

  private fun assertFadeSettles(
      spec: FadeSpec,
      targetAlpha: Float,
      alphaStyle: Style = Style.viewAlpha(targetAlpha),
      width: Int = 100,
      checkBinding: (View) -> Unit = {},
  ) {
    fun root(component: Component?) =
        Column(style = Style.width(200.px).height(100.px)) {
          if (component != null) child(component)
        }
    val testView = lithoTestRule.render {
      root(FadeComponent(targetAlpha = 1f, bgColor = Color.RED))
    }
    shadowOf(getMainLooper()).idle()
    val view = testView.findViewWithTag(FADE_TAG)

    testView.setRoot(root(FadeComponent(targetAlpha = 0f, bgColor = Color.RED)))
    shadowOf(getMainLooper()).idle()
    animationRule.advanceBy(250.ms)
    assertThat(view.alpha).isEqualTo(0.75f)

    testView.setRoot(root(FadeComponent(targetAlpha, Color.RED, spec, alphaStyle.width(width.px))))
    shadowOf(getMainLooper()).idle()
    assertThat(view.width).isEqualTo(width)
    assertThat(view.alpha)
        .describedAs("$spec target=$targetAlpha width=$width")
        .isEqualTo(targetAlpha)
    animationRule.advanceBy(500.ms)
    assertThat(view.alpha).isEqualTo(targetAlpha)
    checkBinding(view)

    testView.setRoot(root(null))
    shadowOf(getMainLooper()).idle()
    assertThat(view.parent).isNull()
    assertThat(view.alpha).isEqualTo(1f)
    animationRule.advanceBy(500.ms)
    assertThat(view.alpha).isEqualTo(1f)
    testView.release()
  }

  private enum class FadeSpec {
    ANIMATED,
    NULL,
    ABSENT,
    NO_TRANSITION,
  }

  private class FadeComponent(
      targetAlpha: Float,
      private val bgColor: Int,
      private val spec: FadeSpec = FadeSpec.ANIMATED,
      private val alphaStyle: Style = Style.viewAlpha(targetAlpha),
  ) : KComponent() {
    override fun ComponentScope.render(): Component {
      val base = Style.width(100.px).height(100.px).backgroundColor(bgColor) + alphaStyle
      val style =
          when (spec) {
            FadeSpec.ANIMATED ->
                base.transition { alpha = tween(duration = 1_000.ms, easing = Easing.Linear) }
            FadeSpec.NULL -> base.transition { alpha = null }
            FadeSpec.ABSENT -> base.transition { scale = tween(duration = 1_000.ms) }
            FadeSpec.NO_TRANSITION -> base
          }
      return Row(style = style.viewTag(FADE_TAG))
    }
  }

  private class RemountHostComponent(
      private val detailed: Boolean,
      private val faded: Boolean,
  ) : KComponent() {
    override fun ComponentScope.render(): Component = Column {
      child(
          key(PRESERVED_KEY) {
            if (detailed) DetailedCard(faded = faded) else SummaryCard(faded = faded)
          },
      )
    }
  }

  private class SummaryCard(private val faded: Boolean) : KComponent() {
    override fun ComponentScope.render(): Component = Row(
        style =
            Style.width(200.px)
                .height(100.px)
                .viewAlpha(if (faded) 0f else 1f)
                .transition { alpha = tween(duration = 1_000.ms, easing = Easing.Linear) }
                .viewTag(CARD_TAG),
    )
  }

  private class DetailedCard(private val faded: Boolean) : KComponent() {
    override fun ComponentScope.render(): Component = Row(
        style =
            Style.width(320.px)
                .height(160.px)
                .viewAlpha(if (faded) 0f else 1f)
                .transition { alpha = tween(duration = 1_000.ms, easing = Easing.Linear) }
                .viewTag(CARD_TAG),
    )
  }

  private companion object {
    const val FADE_TAG = "fade"
    const val CARD_TAG = "card"
    const val PRESERVED_KEY = "preserved_card"
  }
}
