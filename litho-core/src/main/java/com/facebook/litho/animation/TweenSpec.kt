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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO

/**
 * Creates a [TweenSpec] for time-based animations with an easing curve.
 *
 * ```kotlin
 * tween<Float>(duration = 300.ms, easing = Easing.EaseInOut)
 * tween<Float>(duration = 500.ms, easing = Easing.FastOutSlowIn, delay = 100.ms)
 * ```
 *
 * @param duration Total animation duration. Defaults to 300ms.
 * @param easing The easing curve controlling the rate of change. Defaults to
 *   [Easing.FastOutSlowIn].
 * @param delay Time to wait before starting the animation. Defaults to zero.
 */
fun <T> tween(
    duration: Duration = 300.ms,
    easing: Easing = Easing.FastOutSlowIn,
    delay: Duration = ZERO,
): DurationBasedAnimationSpec<T> = TweenSpec(duration, easing, delay)

/**
 * A time-based [AnimationSpec] that interpolates between values over a fixed duration with an
 * easing curve. All components share the single eased fraction, so a multi-component value stays
 * coordinated across the tween.
 *
 * Use the [tween] builder function to create instances:
 * ```kotlin
 * val spec = tween<Float>(duration = 300.ms, easing = Easing.FastOutSlowIn)
 * ```
 *
 * @see tween
 * @see Easing
 */
private data class TweenSpec<T>(
    val duration: Duration = 300.ms,
    val easing: Easing = Easing.FastOutSlowIn,
    val delay: Duration = ZERO,
) : DurationBasedAnimationSpec<T> {

  override val totalDurationNanos: Long = run {
    val durationNanos = duration.toFiniteNanos()
    val delayNanos = delay.toFiniteNanos()
    require(delayNanos <= Long.MAX_VALUE - durationNanos) {
      "Tween duration and delay must fit in Long nanoseconds."
    }
    durationNanos + delayNanos
  }

  override fun createAnimator(
      initialValue: T,
      targetValue: T,
      typeConverter: TypeConverter<T>,
      initialVelocity: AnimationVector?,
  ): Animator<T> = TweenAnimator(
      initialValue = initialValue,
      targetValue = targetValue,
      typeConverter = typeConverter,
      animationDurationNanos = duration.inWholeNanoseconds,
      delayNanos = delay.inWholeNanoseconds,
      easing = easing,
  )
}

private class TweenAnimator<T>(
    private val initialValue: T,
    private val targetValue: T,
    private val typeConverter: TypeConverter<T>,
    private val animationDurationNanos: Long,
    private val delayNanos: Long,
    private val easing: Easing,
) : Animator<T> {

  override val durationNanos: Long = animationDurationNanos + delayNanos

  // Endpoints are fixed for the animator's lifetime, so vectorize them once; only [valueVector] is
  // written per frame, keeping the tick allocation-free apart from the reconstructed value.
  private val initialVector = typeConverter.newVectorFromValue(initialValue)
  private val targetVector = typeConverter.newVectorFromValue(targetValue)
  private val valueVector = typeConverter.newVector()

  override fun getValue(playTimeNanos: Long): T {
    if (playTimeNanos >= durationNanos) return targetValue
    if (playTimeNanos < delayNanos) return initialValue
    val fraction = easing.transform(rawFraction(playTimeNanos))
    for (i in 0 until valueVector.size) {
      valueVector[i] = lerp(initialVector[i], targetVector[i], fraction)
    }
    return typeConverter.fromVector(valueVector)
  }

  override fun getVelocity(playTimeNanos: Long, velocity: AnimationVector) {
    if (
        playTimeNanos >= durationNanos || playTimeNanos < delayNanos || animationDurationNanos <= 0
    ) {
      velocity.reset()
      return
    }
    // Per-component velocity via finite difference over the shared eased fraction.
    val dt = 1_000_000L // 1ms
    val t2 = playTimeNanos + minOf(dt, durationNanos - playTimeNanos)
    val e1 = easing.transform(rawFraction(playTimeNanos))
    val e2 = easing.transform(rawFraction(t2))
    val dtSeconds = (t2 - playTimeNanos) / 1_000_000_000f
    for (i in 0 until velocity.size) {
      val span = targetVector[i] - initialVector[i]
      velocity[i] = if (dtSeconds > 0f) (span * (e2 - e1)) / dtSeconds else 0f
    }
  }

  private fun rawFraction(timeNanos: Long): Float {
    val elapsed = timeNanos - delayNanos
    return if (animationDurationNanos > 0)
        (elapsed.toFloat() / animationDurationNanos).coerceIn(0f, 1f)
    else 1f
  }
}
