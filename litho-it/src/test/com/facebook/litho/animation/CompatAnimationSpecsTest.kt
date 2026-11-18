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

import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import com.facebook.litho.dataflow.springs.Spring
import com.facebook.litho.dataflow.springs.SpringConfig
import kotlin.math.abs
import kotlin.math.max
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.within
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CompatAnimationSpecsTest {

  @Test
  fun compatSpring_tracksLegacySpringAcrossDampingRegimes() {
    for ((tension, friction) in SPRING_CONFIGS) {
      for (travel in TRAVELS) {
        val deviation = maxDeviation(compatSpring(tension, friction), tension, friction, travel)
        assertThat(deviation)
            .describedAs("tension=$tension friction=$friction travel=$travel")
            .isLessThanOrEqualTo(abs(travel) * MAX_DEVIATION_RATIO)
      }
    }
  }

  @Test
  fun compatSpring_defaultsTrackLegacyConstants() {
    val default = SpringConfig.defaultConfig
    val noOvershoot = SpringConfig.noOvershootConfig

    assertThat(maxDeviation(compatSpring(), default.tension, default.friction, 100f))
        .isLessThanOrEqualTo(100f * MAX_DEVIATION_RATIO)
    assertThat(
        maxDeviation(compatSpringNoOvershoot(), noOvershoot.tension, noOvershoot.friction, 100f),
    )
        .isLessThanOrEqualTo(100f * MAX_DEVIATION_RATIO)
  }

  @Test
  fun compatSpring_defaultOvershoots_unlikeSpring() {
    val compat = compatSpring<Float>().createAnimator(0f, 100f, FloatTypeConverter)
    val modern = spring<Float>().createAnimator(0f, 100f, FloatTypeConverter)

    assertThat(peak(compat)).isGreaterThan(100f)
    assertThat(peak(modern)).isLessThanOrEqualTo(100f)
  }

  @Test
  fun compatSpring_rejectsCoefficientsWithNoFiniteSpring() {
    // Non-positive tension, a tension that overflows Float, and zero friction (never settles).
    for ((tension, friction) in
        listOf(0.0 to 10.0, 1e40 to 22.0, 230.2 to 0.0, Double.NaN to 22.0)) {
      assertThatThrownBy { compatSpring<Float>(tension, friction) }
          .describedAs("tension=$tension friction=$friction")
          .isInstanceOf(IllegalArgumentException::class.java)
    }
  }

  @Test
  fun compatTiming_easesWithAccelerateDecelerate() {
    val animator = compatTiming<Float>(300).createAnimator(0f, 100f, FloatTypeConverter)
    val interpolator = AccelerateDecelerateInterpolator()

    for (ms in 0..300 step 30) {
      assertThat(animator.getValue(ms * NANOS_PER_MS))
          .isCloseTo(100f * interpolator.getInterpolation(ms / 300f), within(0.01f))
    }
  }

  @Test
  fun compatTiming_usesGivenInterpolator_andLinearForNull() {
    val overshoot = OvershootInterpolator()
    val custom = compatTiming<Float>(200, overshoot).createAnimator(0f, 100f, FloatTypeConverter)
    val linear = compatTiming<Float>(200, null).createAnimator(0f, 100f, FloatTypeConverter)

    assertThat(custom.getValue(100 * NANOS_PER_MS))
        .isCloseTo(100f * overshoot.getInterpolation(0.5f), within(0.01f))
    assertThat(linear.getValue(50 * NANOS_PER_MS)).isCloseTo(25f, within(0.01f))
  }

  @Test
  fun compatTiming_withNegativeDuration_finishesAtOnce() {
    // `Transition.timing(-25)` ends before it starts, so legacy jumps to the target; `tween`
    // rejects
    // a negative duration.
    for (spec in listOf(compatTiming<Float>(-25), compatTiming<Float>(-25, LinearInterpolator()))) {
      val animator = spec.createAnimator(0f, 100f, FloatTypeConverter)
      assertThat(animator.durationNanos).isEqualTo(0L)
      assertThat(animator.getValue(0L)).isEqualTo(100f)
    }
  }

  @Test
  fun compatSpecs_areEqualAcrossCalls() {
    // Specs built in render are compared across renders; a fresh but equal spec must not restart
    // a running transition.
    assertThat(compatSpring<Float>()).isEqualTo(compatSpring<Float>())
    assertThat(compatSpring<Float>(120.0, 14.0)).isEqualTo(compatSpring<Float>(120.0, 14.0))
    assertThat(compatTiming<Float>(300)).isEqualTo(compatTiming<Float>(300))
    assertThat(compatTiming<Float>(300, OvershootInterpolator()))
        .isEqualTo(compatTiming<Float>(300, OvershootInterpolator()))
  }

  /**
   * Steps the legacy spring frame by frame, as `SpringNode` does, and returns how far it strays
   * from the modern animator while the modern one is in flight.
   *
   * The legacy spring integrates in 1ms substeps and reports a value interpolated back between the
   * last two, so it trails its clock by up to one substep. Each legacy value is therefore compared
   * against the modern range over `[t - 1ms, t]` rather than a single sample; a fast spring moves
   * several units in that millisecond.
   *
   * Frames after the modern animator settles are excluded: it snaps to its target within 1% of the
   * travel, an engine-wide threshold that [spring] shares and the mapping does not control.
   */
  private fun maxDeviation(
      spec: FiniteAnimationSpec<Float>,
      tension: Double,
      friction: Double,
      travel: Float,
  ): Float {
    val legacy =
        Spring().setSpringConfig(SpringConfig(tension, friction)).apply {
          setCurrentValue(0.0)
          setEndValue(travel.toDouble())
        }
    val modern = spec.createAnimator(0f, travel, FloatTypeConverter)
    // Without these, an animator that starts finished compares no frames and reports no deviation.
    assertThat(modern.getValue(0L)).isEqualTo(0f)
    assertThat(modern.isFinished(0L)).isFalse()

    var deviation = 0f
    var frame = 0
    while (frame < MAX_FRAMES && !modern.isFinished(frameNanos(frame))) {
      val now = modern.getValue(frameNanos(frame))
      val lagged = modern.getValue(max(0L, frameNanos(frame) - NANOS_PER_MS))
      val value = legacy.getCurrentValue().toFloat()
      val gap =
          when {
            value < minOf(now, lagged) -> minOf(now, lagged) - value
            value > maxOf(now, lagged) -> value - maxOf(now, lagged)
            else -> 0f
          }
      deviation = max(deviation, gap)
      legacy.advance(FRAME_SECONDS)
      frame++
    }
    assertThat(frame).describedAs("did not settle").isLessThan(MAX_FRAMES)
    assertThat(frame).describedAs("compared too few in-flight frames").isGreaterThan(2)
    return deviation
  }

  private fun peak(animator: Animator<Float>): Float {
    var peak = Float.NEGATIVE_INFINITY
    for (frame in 0 until MAX_FRAMES) {
      peak = max(peak, animator.getValue(frameNanos(frame)))
    }
    return peak
  }

  private fun frameNanos(frame: Int): Long = frame * FRAME_NANOS

  private companion object {
    const val NANOS_PER_MS = 1_000_000L
    const val FRAME_NANOS = 16 * NANOS_PER_MS
    const val FRAME_SECONDS = 0.016
    const val MAX_FRAMES = 600

    // Both engines solve the same equation, so in-flight values agree to float rounding (measured
    // below 1e-6 of the travel). This leaves headroom for that while still catching a mapping
    // error.
    const val MAX_DEVIATION_RATIO = 1e-4f

    // Spans under-damped, the two `SpringConfig` constants, near-critical and over-damped.
    val SPRING_CONFIGS = listOf(
        40.0 to 6.0,
        120.0 to 14.0,
        SpringConfig.DEFAULT_TENSION to SpringConfig.DEFAULT_FRICTION,
        338.8 to 34.0,
        400.0 to 40.0,
        1000.0 to 30.0,
        50.0 to 30.0,
    )

    // A pixel-scale move, an alpha-scale fade and a reversed move.
    val TRAVELS = listOf(100f, 1f, -250f)
  }
}
