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
import com.facebook.litho.animation.width
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.core.height
import com.facebook.litho.core.width
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.layout.flexbox.Row
import com.facebook.litho.testing.LithoAnimationTestRule
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.useEffect
import com.facebook.litho.view.alpha
import com.facebook.litho.view.viewTag
import com.facebook.rendercore.px
import kotlin.time.Duration.Companion.milliseconds
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode

/**
 * Tests for the same-frame animation invalidation path (`enableAnimationFrameInvalidation`).
 *
 * The probe is deliberate: [animationRule] is constructed with an empty `afterFrame`, so frames are
 * driven without pumping the main looper. A bind-only animated value that updates the mounted view
 * after [LithoAnimationTestRule.runNextFrame] with no `idle()` proves the value was applied during
 * the frame callback rather than via a deferred sync-state runnable. With the flag off these
 * assertions would fail, because the update would still be sitting on the looper.
 */
@OptIn(ExperimentalLithoApi::class)
@LooperMode(LooperMode.Mode.PAUSED)
@RunWith(LithoTestRunner::class)
class AnimationFrameInvalidationTest {

  @Rule
  @JvmField
  val lithoTestRule = LithoTestRule(
      ComponentsConfiguration.defaultInstance.copy(
          enableAnimationFrameInvalidation = true,
      ),
  )

  // Empty afterFrame: no looper pump after each frame, so a passing assertion proves the bind-only
  // update was applied in-frame.
  @Rule @JvmField val animationRule = LithoAnimationTestRule()

  private var capturedAnimatable: Animatable<Float>? = null
  private var capturedSiblingAlpha: Value<Float>? = null
  private var effectRunCount = 0
  private var effectReadValue = 0f

  @Test
  fun bindOnlyAlpha_appliesDuringFrameWithoutLooperPump() {
    val testView = lithoTestRule.render { AlphaComponent(target = 0f) }
    shadowOf(getMainLooper()).idle()
    assertThat(testView.lithoView.alpha).isEqualTo(0f)

    testView.setRoot(AlphaComponent(target = 1f))
    shadowOf(getMainLooper()).idle()

    // The first frame establishes the animation start time; the second produces a changed value.
    animationRule.runNextFrame()
    animationRule.runNextFrame()

    // No looper pump here — the in-frame path must have already updated the mounted alpha.
    assertThat(testView.lithoView.alpha).isGreaterThan(0f).isLessThan(1f)
  }

  @Test
  fun bindOnlyAlpha_settlesToTargetInFrame() {
    val testView = lithoTestRule.render { AlphaComponent(target = 0f) }
    shadowOf(getMainLooper()).idle()

    testView.setRoot(AlphaComponent(target = 1f))
    shadowOf(getMainLooper()).idle()

    // Advance comfortably past the tween duration so the animation settles on its completion frame.
    repeat(20) { animationRule.runNextFrame() }

    // The completion frame force-publishes; with no looper pump the final value is applied
    // in-frame.
    assertThat(testView.lithoView.alpha).isEqualTo(1f)
  }

  @Test
  fun resolveReadValue_recomputesInFrameViaLayoutPath() {
    val testView = lithoTestRule.render { ResolveAlphaComponent(target = 0f) }
    shadowOf(getMainLooper()).idle()
    assertThat(testView.lithoView.alpha).isEqualTo(0f)

    testView.setRoot(ResolveAlphaComponent(target = 1f))
    shadowOf(getMainLooper()).idle()

    animationRule.runNextFrame()
    animationRule.runNextFrame()
    // The layout path queued the update + requested layout; drive the traversal it scheduled
    // (measure then layout) with no looper pump. The pendingAnimationLayoutUpdates marker is
    // consumed during that traversal and recomputes only the dirty scope.
    testView.measure().layout()

    assertThat(testView.lithoView.alpha).isGreaterThan(0f).isLessThan(1f)
  }

  @Test
  fun resolveReadValue_settlesToTargetViaLayoutPath() {
    val testView = lithoTestRule.render { ResolveAlphaComponent(target = 0f) }
    shadowOf(getMainLooper()).idle()

    testView.setRoot(ResolveAlphaComponent(target = 1f))
    shadowOf(getMainLooper()).idle()

    repeat(20) {
      animationRule.runNextFrame()
      testView.measure().layout()
    }

    assertThat(testView.lithoView.alpha).isEqualTo(1f)
  }

  @Test
  fun snapTo_bindOnly_appliesImmediatelyWithoutLooperPump() {
    val testView = lithoTestRule.render { ImperativeAlphaComponent() }
    shadowOf(getMainLooper()).idle()
    assertThat(testView.lithoView.alpha).isEqualTo(0f)

    // snapTo runs outside a frame tick; the bind-only update must flush immediately, in-frame.
    checkNotNull(capturedAnimatable).jumpTo(1f)

    // No looper pump — the value is applied synchronously via the invalidation path.
    assertThat(testView.lithoView.alpha).isEqualTo(1f)
  }

  @Test
  fun publishingCommand_preservesReplacementStartedDuringSynchronousBind() {
    for (firstOutcome in listOf(AnimationEnd.Finished, AnimationEnd.Interrupted)) {
      val outcomes = mutableListOf<Pair<String, AnimationEnd>>()
      var replaceOnBind = false
      val testView = lithoTestRule.render {
        ImperativeAlphaComponent {
          if (replaceOnBind) {
            replaceOnBind = false
            checkNotNull(capturedAnimatable).startAnimation(0f, snap()) {
              outcomes.add("replacement" to it)
            }
          }
        }
      }
      lithoTestRule.idle()
      val animatable = checkNotNull(capturedAnimatable)
      animatable.startAnimation(1f, tween(1_000.milliseconds)) { outcomes.add("first" to it) }
      replaceOnBind = true

      if (firstOutcome == AnimationEnd.Finished) animatable.finish() else animatable.jumpTo(1f)

      assertThat(testView.lithoView.alpha).isEqualTo(1f)
      assertThat(replaceOnBind).isFalse()
      assertThat(outcomes).isEmpty()
      assertThat(AnimationController.hasRunningAnimations).isTrue()
      animationRule.runNextFrame()
      assertThat(testView.lithoView.alpha).isEqualTo(0f)
      assertThat(outcomes)
          .containsExactly("first" to firstOutcome, "replacement" to AnimationEnd.Finished)
      assertThat(animationRule.hasScheduledFrames()).isFalse()
      testView.release()
    }
  }

  @Test
  fun valueReadAtBothBindAndResolve_takesLayoutPathNotBindFastPath() {
    val testView = lithoTestRule.render { BindAndResolveAlphaComponent(target = 0f) }
    shadowOf(getMainLooper()).idle()
    assertThat(testView.lithoView.alpha).isEqualTo(0f)

    testView.setRoot(BindAndResolveAlphaComponent(target = 1f))
    shadowOf(getMainLooper()).idle()

    // The value is read during render (resolve), so the frame takes the layout path: it sets the
    // pending marker rather than applying via applyChanges, leaving the mounted alpha unchanged
    // until a traversal runs.
    animationRule.runNextFrame()
    animationRule.runNextFrame()
    assertThat(testView.lithoView.alpha).isEqualTo(0f)

    // Driving the traversal consumes the marker and applies the value in one layout/mount pass.
    testView.measure().layout()
    assertThat(testView.lithoView.alpha).isGreaterThan(0f).isLessThan(1f)
  }

  @Test
  fun forcedRelayoutWithPendingAnimationLayout_stillAppliesValue() {
    val testView = lithoTestRule.render { ResolveAlphaComponent(target = 0f) }
    shadowOf(getMainLooper()).idle()

    testView.setRoot(ResolveAlphaComponent(target = 1f))
    shadowOf(getMainLooper()).idle()

    animationRule.runNextFrame()
    animationRule.runNextFrame()

    // A forced relayout coincides with the pending animation layout update. The forced path takes
    // over, and must still apply the queued (uncommitted) animation value rather than dropping it.
    testView.lithoView.forceRelayout()
    testView.measure().layout()

    assertThat(testView.lithoView.alpha).isGreaterThan(0f).isLessThan(1f)
  }

  @Test
  fun frameWithLayoutAndBindOnlyAnimations_appliesBindOnlyValue() {
    val testView = lithoTestRule.render { LayoutAndBindAlphaComponent(expanded = false) }
    shadowOf(getMainLooper()).idle()
    assertThat(testView.lithoView.alpha).isEqualTo(0f)

    testView.setRoot(LayoutAndBindAlphaComponent(expanded = true))
    shadowOf(getMainLooper()).idle()

    animationRule.runNextFrame()
    animationRule.runNextFrame()
    testView.measure().layout()

    assertThat(testView.lithoView.alpha).isGreaterThan(0f).isLessThan(1f)
  }

  @Test
  fun frameWithSiblingLayoutAndBindOnlyAnimations_appliesBindOnlyValue() {
    val testView = lithoTestRule.render { SiblingLayoutAndBindAlphaComponent(expanded = false) }
    shadowOf(getMainLooper()).idle()
    val alphaChild = checkNotNull(testView.lithoView.findViewWithTag(ALPHA_CHILD_TAG))
    assertThat(capturedSiblingAlpha!!.value).isEqualTo(0f)
    assertThat(alphaChild.alpha).isEqualTo(0f)

    testView.setRoot(SiblingLayoutAndBindAlphaComponent(expanded = true))
    shadowOf(getMainLooper()).idle()

    animationRule.runNextFrame()
    animationRule.runNextFrame()
    testView.measure().layout()

    assertThat(capturedSiblingAlpha!!.value).isGreaterThan(0f).isLessThan(1f)
    assertThat(alphaChild.alpha).isGreaterThan(0f).isLessThan(1f)
  }

  @Test
  fun valueReadAtBindAndEffect_reRunsEffectViaCommitPath() {
    val testView = lithoTestRule.render { BindAndEffectAlphaComponent(target = 0f) }
    shadowOf(getMainLooper()).idle()
    assertThat(effectRunCount).isGreaterThanOrEqualTo(1)

    testView.setRoot(BindAndEffectAlphaComponent(target = 1f))
    shadowOf(getMainLooper()).idle()
    val runsBeforeAnimation = effectRunCount

    // The value is read in an effect, so the frame must take the committing path, not the bind-only
    // fast path — effects only re-run at commit. The frame queues the update; driving the traversal
    // commits it and re-runs the effect with the mid-animation value.
    animationRule.runNextFrame()
    animationRule.runNextFrame()
    testView.measure().layout()

    assertThat(effectRunCount).isGreaterThan(runsBeforeAnimation)
    assertThat(effectReadValue).isGreaterThan(0f).isLessThan(1f)
  }

  @Test
  fun unmount_whileAnimating_unregistersFromController() {
    val testView = lithoTestRule.render { AlphaComponent(target = 0f) }
    shadowOf(getMainLooper()).idle()

    // Retargeting starts an animation; its useEffect registers it with the controller on commit.
    testView.setRoot(AlphaComponent(target = 1f))
    shadowOf(getMainLooper()).idle()
    assertThat(AnimationController.hasRunningAnimations).isTrue()

    // Unmounting must run the animation's onCleanup, which stops and unregisters it — otherwise the
    // controller would keep ticking a released tree.
    testView.detachFromWindow().release()

    assertThat(AnimationController.hasRunningAnimations).isFalse()
  }

  @Test
  fun infiniteAnimation_pauseThenResume_continuesFrameUpdates() {
    val testView = lithoTestRule.render { InfiniteAlphaComponent(running = true) }
    shadowOf(getMainLooper()).idle()
    assertThat(AnimationController.hasRunningAnimations).isTrue()

    animationRule.runNextFrame()
    animationRule.runNextFrame()
    assertThat(testView.lithoView.alpha).isGreaterThan(0f)

    testView.setRoot(InfiniteAlphaComponent(running = false))
    shadowOf(getMainLooper()).idle()
    assertThat(AnimationController.hasRunningAnimations).isFalse()

    testView.setRoot(InfiniteAlphaComponent(running = true))
    shadowOf(getMainLooper()).idle()
    assertThat(AnimationController.hasRunningAnimations).isTrue()

    animationRule.runNextFrame()
    assertThat(testView.lithoView.alpha).isEqualTo(0f)
    animationRule.runNextFrame()

    assertThat(testView.lithoView.alpha).isGreaterThan(0f)
  }

  @Test
  fun infiniteAnimation_configChangeWhilePaused_snapsToNewFrom() {
    // Start paused at from = 0; the initial value is the mounted alpha.
    val testView = lithoTestRule.render {
      ConfigurableInfiniteAlphaComponent(running = false, from = 0f)
    }
    shadowOf(getMainLooper()).idle()
    assertThat(testView.lithoView.alpha).isEqualTo(0f)

    // A config change while paused snaps to the new `from` immediately, without starting to
    // animate.
    testView.setRoot(ConfigurableInfiniteAlphaComponent(running = false, from = 0.5f))
    shadowOf(getMainLooper()).idle()
    assertThat(AnimationController.hasRunningAnimations).isFalse()
    assertThat(testView.lithoView.alpha).isEqualTo(0.5f)
  }

  private class AlphaComponent(private val target: Float) : KComponent() {
    override fun ComponentScope.render(): Component? {
      val animatedAlpha = useAnimatedFloat(target, spec = tween(100.milliseconds))
      return Row(style = Style.width(100.px).height(100.px).alpha(animatedAlpha))
    }
  }

  private class LayoutAndBindAlphaComponent(private val expanded: Boolean) : KComponent() {
    override fun ComponentScope.render(): Component {
      val transition = useAnimationGroup(expanded)
      val animatedWidth =
          transition.animateDimen(transitionSpec = { tween(100.milliseconds) }) { target ->
            if (target) 200.px else 100.px
          }
      val animatedAlpha =
          transition.animateFloat(transitionSpec = { tween(100.milliseconds) }) { target ->
            if (target) 1f else 0f
          }
      return Row(style = Style.width(animatedWidth).height(100.px).alpha(animatedAlpha))
    }
  }

  private inner class SiblingLayoutAndBindAlphaComponent(private val expanded: Boolean) :
      KComponent() {
    override fun ComponentScope.render(): Component {
      val transition = useAnimationGroup(expanded)
      val animatedWidth =
          transition.animateDimen(transitionSpec = { tween(100.milliseconds) }) { target ->
            if (target) 200.px else 100.px
          }
      val animatedAlpha =
          transition.animateFloat(transitionSpec = { tween(100.milliseconds) }) { target ->
            if (target) 1f else 0f
          }
      capturedSiblingAlpha = animatedAlpha
      return Column {
        child(Row(style = Style.width(animatedWidth).height(100.px)))
        child(
            Row(
                style =
                    Style.width(100.px)
                        .height(100.px)
                        .alpha(animatedAlpha)
                        .viewTag(ALPHA_CHILD_TAG),
            ),
        )
      }
    }
  }

  private class InfiniteAlphaComponent(private val running: Boolean) : KComponent() {
    override fun ComponentScope.render(): Component {
      val animatedAlpha =
          useInfiniteAnimationGroup(shouldRun = running)
              .animateFloat(from = 0f, to = 1f, animation = tween(100.milliseconds))
      return Row(style = Style.width(100.px).height(100.px).alpha(animatedAlpha))
    }
  }

  /** Varies the loop's `from` across renders so a config change restarts the slot. */
  private class ConfigurableInfiniteAlphaComponent(
      private val running: Boolean,
      private val from: Float,
  ) : KComponent() {
    override fun ComponentScope.render(): Component {
      val animatedAlpha =
          useInfiniteAnimationGroup(shouldRun = running)
              .animateFloat(from = from, to = 1f, animation = tween(100.milliseconds))
      return Row(style = Style.width(100.px).height(100.px).alpha(animatedAlpha))
    }
  }

  private inner class ImperativeAlphaComponent(private val onValueRead: ((Float) -> Unit)? = null) :
      KComponent() {
    override fun ComponentScope.render(): Component {
      val animated =
          useAnimatable(0f, FloatTypeConverter, label = "alpha")
              .also { capturedAnimatable = it }
              .value
      val value =
          if (onValueRead == null) animated
          else
              object : Value<Float> {
                override val value: Float
                  get() = animated.value.also { onValueRead.invoke(it) }
              }
      return Row(style = Style.width(100.px).height(100.px).alpha(value))
    }
  }

  /** Reads the value at both bind (`Style.alpha(Value)`) and resolve (`value.value` in render). */
  private class BindAndResolveAlphaComponent(private val target: Float) : KComponent() {
    override fun ComponentScope.render(): Component {
      val animated = useAnimatedFloat(target, spec = tween(100.milliseconds))
      // Resolve read of the value forces the layout path. `toInt()` is 0 across [0,1), so the width
      // stays effectively constant and only alpha is asserted.
      val current = animated.value
      return Row(style = Style.width((100 + current.toInt()).px).height(100.px).alpha(animated))
    }
  }

  /** Reads the value at bind (`Style.alpha`) and inside an effect, which re-runs only at commit. */
  private inner class BindAndEffectAlphaComponent(private val target: Float) : KComponent() {
    override fun ComponentScope.render(): Component {
      val animated = useAnimatedFloat(target, spec = tween(100.milliseconds))
      useEffect(Unit) {
        effectReadValue = animated.value
        effectRunCount++
        null
      }
      return Row(style = Style.width(100.px).height(100.px).alpha(animated))
    }
  }

  /**
   * Reads the animated value during render (a resolve read) and applies it as a static alpha, so
   * frame updates take the layout path rather than the bind/draw fast path.
   */
  private class ResolveAlphaComponent(private val target: Float) : KComponent() {
    override fun ComponentScope.render(): Component? {
      val animated = useAnimatedFloat(target, spec = tween(100.milliseconds))
      return Row(style = Style.width(100.px).height(100.px).alpha(animated.value))
    }
  }
}

private const val ALPHA_CHILD_TAG = "alpha-child"
