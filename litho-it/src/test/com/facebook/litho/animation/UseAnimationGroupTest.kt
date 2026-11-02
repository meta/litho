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
import com.facebook.litho.Value
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.core.height
import com.facebook.litho.core.width
import com.facebook.litho.layout.flexbox.Row
import com.facebook.litho.testing.LithoAnimationTestRule
import com.facebook.litho.testing.LithoTestRule
import com.facebook.litho.testing.testrunner.LithoTestRunner
import com.facebook.rendercore.px
import kotlin.time.Duration.Companion.milliseconds
import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.LooperMode

@OptIn(ExperimentalLithoApi::class)
@LooperMode(LooperMode.Mode.PAUSED)
@RunWith(LithoTestRunner::class)
class UseAnimationGroupTest {

  @Rule @JvmField val lithoTestRule = LithoTestRule()

  @Rule @JvmField val animationRule = LithoAnimationTestRule { lithoTestRule.idle() }

  private var capturedGroup: AnimationGroup<Boolean>? = null
  private var capturedStringGroup: AnimationGroup<String>? = null
  private var capturedAlpha: Value<Float>? = null
  private var capturedRotation: Value<Float>? = null
  private var capturedShortValue: Value<Float>? = null
  private var capturedLongValue: Value<Float>? = null
  private var capturedPlayback: AnimationPlayback? = null
  private val capturedSegments = mutableListOf<Segment<Boolean>>()

  @Before
  fun setUp() {
    capturedGroup = null
    capturedStringGroup = null
    capturedAlpha = null
    capturedRotation = null
    capturedShortValue = null
    capturedLongValue = null
    capturedPlayback = null
    capturedSegments.clear()
  }

  // --- Segment tests ---

  @Test
  fun segment_isTransitioningTo_matchingDirection() {
    val segment = Segment(false, true)
    val result = with(segment) { false.isTransitioningTo(true) }
    assertThat(result).isTrue()
  }

  @Test
  fun segment_isTransitioningTo_nonMatchingDirection() {
    val segment = Segment(false, true)
    val result = with(segment) { true.isTransitioningTo(false) }
    assertThat(result).isFalse()
  }

  // --- useAnimationGroup tests ---

  @Test
  fun useAnimationGroup_initialState_isApplied() {
    lithoTestRule.render { GroupAlphaComponent(expanded = false) }
    lithoTestRule.idle()

    assertThat(capturedGroup).isNotNull
    assertThat(capturedGroup!!.state.currentState).isFalse()
    assertThat(capturedGroup!!.targetState).isFalse()
    assertThat(capturedGroup!!.state.isRunning).isFalse()
  }

  @Test
  fun useAnimationGroup_stateChange_retargetsAllChildren() {
    val testView = lithoTestRule.render { GroupAlphaComponent(expanded = false) }
    lithoTestRule.idle()

    assertThat(testView.lithoView.alpha).isEqualTo(0f)

    testView.setRoot(GroupAlphaComponent(expanded = true))
    lithoTestRule.idle()
    animationRule.runNextFrame()

    assertThat(testView.lithoView.alpha).isEqualTo(1f)
  }

  @Test
  fun useAnimationGroup_directionalSpec_receivesCorrectSegment() {
    val testView = lithoTestRule.render { DirectionalSpecComponent(expanded = false) }
    lithoTestRule.idle()

    // First render: segment is (false, false) — identity transition
    assertThat(capturedSegments).hasSize(1)
    assertThat(capturedSegments[0]).isEqualTo(Segment(false, false))

    capturedSegments.clear()

    // State change false → true
    testView.setRoot(DirectionalSpecComponent(expanded = true))
    lithoTestRule.idle()

    assertThat(capturedSegments).isNotEmpty
    assertThat(capturedSegments[0]).isEqualTo(Segment(false, true))
  }

  @Test
  fun useAnimationGroup_completion_updatesCurrentState() {
    val testView = lithoTestRule.render { GroupAlphaComponent(expanded = false) }
    lithoTestRule.idle()

    assertThat(capturedGroup!!.state.currentState).isFalse()

    testView.setRoot(GroupAlphaComponent(expanded = true))
    lithoTestRule.idle()
    animationRule.runNextFrame()

    // After snap animation completes, currentState should catch up to targetState
    assertThat(capturedGroup!!.state.currentState).isTrue()
    assertThat(capturedGroup!!.targetState).isTrue()
    assertThat(capturedGroup!!.state.isRunning).isFalse()
  }

  @Test
  fun useAnimationGroup_midFlightRetarget_skipsIntermediateState() {
    val testView = lithoTestRule.render { ThreeStateComponent(state = "A") }
    lithoTestRule.idle()

    assertThat(capturedStringGroup!!.state.currentState).isEqualTo("A")

    // Rapid state changes: A → B → C without settling
    testView.setRoot(ThreeStateComponent(state = "B"))
    testView.setRoot(ThreeStateComponent(state = "C"))
    lithoTestRule.idle()
    animationRule.runNextFrame()

    // currentState should be "C" — never settled at "B"
    assertThat(capturedStringGroup!!.state.currentState).isEqualTo("C")
    assertThat(capturedStringGroup!!.targetState).isEqualTo("C")
  }

  @Test
  fun useAnimationGroup_reverseBeforeSettle_segmentDescribesTheReversedLeg() {
    val reverseSpec: Segment<String>.() -> FiniteAnimationSpec<Float> = {
      if ("B" isTransitioningTo "A") tween(1_000.milliseconds) else snap()
    }
    val testView = lithoTestRule.render { ThreeStateComponent("A", reverseSpec) }
    lithoTestRule.idle()
    animationRule.runNextFrame()
    assertThat(capturedStringGroup!!.state.segment).isEqualTo(Segment("A", "A"))

    testView.setRoot(ThreeStateComponent("B", reverseSpec))
    lithoTestRule.idle()
    assertThat(capturedStringGroup!!.state.segment).isEqualTo(Segment("A", "B"))

    // Reverse before the run to "B" settles. Reported from the last settled state the segment
    // would collapse to ("A", "A") and a directional transitionSpec would match no branch.
    testView.setRoot(ThreeStateComponent("A", reverseSpec))
    lithoTestRule.idle()
    assertThat(capturedStringGroup!!.state.segment).isEqualTo(Segment("B", "A"))
    assertThat(capturedStringGroup!!.state.hasAnimator).isTrue()
    animationRule.advanceBy(1_000.milliseconds)
    assertThat(capturedStringGroup!!.state.segment).isEqualTo(Segment("A", "A"))
  }

  @Test
  fun useAnimationGroup_multipleProperties_animateIndependently() {
    val testView = lithoTestRule.render { MultiPropertyComponent(expanded = false) }
    lithoTestRule.idle()

    assertThat(capturedAlpha!!.value).isEqualTo(0f)
    assertThat(capturedRotation!!.value).isEqualTo(0f)

    testView.setRoot(MultiPropertyComponent(expanded = true))
    lithoTestRule.idle()
    animationRule.runNextFrame()

    assertThat(capturedAlpha!!.value).isEqualTo(1f)
    assertThat(capturedRotation!!.value).isEqualTo(180f)
  }

  @Test
  fun retargetAlignsActiveSettledAndUnstartedSlotsAcrossFrameGaps() {
    val frames = List(3) { mutableListOf<Long>() }
    val notifications = mutableListOf<Int>()
    var group: AnimationGroup<Int>? = null
    fun spec(slot: Int, durationMs: Int): FiniteAnimationSpec<Float> =
        object : FiniteAnimationSpec<Float> {
          override fun createAnimator(
              initialValue: Float,
              targetValue: Float,
              typeConverter: TypeConverter<Float>,
              initialVelocity: AnimationVector?,
          ): Animator<Float> {
            val animator =
                tween<Float>(durationMs.milliseconds, Easing.Linear)
                    .createAnimator(initialValue, targetValue, typeConverter, initialVelocity)
            return object : Animator<Float> by animator {
              override fun getValue(playTimeNanos: Long): Float {
                frames[slot].add(playTimeNanos)
                return animator.getValue(playTimeNanos)
              }
            }
          }
        }
    val specs = List(3) { spec(it, 1_000) }
    val short = spec(1, 100)
    fun component(target: Int): Component =
        object : KComponent() {
          override fun ComponentScope.render(): Component {
            val transition = useAnimationGroup(target, onSettled = { notifications.add(it) })
            group = transition
            val active = transition.animateFloat(transitionSpec = { specs[0] }) { it.toFloat() }
            val settled =
                transition.animateFloat(
                    transitionSpec = { if (targetState == 1) short else specs[1] },
                ) {
                  it.toFloat()
                }
            transition.animateFloat(
                transitionSpec = { if (targetState == 1) snap() else specs[2] },
            ) {
              if (it == 2) 1f else 0f
            }
            return Row(style = Style.width(10.px).height(10.px).alpha(active).rotation(settled))
          }
        }
    val view = lithoTestRule.render { component(0) }
    lithoTestRule.idle()
    view.setRoot(component(1))
    lithoTestRule.idle()
    animationRule.advanceBy(200.milliseconds)
    val values = checkNotNull(group).state.animatedValues
    assertThat(values.map { it.currentValueRaw }).containsExactly(0.2f, 1f, 0f)
    assertThat(notifications).isEmpty()

    view.setRoot(component(2))
    lithoTestRule.idle()
    assertThat(values.map { it.currentValueRaw }).containsExactly(0.2f, 1f, 0f)
    frames.forEach { it.clear() }
    animationRule.runNextFrame(200.milliseconds)
    for (samples in frames) {
      assertThat(samples).containsExactly(200.milliseconds.inWholeNanoseconds)
    }
    assertThat(values[0].clock).isSameAs(values[1].clock)
    assertThat(values[0].clock).isSameAs(values[2].clock)
    assertThat(values[0].currentValueRaw as Float).isBetween(0.55f, 0.57f)
    assertThat(values[1].currentValueRaw).isEqualTo(1.2f)
    assertThat(values[2].currentValueRaw).isEqualTo(0.2f)
    assertThat(notifications).isEmpty()

    animationRule.runNextFrame(800.milliseconds)
    assertThat(values.map { it.currentValueRaw }).containsExactly(2f, 2f, 1f)
    assertThat(notifications).containsExactly(2)
    assertThat(animationRule.hasScheduledFrames()).isFalse()
  }

  @Test
  fun useAnimationGroup_retargetAfterOneChildSettles_keepsAllChildrenAdvancing() {
    val testView = lithoTestRule.render { MixedDurationComponent(expanded = false) }
    lithoTestRule.idle()
    assertThat(capturedShortValue!!.value).isEqualTo(0f)
    assertThat(capturedLongValue!!.value).isEqualTo(0f)

    testView.setRoot(MixedDurationComponent(expanded = true))
    lithoTestRule.idle()

    repeat(8) { animationRule.runNextFrame() }
    assertThat(capturedShortValue!!.value).isEqualTo(1f)
    assertThat(capturedLongValue!!.value).isGreaterThan(0f).isLessThan(1f)

    testView.setRoot(MixedDurationComponent(expanded = false))
    lithoTestRule.idle()

    animationRule.runNextFrame()
    animationRule.runNextFrame()

    assertThat(capturedShortValue!!.value).isLessThan(1f)
  }

  @Test
  fun useAnimationGroup_deferredPlaybackPartialRetargetAfterPlay_resumesSettledChild() {
    val testView = lithoTestRule.render { DeferredMixedDurationComponent(expanded = false) }
    lithoTestRule.idle()

    testView.setRoot(DeferredMixedDurationComponent(expanded = true))
    lithoTestRule.idle()
    checkNotNull(capturedPlayback).play()

    repeat(8) { animationRule.runNextFrame() }
    assertThat(capturedShortValue!!.value).isEqualTo(1f)
    assertThat(capturedLongValue!!.value).isGreaterThan(0f).isLessThan(1f)

    testView.setRoot(DeferredMixedDurationComponent(expanded = false))
    lithoTestRule.idle()

    animationRule.runNextFrame()
    animationRule.runNextFrame()

    assertThat(capturedShortValue!!.value).isLessThan(1f)
  }

  @Test
  fun useAnimationGroup_reverseToCurrentStateWhileActive_retargetsAllChildren() {
    val testView = lithoTestRule.render { MixedDurationComponent(expanded = true) }
    lithoTestRule.idle()
    assertThat(capturedShortValue!!.value).isEqualTo(1f)
    assertThat(capturedLongValue!!.value).isEqualTo(1f)

    testView.setRoot(MixedDurationComponent(expanded = false))
    lithoTestRule.idle()
    animationRule.runNextFrame()
    animationRule.runNextFrame()
    assertThat(capturedLongValue!!.value).isGreaterThan(0f).isLessThan(1f)

    testView.setRoot(MixedDurationComponent(expanded = true))
    lithoTestRule.idle()
    animationRule.runNextFrame()
    animationRule.runNextFrame()

    assertThat(capturedShortValue!!.value).isGreaterThan(0f)
    assertThat(capturedLongValue!!.value).isGreaterThan(0f)
  }

  @Test
  fun useAnimationGroup_segment_reflectsCurrentAndTargetState() {
    val testView = lithoTestRule.render { GroupAlphaComponent(expanded = false) }
    lithoTestRule.idle()

    assertThat(capturedGroup!!.state.segment).isEqualTo(Segment(false, false))

    testView.setRoot(GroupAlphaComponent(expanded = true))
    lithoTestRule.idle()
    animationRule.runNextFrame()

    // After completion, segment reflects the settled state
    assertThat(capturedGroup!!.state.segment).isEqualTo(Segment(true, true))
  }

  // --- Test components ---

  private inner class GroupAlphaComponent(
      private val expanded: Boolean,
  ) : KComponent() {
    override fun ComponentScope.render(): Component? {
      val group = useAnimationGroup(expanded)
      capturedGroup = group
      val alpha =
          group.animateFloat(
              transitionSpec = { snap() },
          ) { state ->
            if (state) 1f else 0f
          }
      return Row(style = Style.width(100.px).height(100.px).alpha(alpha))
    }
  }

  private inner class DirectionalSpecComponent(
      private val expanded: Boolean,
  ) : KComponent() {
    override fun ComponentScope.render(): Component? {
      val group = useAnimationGroup(expanded)
      capturedGroup = group
      val alpha =
          group.animateFloat(
              transitionSpec = {
                capturedSegments.add(Segment(initialState, targetState))
                snap()
              },
          ) { state ->
            if (state) 1f else 0f
          }
      return Row(style = Style.width(100.px).height(100.px).alpha(alpha))
    }
  }

  private inner class ThreeStateComponent(
      private val state: String,
      private val transitionSpec: Segment<String>.() -> FiniteAnimationSpec<Float> = { snap() },
  ) : KComponent() {
    override fun ComponentScope.render(): Component? {
      val group = useAnimationGroup(state)
      capturedStringGroup = group
      val alpha =
          group.animateFloat(
              transitionSpec = transitionSpec,
          ) { s ->
            when (s) {
              "A" -> 0f
              "B" -> 0.5f
              else -> 1f
            }
          }
      return Row(style = Style.width(100.px).height(100.px).alpha(alpha))
    }
  }

  private inner class MultiPropertyComponent(
      private val expanded: Boolean,
  ) : KComponent() {
    override fun ComponentScope.render(): Component? {
      val group = useAnimationGroup(expanded)
      capturedGroup = group
      val alpha =
          group.animateFloat(
              transitionSpec = { snap() },
          ) { state ->
            if (state) 1f else 0f
          }
      val rotation =
          group.animateFloat(
              transitionSpec = { snap() },
          ) { state ->
            if (state) 180f else 0f
          }
      capturedAlpha = alpha
      capturedRotation = rotation
      return Row(style = Style.width(100.px).height(100.px).alpha(alpha))
    }
  }

  private inner class DeferredMixedDurationComponent(
      private val expanded: Boolean,
  ) : KComponent() {
    override fun ComponentScope.render(): Component? {
      val playback = useAnimationPlayback(autoPlay = false)
      capturedPlayback = playback
      val group = useAnimationGroup(expanded, playback = playback)
      capturedShortValue =
          group.animateFloat(transitionSpec = { tween(32.milliseconds) }) { state ->
            if (state) 1f else 0f
          }
      capturedLongValue =
          group.animateFloat(transitionSpec = { tween(1_000.milliseconds) }) { state ->
            if (state) 1f else 0f
          }
      return Row(style = Style.width(100.px).height(100.px))
    }
  }

  private inner class MixedDurationComponent(
      private val expanded: Boolean,
  ) : KComponent() {
    override fun ComponentScope.render(): Component? {
      val group = useAnimationGroup(expanded)
      capturedGroup = group
      val shortValue =
          group.animateFloat(
              transitionSpec = { tween(32.milliseconds) },
          ) { state ->
            if (state) 1f else 0f
          }
      val longValue =
          group.animateFloat(
              transitionSpec = { tween(1_000.milliseconds) },
          ) { state ->
            if (state) 1f else 0f
          }
      capturedShortValue = shortValue
      capturedLongValue = longValue
      return Row(style = Style.width(100.px).height(100.px).alpha(shortValue))
    }
  }
}
