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
import com.facebook.litho.LithoPrimitive
import com.facebook.litho.LithoRenderTreeView
import com.facebook.litho.NestedLithoTree
import com.facebook.litho.NestedLithoTreeState
import com.facebook.litho.PrimitiveComponent
import com.facebook.litho.PrimitiveComponentScope
import com.facebook.litho.ResolveResult
import com.facebook.litho.Style
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.bindToRenderTreeView
import com.facebook.litho.config.ComponentsConfiguration
import com.facebook.litho.core.height
import com.facebook.litho.core.width
import com.facebook.litho.layout.flexbox.Row
import com.facebook.litho.layoutVersion
import com.facebook.litho.testing.LithoAnimationTestRule
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.litho.useNestedTree
import com.facebook.rendercore.SizeConstraints
import com.facebook.rendercore.ms
import com.facebook.rendercore.primitives.LayoutBehavior
import com.facebook.rendercore.primitives.LayoutScope
import com.facebook.rendercore.primitives.Primitive
import com.facebook.rendercore.primitives.PrimitiveLayoutResult
import com.facebook.rendercore.primitives.ViewAllocator
import com.facebook.rendercore.px
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode

@OptIn(ExperimentalLithoApi::class)
@LooperMode(LooperMode.Mode.PAUSED)
@RunWith(LithoTestRunner::class)
class AnimatableTest {

  @Rule
  @JvmField
  val lithoTestRule = LithoTestRule(
      ComponentsConfiguration.defaultInstance.copy(enableStateReadTracking = true),
  )

  @Rule @JvmField val animationRule = LithoAnimationTestRule { shadowOf(getMainLooper()).idle() }

  private var capturedAnimatable: Animatable<Float>? = null

  @Before
  fun setUp() {
    capturedAnimatable = null
  }

  @Test
  fun useAnimatable_animateTo_updatesMountedValue() {
    val testView = lithoTestRule.render { ImperativeAlphaComponent() }
    shadowOf(getMainLooper()).idle()
    assertThat(testView.lithoView.alpha).isEqualTo(0f)

    checkNotNull(capturedAnimatable).animateTo(1f, snap())
    animationRule.runNextFrame()

    assertThat(testView.lithoView.alpha).isEqualTo(1f)
  }

  @Test
  fun useAnimatable_rejectsInfiniteAnimatorFromFiniteSpec() {
    lithoTestRule.render { ImperativeAlphaComponent() }
    shadowOf(getMainLooper()).idle()
    val animatable = checkNotNull(capturedAnimatable)

    assertThatThrownBy {
          animatable.animateTo(1f, VelocityTrackingAnimationSpec(Animator.DURATION_INFINITE))
        }
        .isInstanceOf(IllegalArgumentException::class.java)
        .hasMessageContaining("FiniteAnimationSpec must create an animator with a finite duration")
    assertThat(animatable.isRunning).isFalse()
  }

  @Test
  fun useAnimatable_snapTo_updatesMountedValueWithoutRunningAnimation() {
    val testView = lithoTestRule.render { ImperativeAlphaComponent() }
    shadowOf(getMainLooper()).idle()
    assertThat(testView.lithoView.alpha).isEqualTo(0f)

    checkNotNull(capturedAnimatable).snapTo(0.5f)
    shadowOf(getMainLooper()).idle()

    assertThat(testView.lithoView.alpha).isEqualTo(0.5f)
    assertThat(checkNotNull(capturedAnimatable).isRunning).isFalse()
  }

  @Test
  fun useAnimatable_stop_haltsFurtherAnimationFrames() {
    val testView = lithoTestRule.render { ImperativeAlphaComponent() }
    shadowOf(getMainLooper()).idle()

    val animatable = checkNotNull(capturedAnimatable)
    animatable.animateTo(1f, tween(duration = 1_000.ms, easing = Easing.Linear))

    animationRule.advanceBy(200.ms)
    val valueBeforeStop = testView.lithoView.alpha

    assertThat(valueBeforeStop).isGreaterThan(0f)
    assertThat(valueBeforeStop).isLessThan(1f)
    assertThat(animatable.isRunning).isTrue()

    animatable.stop()
    animationRule.advanceBy(700.ms)

    assertThat(animatable.isRunning).isFalse()
    assertThat(testView.lithoView.alpha).isEqualTo(valueBeforeStop)

    animationRule.advanceBy(600.ms)
    assertThat(testView.lithoView.alpha).isEqualTo(valueBeforeStop)
  }

  @Test
  fun useAnimatable_retargetUsesManualAnimationPlayTimeForVelocity() {
    lithoTestRule.render { ImperativeAlphaComponent() }
    shadowOf(getMainLooper()).idle()

    val animatable = checkNotNull(capturedAnimatable)
    val firstSpec = VelocityTrackingAnimationSpec()
    val secondSpec = VelocityTrackingAnimationSpec()

    animatable.animateTo(1f, firstSpec)
    animationRule.advanceBy(200.ms)
    animatable.animateTo(0f, secondSpec)

    assertThat(secondSpec.initialVelocity).isEqualTo(200f)
  }

  @Test
  fun useAnimatable_retargetPassesOwnedVelocitySnapshot() {
    lithoTestRule.render { ImperativeAlphaComponent() }
    shadowOf(getMainLooper()).idle()

    val animatable = checkNotNull(capturedAnimatable)
    val firstSpec = VelocityTrackingAnimationSpec()
    val secondSpec = VelocityTrackingAnimationSpec()
    val thirdSpec = VelocityTrackingAnimationSpec()

    animatable.animateTo(1f, firstSpec)
    animationRule.advanceBy(200.ms)
    animatable.animateTo(0f, secondSpec)
    val secondVelocity = checkNotNull(secondSpec.initialVelocityVector)
    assertThat(secondVelocity[0]).isEqualTo(200f)

    animationRule.advanceBy(100.ms)
    animatable.animateTo(1f, thirdSpec)

    assertThat(secondVelocity[0]).isEqualTo(200f)
    assertThat(thirdSpec.initialVelocityVector).isNotSameAs(secondVelocity)
  }

  @Test
  fun useAnimatable_retarget_advancesOnFirstFrameWithoutStall() {
    val testView = lithoTestRule.render { ImperativeAlphaComponent() }
    shadowOf(getMainLooper()).idle()
    val animatable = checkNotNull(capturedAnimatable)

    animatable.animateTo(1f, tween(duration = 160.ms, easing = Easing.Linear))
    animationRule.advanceBy(80.ms)
    val mid = testView.lithoView.alpha
    assertThat(mid).isGreaterThan(0f)
    assertThat(mid).isLessThan(1f)

    animatable.animateTo(0f, tween(duration = 160.ms, easing = Easing.Linear))
    animationRule.runNextFrame()
    assertThat(testView.lithoView.alpha).isLessThan(mid)
  }

  @Test
  fun completionChainsAdvanceOnTheNextFrameAtSystemScaledTime() {
    for (scale in listOf(1f, 2f)) {
      for (finishExplicitly in listOf(false, true)) {
        val view = lithoTestRule.render { ImperativeAlphaComponent() }
        shadowOf(getMainLooper()).idle()
        animationRule.durationScale = scale
        val animatable = checkNotNull(capturedAnimatable)
        val outcomes = mutableListOf<AnimationEnd>()
        val spec = tween<Float>(100.ms, Easing.Linear)
        animatable.animateTo(1f, spec) { outcome ->
          outcomes.add(outcome)
          if (outcome == AnimationEnd.Finished) {
            animatable.animateTo(2f, spec) { outcomes.add(it) }
          }
        }
        if (finishExplicitly) {
          animationRule.advanceBy(50.ms * scale.toDouble())
          animatable.finish()
          animationRule.runNextFrame()
        } else {
          animationRule.advanceBy(100.ms * scale.toDouble())
        }
        assertThat(animatable.value.value).isEqualTo(1f)
        assertThat(outcomes).containsExactly(AnimationEnd.Finished)
        animationRule.runNextFrame(16.ms * scale.toDouble())
        assertThat(animatable.value.value).isEqualTo(1.16f)
        animationRule.runNextFrame(84.ms * scale.toDouble())
        assertThat(animatable.value.value).isEqualTo(2f)
        assertThat(outcomes).containsExactly(AnimationEnd.Finished, AnimationEnd.Finished)
        assertThat(animationRule.hasScheduledFrames()).isFalse()
        view.release()
      }
    }
  }

  @Test
  fun useAnimatable_insideNestedTree_updatesMountedValue() {
    val testView =
        lithoTestRule.render(widthPx = 100, heightPx = 100) {
          NestedTreeHost { ImperativeAlphaComponent() }
        }
    val nestedHost = testView.lithoView.getChildAt(0)
    shadowOf(getMainLooper()).idle()
    assertThat(nestedHost.alpha).isEqualTo(0f)

    checkNotNull(capturedAnimatable).snapTo(0.5f)
    shadowOf(getMainLooper()).idle()

    assertThat(nestedHost.alpha).isEqualTo(0.5f)
    assertThat(checkNotNull(capturedAnimatable).isRunning).isFalse()
  }

  @Test
  fun noOpCommand_completesAsynchronouslyWithoutAnAnimator() {
    lithoTestRule.render { ImperativeAlphaComponent() }
    lithoTestRule.idle()
    val outcomes = mutableListOf<AnimationEnd>()
    val animatable = checkNotNull(capturedAnimatable)

    animatable.animateTo(0f, snap()) { firstOutcome ->
      outcomes.add(firstOutcome)
      animatable.animateTo(0f, snap()) { secondOutcome -> outcomes.add(secondOutcome) }
    }

    assertThat(outcomes).isEmpty()
    assertThat(animatable.isRunning).isFalse()
    assertThat(AnimationController.hasRunningAnimations).isFalse()
    assertThat(animationRule.hasScheduledFrames()).isTrue()
    animationRule.runNextFrame()
    assertThat(outcomes).containsExactly(AnimationEnd.Finished)
    assertThat(animationRule.hasScheduledFrames()).isTrue()
    animationRule.runNextFrame()
    assertThat(outcomes).containsExactly(AnimationEnd.Finished, AnimationEnd.Finished)
    assertThat(animationRule.hasScheduledFrames()).isFalse()
  }

  @Test
  fun equalEndpointCommand_preservesTimedWork() {
    val specs = listOf(
        tween<Float>(1_000.ms) to 0f,
        chain<Float> {
          animateTo(1f, tween(400.ms, Easing.Linear))
          wait(200.ms)
          animateToTarget(tween(400.ms, Easing.Linear))
        } to 1f,
    )
    for ((spec, midpoint) in specs) {
      val view = lithoTestRule.render { ImperativeAlphaComponent() }
      lithoTestRule.idle()
      val outcomes = mutableListOf<AnimationEnd>()
      val animatable = checkNotNull(capturedAnimatable)

      animatable.animateTo(0f, spec) { outcomes.add(it) }
      assertThat(animatable.value.value).isZero()
      assertThat(outcomes).isEmpty()
      animationRule.advanceBy(500.ms)

      assertThat(animatable.value.value).isEqualTo(midpoint)
      assertThat(animatable.isRunning).isTrue()
      assertThat(outcomes).isEmpty()
      animationRule.advanceBy(500.ms)
      assertThat(animatable.value.value).isZero()
      assertThat(outcomes).containsExactly(AnimationEnd.Finished)
      assertThat(animationRule.hasScheduledFrames()).isFalse()
      view.release()
    }
  }

  @Test
  fun replacementAndStop_keepEachCommandsTerminalOutcome() {
    val view = lithoTestRule.render { ImperativeAlphaComponent() }
    lithoTestRule.idle()
    val outcomes = mutableListOf<Pair<String, AnimationEnd>>()
    val animatable = checkNotNull(capturedAnimatable)
    val spec = tween<Float>(duration = 1_000.ms, easing = Easing.Linear)
    animatable.animateTo(1f, spec) { outcomes.add("first" to it) }
    animationRule.advanceBy(200.ms)
    view.setRoot(ImperativeAlphaComponent(label = "updated alpha"))
    lithoTestRule.idle()
    assertThat(capturedAnimatable).isSameAs(animatable)
    val progress = view.lithoView.alpha

    animatable.animateTo(0f, spec) { outcomes.add("second" to it) }
    animatable.stop()

    assertThat(outcomes).isEmpty()
    assertThat(view.lithoView.alpha).isEqualTo(progress)
    animationRule.runNextFrame()
    assertThat(outcomes)
        .containsExactly(
            "first" to AnimationEnd.Interrupted,
            "second" to AnimationEnd.Cancelled,
        )
    assertThat(animationRule.hasScheduledFrames()).isFalse()
  }

  @Test
  fun finishedCommand_isNotReclassifiedByReplacementBeforeDelivery() {
    val view = lithoTestRule.render { ImperativeAlphaComponent() }
    lithoTestRule.idle()
    val outcomes = mutableListOf<Pair<String, AnimationEnd>>()
    val animatable = checkNotNull(capturedAnimatable)
    animatable.animateTo(1f, snap()) { outcomes.add("first" to it) }
    AnimationController.enqueueCompletion {
      animatable.animateTo(0f, snap()) { outcomes.add("second" to it) }
    }

    animationRule.runNextFrame()

    assertThat(view.lithoView.alpha).isEqualTo(1f)
    assertThat(outcomes).containsExactly("first" to AnimationEnd.Finished)
    animationRule.runNextFrame()
    assertThat(view.lithoView.alpha).isEqualTo(0f)
    assertThat(outcomes)
        .containsExactly(
            "first" to AnimationEnd.Finished,
            "second" to AnimationEnd.Finished,
        )
    assertThat(animationRule.hasScheduledFrames()).isFalse()
  }

  @Test
  fun snapTo_interruptsTheCommandAfterPublishingItsValue() {
    val view = lithoTestRule.render { ImperativeAlphaComponent() }
    lithoTestRule.idle()
    val outcomes = mutableListOf<Pair<AnimationEnd, Float>>()
    val animatable = checkNotNull(capturedAnimatable)
    animatable.animateTo(1f, tween(1_000.ms)) { outcomes.add(it to view.lithoView.alpha) }
    animationRule.advanceBy(200.ms)

    animatable.snapTo(0.5f)
    lithoTestRule.idle()

    assertThat(outcomes).isEmpty()
    animationRule.runNextFrame()
    assertThat(outcomes).containsExactly(AnimationEnd.Interrupted to 0.5f)
    assertThat(animationRule.hasScheduledFrames()).isFalse()
  }

  @Test
  fun finish_publishesTargetAndKeepsFinishedOutcomeAcrossStop() {
    val view = lithoTestRule.render { ImperativeAlphaComponent() }
    lithoTestRule.idle()
    val outcomes = mutableListOf<Pair<AnimationEnd, Float>>()
    val animatable = checkNotNull(capturedAnimatable)
    animatable.animateTo(1f, tween(1_000.ms)) { outcomes.add(it to view.lithoView.alpha) }

    animatable.finish()
    animatable.finish()
    animatable.stop()
    lithoTestRule.idle()

    assertThat(view.lithoView.alpha).isEqualTo(1f)
    assertThat(outcomes).isEmpty()
    animationRule.runNextFrame()
    assertThat(outcomes).containsExactly(AnimationEnd.Finished to 1f)
    assertThat(animationRule.hasScheduledFrames()).isFalse()
  }

  @Test
  fun unmount_cancelsPreparedCommandAndStillDeliversItsOutcome() {
    val view = lithoTestRule.render { ImperativeAlphaComponent() }
    lithoTestRule.idle()
    val outcomes = mutableListOf<AnimationEnd>()
    checkNotNull(capturedAnimatable).animateTo(1f, tween(1_000.ms)) { outcomes.add(it) }

    view.release()
    lithoTestRule.idle()

    assertThat(outcomes).isEmpty()
    assertThat(animationRule.hasScheduledFrames()).isTrue()
    animationRule.runNextFrame()
    assertThat(outcomes).containsExactly(AnimationEnd.Cancelled)
    assertThat(animationRule.hasScheduledFrames()).isFalse()
  }

  private inner class ImperativeAlphaComponent(private val label: String = "alpha") : KComponent() {
    override fun ComponentScope.render(): Component = Row(
        style =
            Style.width(100.px)
                .height(100.px)
                .alpha(
                    useAnimatable(0f, FloatTypeConverter, label = label)
                        .also { capturedAnimatable = it }
                        .value,
                ),
    )
  }

  private class NestedTreeHost(
      private val child: ComponentScope.() -> Component,
  ) : PrimitiveComponent() {

    override fun PrimitiveComponentScope.render(): LithoPrimitive {
      val (nestedTreeState, resolveResult) = useNestedTree(root = child())

      return LithoPrimitive(
          Primitive(
              layoutBehavior = NestedTreeLayoutBehavior(nestedTreeState, resolveResult),
              mountBehavior =
                  MountBehavior(
                      ViewAllocator { context -> LithoRenderTreeView(context) },
                  ) {
                    bindToRenderTreeView(state = nestedTreeState) { this }
                  },
          ),
          style = null,
      )
    }
  }

  private class NestedTreeLayoutBehavior(
      private val nestedTreeState: NestedLithoTreeState,
      private val resolveResult: ResolveResult,
  ) : LayoutBehavior {
    override fun LayoutScope.layout(sizeConstraints: SizeConstraints): PrimitiveLayoutResult {
      val layoutState =
          NestedLithoTree.layout(
              result = resolveResult,
              sizeConstraints = sizeConstraints,
              current = nestedTreeState.currentLayoutState,
              version = layoutVersion,
          )

      return PrimitiveLayoutResult(
          width = layoutState.width.coerceIn(sizeConstraints.minWidth, sizeConstraints.maxWidth),
          height =
              layoutState.height.coerceIn(sizeConstraints.minHeight, sizeConstraints.maxHeight),
          layoutData = layoutState,
      )
    }
  }

  private class VelocityTrackingAnimationSpec(
      private val reportedDurationNanos: Long = 1_000_000_000L,
  ) : FiniteAnimationSpec<Float> {
    var initialVelocity: Float = Float.NaN
      private set

    var initialVelocityVector: AnimationVector? = null
      private set

    override fun createAnimator(
        initialValue: Float,
        targetValue: Float,
        typeConverter: TypeConverter<Float>,
        initialVelocity: AnimationVector?,
    ): Animator<Float> {
      initialVelocityVector = initialVelocity
      this.initialVelocity = initialVelocity?.get(0) ?: 0f
      return object : Animator<Float> {
        override val durationNanos: Long = reportedDurationNanos

        override fun getValue(playTimeNanos: Long): Float {
          if (isFinished(playTimeNanos)) return targetValue
          val fraction = (playTimeNanos / 1_000_000_000f).coerceIn(0f, 1f)
          return initialValue + (targetValue - initialValue) * fraction
        }

        override fun getVelocity(playTimeNanos: Long, velocity: AnimationVector) {
          velocity[0] = if (isFinished(playTimeNanos)) 0f else playTimeNanos / 1_000_000f
        }
      }
    }
  }
}
