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
import kotlin.time.Duration.Companion.nanoseconds
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AnimationSpecCompositionTest {
  @Test
  fun delayAndLeadingHoldShareTimingAndStartAtRest() {
    val velocity = FloatTypeConverter.newVector().apply { this[0] = 80f }
    for (spec in listOf(tween<Float>(100.ms, Easing.Linear), spring<Float>())) {
      velocity[0] = 80f
      val direct = spec.createAnimator(0f, 10f, FloatTypeConverter)
      val delayed = spec.delayed(50.ms)
      val held =
          chain<Float> {
            wait(20.ms)
            wait(30.ms)
            animateToTarget(spec)
          }
      assertThat(held).isEqualTo(delayed)
      assertThat(spec.delayed(Duration.ZERO)).isSameAs(spec)
      assertThat(chain<Float> { animateToTarget(spec) }).isSameAs(spec)
      assertThat(spec.delayed(20.ms).delayed(30.ms)).isEqualTo(delayed)
      val animator = delayed.createAnimator(0f, 10f, FloatTypeConverter, velocity)
      assertThat(animator.durationNanos).isEqualTo(50_000_000L + direct.durationNanos)
      for (time in longArrayOf(-1L, 0L, 49_999_999L)) {
        assertThat(animator.getValue(time)).isZero()
        animator.getVelocity(time, velocity)
        assertThat(velocity[0]).isZero()
      }
      for (time in longArrayOf(0L, direct.durationNanos / 2, direct.durationNanos)) {
        assertThat(animator.getValue(50_000_000L + time)).isEqualTo(direct.getValue(time))
      }
      assertBoundary(animator, 10f)
    }
    val cycle = tween<Float>(100.ms, Easing.Linear).delayed(50.ms)
    assertThat(cycle.totalDurationNanos).isEqualTo(150.ms.inWholeNanoseconds)
    val repeated = repeatable(2, cycle).createAnimator(0f, 10f, FloatTypeConverter)
    assertThat(repeated.durationNanos).isEqualTo(300_000_000L)
    assertThat(repeated.getValue(175_000_000L)).isZero()
    assertThat(repeated.getValue(250_000_000L)).isEqualTo(5f)
    val reversed =
        repeatable(2, cycle, RepeatMode.Reverse).createAnimator(0f, 10f, FloatTypeConverter)
    assertThat(reversed.getValue(175_000_000L)).isEqualTo(10f)
    assertThat(reversed.getValue(225_000_000L)).isEqualTo(7.5f)
    assertBoundary(reversed, 0f)
    val widened: FiniteAnimationSpec<Float> = cycle
    assertThat(widened.delayed(10.ms)).isInstanceOf(DurationBasedAnimationSpec::class.java)
    val hold = spring<Float>().delayed(50.ms).createAnimator(1f, 1f, FloatTypeConverter)
    assertThat(hold.durationNanos).isEqualTo(50_000_000L)
    assertThat(hold.isFinished(49_999_999L)).isFalse()
    assertBoundary(hold, 1f)
    val large = 5_000_000_000_000_000_000L.nanoseconds
    val merged =
        chain<Float> {
              wait(large)
              wait(1.nanoseconds)
              animateToTarget(snap())
            }
            .createAnimator(0f, 1f, FloatTypeConverter)
    assertThat(merged.durationNanos).isEqualTo(large.inWholeNanoseconds + 1L)
  }

  @Test
  fun chainBindsOnceAndSeeksAcrossHoldsAndInstantLegs() {
    var bindings = 0
    val spec =
        object : FiniteAnimationSpec<Float> by tween<Float>(100.ms, Easing.Linear) {
          override fun createAnimator(
              initialValue: Float,
              targetValue: Float,
              typeConverter: TypeConverter<Float>,
              initialVelocity: AnimationVector?,
          ): Animator<Float> {
            bindings++
            return tween<Float>(100.ms, Easing.Linear)
                .createAnimator(initialValue, targetValue, typeConverter, initialVelocity)
          }
        }
    val animator =
        chain<Float> {
              animateTo(10f, spec)
              wait(50.ms)
              animateTo(-5f, snap())
              animateToTarget(spec)
            }
            .createAnimator(0f, 0f, FloatTypeConverter)
    assertThat(animator.durationNanos).isEqualTo(250_000_000L)
    for ((time, value) in
        listOf(
            200.ms to -2.5f,
            50.ms to 5f,
            100.ms to 10f,
            149.ms to 10f,
            150.ms to -5f,
            0.ms to 0f,
        )) {
      assertThat(animator.getValue(time.inWholeNanoseconds)).isEqualTo(value)
    }
    val velocity = FloatTypeConverter.newVector()
    animator.getVelocity(100_000_000L, velocity)
    assertThat(velocity[0]).isZero()
    animator.getVelocity(200_000_000L, velocity)
    assertThat(velocity[0]).isBetween(49.99f, 50.01f)
    assertBoundary(animator, 0f)
    assertThat(bindings).isEqualTo(2)
    assertBoundary(
        chain<Float> {
              animateTo(10f, snap())
              animateToTarget(snap())
            }
            .createAnimator(0f, 0f, FloatTypeConverter),
        0f,
    )
  }

  @Test
  fun firstSpringInheritsEachVelocityAndLaterLegsStartAtRest() {
    val spec = spring<IntSize>(visibilityThreshold = IntSize(1, 1))
    val velocity =
        IntSizeTypeConverter.newVector().apply {
          this[0] = 70f
          this[1] = -30f
        }
    val initial = IntSize(0, 0)
    val waypoint = IntSize(10, 20)
    val target = IntSize(30, -10)
    val first = spec.createAnimator(initial, waypoint, IntSizeTypeConverter, velocity)
    val second = spec.createAnimator(waypoint, target, IntSizeTypeConverter)
    val animator =
        chain<IntSize> {
              animateTo(waypoint, spec)
              wait(50.ms)
              animateToTarget(spec)
            }
            .createAnimator(initial, target, IntSizeTypeConverter, velocity)
    assertThat(velocity[0]).isEqualTo(70f)
    assertThat(velocity[1]).isEqualTo(-30f)
    animator.getVelocity(0L, velocity)
    assertThat(velocity[0]).isEqualTo(70f)
    assertThat(velocity[1]).isEqualTo(-30f)
    val secondStart = first.durationNanos + 50_000_000L
    assertThat(animator.durationNanos).isEqualTo(secondStart + second.durationNanos)
    assertThat(animator.getValue(first.durationNanos)).isEqualTo(waypoint)
    animator.getVelocity(secondStart, velocity)
    assertThat(velocity[0]).isZero()
    assertThat(velocity[1]).isZero()
    assertThat(animator.getValue(secondStart + second.durationNanos / 2))
        .isEqualTo(second.getValue(second.durationNanos / 2))
  }

  @Test
  fun reverseRepeatsKeepTheirNaturalEndpointThroughComposition() {
    val reverse = repeatable(2, tween<Float>(100.ms, Easing.Linear), RepeatMode.Reverse)
    assertBoundary(reverse.delayed(50.ms).createAnimator(0f, 10f, FloatTypeConverter), 0f)
    val animator =
        chain<Float> {
              animateTo(10f, reverse)
              animateToTarget(tween(100.ms, Easing.Linear))
            }
            .createAnimator(0f, 20f, FloatTypeConverter)
    assertThat(animator.durationNanos).isEqualTo(300_000_000L)
    assertThat(animator.getValue(200_000_000L)).isZero()
    assertThat(animator.getValue(250_000_000L)).isEqualTo(10f)
    assertBoundary(animator, 20f)
    val finalReverse =
        chain<Float> {
              animateTo(5f, snap())
              animateToTarget(reverse)
            }
            .createAnimator(0f, 10f, FloatTypeConverter)
    assertBoundary(finalReverse, 5f)
  }

  @Test
  fun compositionsHaveStructuralEqualityAndPreserveContextThresholds() {
    fun recipe(first: FiniteAnimationSpec<Float>) =
        chain<Float> {
              animateTo(0.8f, first)
              wait(40.ms)
              animateToTarget(spring(visibilityThreshold = 0.2f))
            }
            .delayed(20.ms)
    assertThat(recipe(spring())).isEqualTo(recipe(spring()))
    assertThat(recipe(spring()).hashCode()).isEqualTo(recipe(spring()).hashCode())
    val actual = recipe(spring()).withDefaultVisibilityThreshold(0.001f)
    val expected = recipe(spring(visibilityThreshold = 0.001f))
    assertThat(actual).isEqualTo(expected)
    assertThat(actual.createAnimator(0f, 1f, FloatTypeConverter).durationNanos)
        .isEqualTo(expected.createAnimator(0f, 1f, FloatTypeConverter).durationNanos)
    val tween = tween<Float>(100.ms).delayed(20.ms)
    assertThat(tween.withDefaultVisibilityThreshold(0.001f)).isSameAs(tween)
  }

  @Test
  fun invalidDeclarationsAndUnrepresentableDurationsAreRejected() {
    for (duration in listOf((-1).ms, Duration.INFINITE)) {
      assertThatThrownBy { spring<Float>().delayed(duration) }
          .isInstanceOf(IllegalArgumentException::class.java)
      assertThatThrownBy {
            chain<Float> {
              wait(duration)
              animateToTarget(snap())
            }
          }
          .isInstanceOf(IllegalArgumentException::class.java)
    }
    for (declaration in
        listOf<ChainBuilder<Float>.() -> Unit>(
            {},
            { animateTo(1f, snap()) },
            {
              animateToTarget(snap())
              wait(1.ms)
            },
            {
              animateToTarget(snap())
              animateToTarget(snap())
            },
        )) {
      assertThatThrownBy { chain(declaration) }.isInstanceOf(IllegalArgumentException::class.java)
    }
    val largest = Long.MAX_VALUE.nanoseconds
    assertThatThrownBy { tween<Float>(largest).delayed(1.ms) }
        .isInstanceOf(IllegalArgumentException::class.java)
    assertThatThrownBy {
          chain<Float> {
            wait(largest)
            wait(1.ms)
            animateToTarget(snap())
          }
        }
        .isInstanceOf(IllegalArgumentException::class.java)
    // Durations this large keep millisecond precision, so exceed half by a whole millisecond.
    val half = (Long.MAX_VALUE / 2).nanoseconds + 1.ms
    val longTween = tween<Float>(half)
    val knownChain =
        chain<Float> {
          animateTo(5f, longTween)
          animateToTarget(tween(1.ms))
        }
    val exactDelay =
        snap<Float>()
            .delayed(largest)
            .delayed((Long.MAX_VALUE - largest.inWholeNanoseconds).nanoseconds)
    for (declaration in
        listOf<ChainBuilder<Float>.() -> Unit>(
            {
              wait(half)
              animateToTarget(longTween)
            },
            {
              wait(half)
              animateTo(1f, longTween)
              animateToTarget(snap())
            },
            {
              animateTo(1f, longTween)
              animateToTarget(longTween)
            },
            {
              animateTo(4f, longTween)
              animateToTarget(knownChain)
            },
            {
              animateTo(1f, repeatable(1, longTween))
              animateToTarget(longTween)
            },
            {
              animateTo(1f, fixedDurationSpec(0L).delayed(half))
              animateToTarget(longTween)
            },
            {
              animateTo(1f, exactDelay)
              wait(1.nanoseconds)
              animateToTarget(snap())
            },
        )) {
      assertThatThrownBy { chain(declaration) }.isInstanceOf(IllegalArgumentException::class.java)
    }
    assertThatThrownBy { knownChain.delayed(half) }
        .isInstanceOf(IllegalArgumentException::class.java)
    // The repeated cycle keeps every nanosecond of the combined delays.
    assertThat(repeatable(1, exactDelay).createAnimator(0f, 1f, FloatTypeConverter).durationNanos)
        .isEqualTo(Long.MAX_VALUE)
    val infinite = fixedDurationSpec(-1L)
    assertThatThrownBy { infinite.delayed(1.ms).createAnimator(0f, 1f, FloatTypeConverter) }
        .isInstanceOf(IllegalArgumentException::class.java)
    assertThatThrownBy {
          chain<Float> {
                animateTo(1f, infinite)
                animateToTarget(tween(1.ms))
              }
              .createAnimator(0f, 2f, FloatTypeConverter)
        }
        .isInstanceOf(IllegalArgumentException::class.java)
  }

  @Test
  fun legsThatDoNotFitFinishAtTheirEndpointAndKeepOtherTiming() {
    val millis = 1.ms.inWholeNanoseconds
    val longest = fixedDurationSpec(Long.MAX_VALUE)
    val fitsExactly =
        chain<Float> {
              animateTo(5f, longest)
              animateToTarget(snap())
            }
            .createAnimator(0f, 1f, FloatTypeConverter)
    assertThat(fitsExactly.durationNanos).isEqualTo(Long.MAX_VALUE)

    for ((spec, finalValue) in
        listOf(longest to 1f, fixedDurationSpec(Long.MAX_VALUE, endAtInitial = true) to 0f)) {
      val delayed = spec.delayed(1.ms).createAnimator(0f, 1f, FloatTypeConverter)
      assertThat(delayed.durationNanos).isEqualTo(millis)
      assertThat(delayed.getValue(millis - 1)).isZero()
      assertBoundary(delayed, finalValue)
    }

    val collapsedMiddle =
        chain<Float> {
              animateTo(4f, tween(1.ms, Easing.Linear))
              animateTo(5f, longest)
              animateToTarget(tween(1.ms, Easing.Linear))
            }
            .createAnimator(0f, 2f, FloatTypeConverter)
    assertThat(collapsedMiddle.durationNanos).isEqualTo(2 * millis)
    assertThat(collapsedMiddle.getValue(millis / 2)).isEqualTo(2f)
    assertThat(collapsedMiddle.getValue(millis)).isEqualTo(5f)
    assertThat(collapsedMiddle.getValue(millis * 3 / 2)).isEqualTo(3.5f)
    assertBoundary(collapsedMiddle, 2f)

    val nestedLeg =
        chain<Float> {
          animateTo(5f, longest)
          animateToTarget(tween(1.ms, Easing.Linear))
        }
    val collapsedNested =
        chain<Float> {
              animateTo(4f, tween(1.ms, Easing.Linear))
              animateToTarget(nestedLeg)
            }
            .createAnimator(0f, 2f, FloatTypeConverter)
    assertThat(collapsedNested.durationNanos).isEqualTo(millis)
    assertThat(collapsedNested.getValue(millis / 2)).isEqualTo(2f)
    assertBoundary(collapsedNested, 2f)
  }

  // Delegates only FiniteAnimationSpec, so it declares no duration: overflow surfaces at binding.
  private fun fixedDurationSpec(
      duration: Long,
      endAtInitial: Boolean = false,
  ): FiniteAnimationSpec<Float> =
      object : FiniteAnimationSpec<Float> by snap<Float>() {
        override fun createAnimator(
            initialValue: Float,
            targetValue: Float,
            typeConverter: TypeConverter<Float>,
            initialVelocity: AnimationVector?,
        ): Animator<Float> =
            object :
                Animator<Float> by FinishedAnimator(
                    if (endAtInitial) initialValue else targetValue,
                ) {
              override val durationNanos: Long = duration
            }
      }

  private fun assertBoundary(animator: Animator<Float>, finalValue: Float) {
    val velocity = FloatTypeConverter.newVector()
    for (time in longArrayOf(animator.durationNanos, Long.MAX_VALUE)) {
      assertThat(animator.isFinished(time)).isTrue()
      assertThat(animator.getValue(time)).isEqualTo(finalValue)
      velocity[0] = 123f
      animator.getVelocity(time, velocity)
      assertThat(velocity[0]).isZero()
    }
  }
}
