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

import com.facebook.rendercore.ms
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.data.Offset
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RepeatableSpecTest {

  // Cycle length now comes from DurationBasedAnimationSpec.totalDurationNanos, so iteration
  // boundaries are exact; the small tolerance only absorbs float rounding.
  private val tolerance = Offset.offset(0.5f)

  private fun ms(millis: Long): Long = millis * 1_000_000L

  @Test
  fun restart_repeatsFromInitialValue() {
    val spec = repeatable<Float>(
        iterations = 3,
        animation = tween(duration = 1000.ms, easing = Easing.Linear),
        repeatMode = RepeatMode.Restart,
    )
    val animator = spec.createAnimator(0f, 100f, FloatTypeConverter)

    // First iteration: 0→100
    assertThat(animator.getValue(ms(500))).isCloseTo(50f, tolerance)

    // Second iteration should restart from 0 — test at midpoint
    assertThat(animator.getValue(ms(1500))).isCloseTo(50f, tolerance)

    // Early in 2nd iteration should be near initial value
    assertThat(animator.getValue(ms(1050))).isCloseTo(5f, tolerance)
  }

  @Test
  fun reverse_alternatesDirection() {
    val spec = repeatable<Float>(
        iterations = 3,
        animation = tween(duration = 1000.ms, easing = Easing.Linear),
        repeatMode = RepeatMode.Reverse,
    )
    val animator = spec.createAnimator(0f, 100f, FloatTypeConverter)

    // First iteration (forward): 0→100
    assertThat(animator.getValue(ms(500))).isCloseTo(50f, tolerance)

    // Second iteration (reverse): 100→0 — midpoint going back
    assertThat(animator.getValue(ms(1500))).isCloseTo(50f, tolerance)

    // Third iteration (forward again): 0→100
    assertThat(animator.getValue(ms(2500))).isCloseTo(50f, tolerance)
  }

  @Test
  fun finiteIterations_isFinished_afterAllIterations() {
    val spec = repeatable<Float>(
        iterations = 2,
        animation = tween(duration = 1000.ms, easing = Easing.Linear),
    )
    val animator = spec.createAnimator(0f, 100f, FloatTypeConverter)

    assertThat(animator.isFinished(ms(500))).isFalse() // mid 1st iteration
    assertThat(animator.isFinished(ms(1500))).isFalse() // mid 2nd iteration
    assertThat(animator.isFinished(ms(2010))).isTrue() // past all iterations (with buffer)
  }

  @Test
  fun infinite_isNeverFinished() {
    val spec = infiniteRepeatable<Float>(
        animation = tween(duration = 1000.ms, easing = Easing.Linear),
    )
    val animator = spec.createAnimator(0f, 100f, FloatTypeConverter)

    assertThat(animator.isFinished(ms(1000))).isFalse()
    assertThat(animator.isFinished(ms(10_000))).isFalse()
    assertThat(animator.isFinished(ms(1_000_000))).isFalse()
  }

  @Test
  fun restart_afterAllIterations_returnsTargetValue() {
    val spec = repeatable<Float>(
        iterations = 2,
        animation = tween(duration = 1000.ms, easing = Easing.Linear),
        repeatMode = RepeatMode.Restart,
    )
    val animator = spec.createAnimator(0f, 100f, FloatTypeConverter)

    // After all iterations with Restart mode → target value
    assertThat(animator.getValue(ms(2010))).isCloseTo(100f, tolerance)
  }

  @Test
  fun reverse_evenIterations_returnsInitialValue() {
    val spec = repeatable<Float>(
        iterations = 2,
        animation = tween(duration = 1000.ms, easing = Easing.Linear),
        repeatMode = RepeatMode.Reverse,
    )
    val animator = spec.createAnimator(0f, 100f, FloatTypeConverter)

    // After 2 iterations with Reverse: forward then back → should end at initial value
    assertThat(animator.getValue(ms(2010))).isCloseTo(0f, tolerance)
  }

  @Test
  fun reverse_oddIterations_returnsTargetValue() {
    val spec = repeatable<Float>(
        iterations = 3,
        animation = tween(duration = 1000.ms, easing = Easing.Linear),
        repeatMode = RepeatMode.Reverse,
    )
    val animator = spec.createAnimator(0f, 100f, FloatTypeConverter)

    // After 3 iterations with Reverse: forward, back, forward → should end at target value
    assertThat(animator.getValue(ms(3010))).isCloseTo(100f, tolerance)
  }

  @Test
  fun infinite_zeroDuration_throws() {
    assertThatThrownBy { infiniteRepeatable<Float>(animation = snap()) }
        .isInstanceOf(IllegalArgumentException::class.java)
    assertThatThrownBy {
          infiniteRepeatable<Float>(animation = tween(duration = 0.ms, delay = 0.ms))
        }
        .isInstanceOf(IllegalArgumentException::class.java)
  }

  @Test
  fun startOffset_negativeOffset_throws() {
    assertThatThrownBy { StartOffset(-1) }.isInstanceOf(IllegalArgumentException::class.java)
    assertThatThrownBy { StartOffset((-1).ms) }.isInstanceOf(IllegalArgumentException::class.java)
  }

  @Test
  fun fastForwardOffset_startsAtPhase() {
    val spec = infiniteRepeatable<Float>(
        animation = tween(duration = 1000.ms, easing = Easing.Linear),
        initialStartOffset = StartOffset(500, StartOffsetType.FastForward),
    )
    val animator = spec.createAnimator(0f, 100f, FloatTypeConverter)

    // FastForward starts 500ms into the 1000ms cycle, so t=0 is already at the midpoint.
    assertThat(animator.getValue(0)).isCloseTo(50f, tolerance)
    assertThat(animator.getValue(ms(250))).isCloseTo(75f, tolerance)
  }

  @Test
  fun delayOffset_holdsAtFromThenRuns() {
    val spec = infiniteRepeatable<Float>(
        animation = tween(duration = 1000.ms, easing = Easing.Linear),
        initialStartOffset = StartOffset(500, StartOffsetType.Delay),
    )
    val animator = spec.createAnimator(0f, 100f, FloatTypeConverter)

    // Held at `from` during the 500ms delay window.
    assertThat(animator.getValue(ms(250))).isCloseTo(0f, tolerance)
    // After the delay, the cycle runs: t=1000ms is 500ms into the first cycle → midpoint.
    assertThat(animator.getValue(ms(1000))).isCloseTo(50f, tolerance)
  }

  @Test
  fun delayOffset_appliesOnlyToFirstCycle() {
    val spec = infiniteRepeatable<Float>(
        animation = tween(duration = 1000.ms, easing = Easing.Linear),
        repeatMode = RepeatMode.Restart,
        initialStartOffset = StartOffset(500, StartOffsetType.Delay),
    )
    val animator = spec.createAnimator(0f, 100f, FloatTypeConverter)

    // First cycle spans [500, 1500]; the second cycle starts at 1500 with no extra delay.
    assertThat(animator.getValue(ms(1500))).isCloseTo(0f, tolerance)
    assertThat(animator.getValue(ms(2000))).isCloseTo(50f, tolerance)
  }

  @Test
  fun childDelay_repeatsEveryCycle() {
    // A tween's own delay is part of the cycle (totalDurationNanos = duration + delay), so unlike a
    // StartOffset it repeats: every cycle holds at `from` for the delay before animating.
    val spec = infiniteRepeatable<Float>(
        animation = tween(duration = 800.ms, delay = 200.ms, easing = Easing.Linear),
        repeatMode = RepeatMode.Restart,
    )
    val animator = spec.createAnimator(0f, 100f, FloatTypeConverter)

    // Cycle length is 1000ms. Cycle 0: [0,200] held, [200,1000] animates 0→100.
    assertThat(animator.getValue(ms(100))).isCloseTo(0f, tolerance)
    assertThat(animator.getValue(ms(600))).isCloseTo(50f, tolerance)
    // Cycle 1: the delay repeats over [1000,1200].
    assertThat(animator.getValue(ms(1100))).isCloseTo(0f, tolerance)
    assertThat(animator.getValue(ms(1600))).isCloseTo(50f, tolerance)
  }

  @Test
  fun repeatable_isFinite_butInfiniteRepeatable_isNot() {
    // The type contract is what keeps infinite specs out of target-settling APIs (which require
    // FiniteAnimationSpec): a finite repeat is finite, an infinite repeat is not.
    assertThat(repeatable<Float>(iterations = 2, animation = tween(1000.ms)))
        .isInstanceOf(FiniteAnimationSpec::class.java)
    assertThat(infiniteRepeatable<Float>(animation = tween(1000.ms)))
        .isNotInstanceOf(FiniteAnimationSpec::class.java)
  }
}
