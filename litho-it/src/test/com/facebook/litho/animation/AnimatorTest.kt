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

import com.facebook.rendercore.IntSize
import com.facebook.rendercore.ms
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AnimatorTest {

  @Test
  fun infiniteAnimatorHasNoCompletionBoundary() {
    val infinite =
        infiniteRepeatable<Float>(tween(100.ms)).createAnimator(0f, 1f, FloatTypeConverter)
    assertThat(infinite.durationNanos).isEqualTo(Animator.DURATION_INFINITE)
    for (time in longArrayOf(0L, 100_000_000L, Long.MAX_VALUE)) {
      assertThat(infinite.isFinished(time)).isFalse()
    }
  }

  @Test
  fun finiteBuiltInsReportTheirCompleteBoundDuration() {
    val cycle = tween<Float>(100.ms, delay = 20.ms)
    val cases = listOf(
        Triple(tween<Float>(100.ms, delay = 40.ms), 140_000_000L, 100f),
        Triple(keyframes<Float>(200.ms) { 120f at 100.ms }, 200_000_000L, 100f),
        Triple(snap<Float>(), 0L, 100f),
        Triple(repeatable(3, cycle, initialStartOffset = StartOffset(30)), 390_000_000L, 100f),
        Triple(repeatable(2, cycle, RepeatMode.Reverse), 240_000_000L, 0f),
        Triple(
            repeatable(3, cycle, initialStartOffset = StartOffset(30, StartOffsetType.FastForward)),
            330_000_000L,
            100f,
        ),
        Triple(
            repeatable(2, cycle, RepeatMode.Reverse, StartOffset(300, StartOffsetType.FastForward)),
            0L,
            0f,
        ),
        Triple(
            repeatable(2, snap<Float>(), initialStartOffset = StartOffset(30)),
            30_000_000L,
            100f,
        ),
        Triple(repeatable(2, snap<Float>()), 0L, 100f),
    )
    for ((spec, duration, finalValue) in cases) {
      assertBoundary(spec.createAnimator(0f, 100f, FloatTypeConverter), duration, finalValue)
    }

    val movingSpring = spring<Float>().createAnimator(0f, 100f, FloatTypeConverter)
    assertThat(movingSpring.durationNanos).isGreaterThan(0L)
    assertBoundary(movingSpring, movingSpring.durationNanos, 100f)
    assertBoundary(spring<Float>().createAnimator(1f, 1f, FloatTypeConverter), 0L, 1f)
  }

  @Test
  fun equalEndpointsKeepExplicitTimingAndKeyframeMotion() {
    val animators = listOf(
        tween<Float>(100.ms, delay = 40.ms),
        keyframes<Float>(140.ms) { 20f at 70.ms },
    )
        .map { it.createAnimator(1f, 1f, FloatTypeConverter) }
    for (animator in animators) {
      assertThat(animator.isFinished(0L)).isFalse()
      assertBoundary(animator, 140_000_000L, 1f)
    }
    assertThat(animators[1].getValue(70_000_000L)).isEqualTo(20f)
  }

  @Test
  fun compositeReportsLastAxisBoundary() {
    val velocity = IntSizeTypeConverter.newVector()
    for ((width, height) in
        listOf(
            tween<Float>(10.ms) to tween<Float>(20.ms),
            tween<Float>(20.ms) to tween<Float>(10.ms),
        )) {
      val animator =
          CompositeIntSizeSpec(width, height)
              .createAnimator(IntSize(0, 0), IntSize(1, 2), IntSizeTypeConverter)
      assertThat(animator.durationNanos).isEqualTo(20_000_000L)
      assertThat(animator.isFinished(19_999_999L)).isFalse()
      assertThat(animator.isFinished(20_000_000L)).isTrue()
      assertThat(animator.getValue(20_000_000L)).isEqualTo(IntSize(1, 2))
      animator.getVelocity(20_000_000L, velocity)
      assertThat(velocity[0]).isZero()
      assertThat(velocity[1]).isZero()
    }
  }

  @Test
  fun invalidAndOverflowingDurationsAreRejectedAtSpecConstruction() {
    val invalidDurations = listOf(
        (-1).ms,
        Duration.INFINITE,
        (Long.MAX_VALUE / 1_000_000L + 1L).milliseconds,
    )
    for (duration in invalidDurations) {
      for (createSpec in
          listOf(
              { tween<Float>(duration) },
              { tween<Float>(delay = duration) },
              { keyframes<Float>(duration) {} },
          )) {
        assertThatThrownBy { createSpec() }.isInstanceOf(IllegalArgumentException::class.java)
      }
    }
    val negativeCycle =
        object : DurationBasedAnimationSpec<Float> by tween<Float>(1.ms) {
          override val totalDurationNanos: Long = -1L
        }
    assertThatThrownBy { repeatable(2, negativeCycle) }
        .isInstanceOf(IllegalArgumentException::class.java)
    assertThatThrownBy { infiniteRepeatable(negativeCycle) }
        .isInstanceOf(IllegalArgumentException::class.java)
    val largest = Long.MAX_VALUE.nanoseconds
    val overflowFactories = listOf(
        { tween<Float>(largest, delay = 1.ms) },
        { repeatable(2, tween<Float>(largest)) },
        { repeatable(1, tween<Float>(largest), initialStartOffset = StartOffset(1)) },
    )
    for (createSpec in overflowFactories) {
      assertThatThrownBy { createSpec() }.isInstanceOf(IllegalArgumentException::class.java)
    }
    val shifted = repeatable(
        1,
        tween<Float>(largest),
        initialStartOffset = StartOffset(1, StartOffsetType.FastForward),
    )
        .createAnimator(0f, 1f, FloatTypeConverter)
    assertBoundary(shifted, largest.inWholeNanoseconds - 1_000_000L, 1f)
  }

  private fun assertBoundary(animator: Animator<Float>, duration: Long, finalValue: Float) {
    assertThat(animator.durationNanos).isEqualTo(duration)
    if (duration > 0L) assertThat(animator.isFinished(duration - 1L)).isFalse()
    val velocity = FloatTypeConverter.newVector()
    for (time in longArrayOf(duration, Long.MAX_VALUE)) {
      assertThat(animator.isFinished(time)).isTrue()
      assertThat(animator.getValue(time)).isEqualTo(finalValue)
      velocity[0] = 123f
      animator.getVelocity(time, velocity)
      assertThat(velocity[0]).isZero()
    }
  }
}
