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

import kotlin.time.Duration

/**
 * Creates a [KeyframesSpec] with the given duration and keyframe definitions.
 *
 * ```kotlin
 * keyframes<Float>(duration = 500.ms) {
 *     0f at 0.ms using Easing.Linear
 *     0.8f at 200.ms using Easing.FastOutSlowIn
 *     1f at 500.ms
 * }
 * ```
 *
 * @param duration The total duration of the keyframes animation.
 * @param block Builder block for defining keyframes.
 */
fun <T> keyframes(
    duration: Duration,
    block: KeyframesBuilder<T>.() -> Unit,
): DurationBasedAnimationSpec<T> {
  val builder = KeyframesBuilder<T>(duration)
  builder.block()
  return builder.build()
}

/**
 * Builder for defining keyframes in a [KeyframesSpec].
 *
 * Keyframes are defined using the [at] infix function, with optional [using] for per-segment
 * easing.
 */
class KeyframesBuilder<T> internal constructor(private val duration: Duration) {

  private val keyframes = mutableListOf<Keyframe<T>>()

  /**
   * Adds a keyframe at the given timestamp.
   *
   * ```kotlin
   * 0.5f at 200.ms
   * 0.5f at 200.ms using Easing.EaseIn
   * ```
   */
  infix fun T.at(timeStamp: Duration): KeyframeHandle<T> {
    val keyframe = Keyframe(timeStamp.inWholeNanoseconds, this, Easing.Linear)
    keyframes.add(keyframe)
    return KeyframeHandle(keyframes, keyframes.lastIndex)
  }

  internal fun build(): DurationBasedAnimationSpec<T> {
    val sorted = keyframes.sortedBy { it.timeNanos }
    return KeyframesSpec(duration, sorted)
  }
}

/** Handle returned by [KeyframesBuilder.at] to allow chaining [using] for per-segment easing. */
class KeyframeHandle<T>
internal constructor(
    private val keyframes: MutableList<Keyframe<T>>,
    private val index: Int,
) {
  /** Sets the easing curve for the segment leading into this keyframe. */
  infix fun using(easing: Easing) {
    keyframes[index] = keyframes[index].copy(easing = easing)
  }
}

/**
 * An [AnimationSpec] that interpolates through a sequence of keyframe values over a fixed duration.
 *
 * Each keyframe specifies a value at a point in time, with optional per-segment easing. Between
 * keyframes, values are interpolated using the segment's easing curve.
 *
 * Use the [keyframes] builder function to create instances:
 * ```kotlin
 * val spec = keyframes<Float>(duration = 500.ms) {
 *     0f at 0.ms using Easing.Linear
 *     0.5f at 150.ms using Easing.EaseIn
 *     1f at 500.ms
 * }
 * ```
 *
 * @see keyframes
 * @see KeyframesBuilder
 */
private data class KeyframesSpec<T>(
    val duration: Duration,
    internal val keyframes: List<Keyframe<T>>,
) : DurationBasedAnimationSpec<T> {

  override val totalDurationNanos: Long = duration.toFiniteNanos()

  override fun createAnimator(
      initialValue: T,
      targetValue: T,
      typeConverter: TypeConverter<T>,
      initialVelocity: AnimationVector?,
  ): Animator<T> {
    val durationNanos = duration.inWholeNanoseconds
    val resolvedKeyframes = buildList {
      // Ensure initial value at t=0 if not specified
      if (keyframes.isEmpty() || keyframes.first().timeNanos > 0) {
        add(Keyframe(0L, initialValue, Easing.Linear))
      }
      addAll(keyframes)
      // Ensure target value at duration if not specified
      if (isEmpty() || last().timeNanos < durationNanos) {
        add(Keyframe(durationNanos, targetValue, Easing.Linear))
      }
    }
    return KeyframesAnimator(
        targetValue,
        typeConverter,
        durationNanos,
        resolvedKeyframes,
    )
  }

  override fun toString(): String = "KeyframesSpec(duration=$duration, keyframes=${keyframes.size})"
}

/** A single keyframe: a value at a specific time with an easing curve for the preceding segment. */
internal data class Keyframe<T>(val timeNanos: Long, val value: T, val easing: Easing)

private class KeyframesAnimator<T>(
    private val targetValue: T,
    private val typeConverter: TypeConverter<T>,
    override val durationNanos: Long,
    private val keyframes: List<Keyframe<T>>,
) : Animator<T> {

  // Reused each frame; the surrounding keyframes are vectorized into [fromVector]/[toVector] per
  // segment and interpolated into [valueVector], so a tick allocates nothing but the value.
  private val fromVector = typeConverter.newVector()
  private val toVector = typeConverter.newVector()
  private val valueVector = typeConverter.newVector()

  override fun getValue(playTimeNanos: Long): T {
    if (playTimeNanos >= durationNanos) return targetValue
    if (playTimeNanos <= 0) return keyframes.first().value

    getValueVector(playTimeNanos, valueVector)
    return typeConverter.fromVector(valueVector)
  }

  override fun getVelocity(playTimeNanos: Long, velocity: AnimationVector) {
    if (playTimeNanos >= durationNanos || playTimeNanos < 0) {
      velocity.reset()
      return
    }
    val dt = 1_000_000L // 1ms
    val t2 = playTimeNanos + minOf(dt, durationNanos - playTimeNanos)
    getValueVector(playTimeNanos, velocity)
    getValueVector(t2, valueVector)
    val dtSeconds = (t2 - playTimeNanos) / 1_000_000_000f
    for (i in 0 until velocity.size) {
      velocity[i] = if (dtSeconds > 0f) (valueVector[i] - velocity[i]) / dtSeconds else 0f
    }
  }

  private fun getValueVector(playTimeNanos: Long, out: AnimationVector) {
    if (playTimeNanos >= durationNanos) {
      typeConverter.toVector(targetValue, out)
      return
    }
    if (playTimeNanos <= 0) {
      typeConverter.toVector(keyframes.first().value, out)
      return
    }

    val nextIndex = keyframes.indexOfFirst { it.timeNanos > playTimeNanos }
    if (nextIndex <= 0) {
      typeConverter.toVector(keyframes.first().value, out)
      return
    }

    val prev = keyframes[nextIndex - 1]
    val next = keyframes[nextIndex]
    val segmentDuration = next.timeNanos - prev.timeNanos
    if (segmentDuration <= 0) {
      typeConverter.toVector(next.value, out)
      return
    }

    val segmentElapsed = playTimeNanos - prev.timeNanos
    val rawFraction = segmentElapsed.toFloat() / segmentDuration
    val fraction = next.easing.transform(rawFraction.coerceIn(0f, 1f))
    typeConverter.toVector(prev.value, fromVector)
    typeConverter.toVector(next.value, toVector)
    for (i in 0 until out.size) {
      out[i] = lerp(fromVector[i], toVector[i], fraction)
    }
  }
}
