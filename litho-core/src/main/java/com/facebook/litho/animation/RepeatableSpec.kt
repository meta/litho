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

/**
 * Creates a [FiniteAnimationSpec] that repeats [animation] for a fixed number of [iterations].
 *
 * ```kotlin
 * repeatable<Float>(iterations = 3, animation = tween(300.ms))
 * repeatable<Float>(iterations = 2, animation = tween(500.ms), repeatMode = RepeatMode.Reverse)
 * ```
 *
 * @param iterations Number of times to repeat. Must be positive.
 * @param animation The duration-based animation to repeat (springs are not repeatable — a cycle
 *   needs a known length).
 * @param repeatMode Whether to restart or reverse on each iteration.
 * @param initialStartOffset A one-time shift of the start time (delay or fast-forward) applied to
 *   the first cycle only.
 */
fun <T> repeatable(
    iterations: Int,
    animation: DurationBasedAnimationSpec<T>,
    repeatMode: RepeatMode = RepeatMode.Restart,
    initialStartOffset: StartOffset = StartOffset(0),
): FiniteAnimationSpec<T> = RepeatableSpec(iterations, animation, repeatMode, initialStartOffset)

/**
 * Creates an [InfiniteAnimationSpec] that repeats [animation] for the lifetime of its owner.
 * Because it never settles, it can only be consumed by `useInfiniteAnimation`, not by
 * target-settling APIs.
 *
 * ```kotlin
 * infiniteRepeatable<Float>(animation = tween(1000.ms), repeatMode = RepeatMode.Reverse)
 * ```
 *
 * @param animation The duration-based animation to repeat. Its cycle must have a positive duration
 *   (a zero-length cycle such as `snap()` would loop with no progress, so it is rejected).
 * @param repeatMode Whether to restart or reverse on each iteration.
 * @param initialStartOffset A one-time shift of the start time (delay or fast-forward) applied to
 *   the first cycle only — use [StartOffsetType.FastForward] to stagger several loops.
 */
fun <T> infiniteRepeatable(
    animation: DurationBasedAnimationSpec<T>,
    repeatMode: RepeatMode = RepeatMode.Restart,
    initialStartOffset: StartOffset = StartOffset(0),
): InfiniteAnimationSpec<T> = InfiniteRepeatableSpec(animation, repeatMode, initialStartOffset)

/** Defines how an animation repeats when it reaches the end of an iteration. */
enum class RepeatMode {
  /** Restarts from the initial value at the beginning of each iteration. */
  Restart,

  /** Reverses direction at the end of each iteration, alternating between initial and target. */
  Reverse,
}

private const val INFINITE = -1

/**
 * A [FiniteAnimationSpec] that repeats [animation] for [iterations] cycles.
 *
 * @see repeatable
 */
private data class RepeatableSpec<T>(
    val iterations: Int,
    val animation: DurationBasedAnimationSpec<T>,
    val repeatMode: RepeatMode,
    val initialStartOffset: StartOffset,
) : FiniteAnimationSpec<T> {

  private val durationNanos: Long = run {
    require(iterations > 0) { "iterations must be positive, but was $iterations" }
    val cycleDurationNanos = animation.totalDurationNanos
    require(cycleDurationNanos >= 0L) {
      "Repeated cycles need a nonnegative totalDurationNanos, but was $cycleDurationNanos."
    }
    require(cycleDurationNanos <= Long.MAX_VALUE / iterations) {
      "Repeated cycles must fit in Long nanoseconds."
    }
    val cyclesDuration = cycleDurationNanos * iterations
    val startShiftNanos = initialStartOffset.signedNanos
    require(startShiftNanos >= 0L || cyclesDuration <= Long.MAX_VALUE + startShiftNanos) {
      "Repeat duration and start delay must fit in Long nanoseconds."
    }
    maxOf(0L, cyclesDuration - startShiftNanos)
  }

  override fun createAnimator(
      initialValue: T,
      targetValue: T,
      typeConverter: TypeConverter<T>,
      initialVelocity: AnimationVector?,
  ): Animator<T> = repeatableAnimator(
      animation,
      repeatMode,
      iterations,
      initialStartOffset,
      durationNanos,
      initialValue,
      targetValue,
      typeConverter,
      initialVelocity,
  )
}

/**
 * An [InfiniteAnimationSpec] that repeats [animation] forever.
 *
 * @see infiniteRepeatable
 */
private data class InfiniteRepeatableSpec<T>(
    val animation: DurationBasedAnimationSpec<T>,
    val repeatMode: RepeatMode,
    val initialStartOffset: StartOffset,
) : InfiniteAnimationSpec<T> {

  init {
    require(animation.totalDurationNanos > 0L) {
      "infiniteRepeatable requires a positive cycle duration, but the animation's " +
          "totalDurationNanos is ${animation.totalDurationNanos}. A zero-length cycle " +
          "(e.g. snap() or a zero-length tween) would loop forever with no progress."
    }
  }

  override fun createAnimator(
      initialValue: T,
      targetValue: T,
      typeConverter: TypeConverter<T>,
      initialVelocity: AnimationVector?,
  ): Animator<T> = repeatableAnimator(
      animation,
      repeatMode,
      INFINITE,
      initialStartOffset,
      Animator.DURATION_INFINITE,
      initialValue,
      targetValue,
      typeConverter,
      initialVelocity,
  )
}

private fun <T> repeatableAnimator(
    animation: DurationBasedAnimationSpec<T>,
    repeatMode: RepeatMode,
    iterations: Int,
    startOffset: StartOffset,
    durationNanos: Long,
    initialValue: T,
    targetValue: T,
    typeConverter: TypeConverter<T>,
    initialVelocity: AnimationVector?,
): Animator<T> {
  val inner = animation.createAnimator(initialValue, targetValue, typeConverter, initialVelocity)
  val reverse =
      if (repeatMode == RepeatMode.Reverse) {
        animation.createAnimator(targetValue, initialValue, typeConverter, null)
      } else {
        null
      }
  return RepeatableAnimator(
      innerAnimator = inner,
      reverseAnimator = reverse,
      iterations = iterations,
      cycleDurationNanos = animation.totalDurationNanos,
      startShiftNanos = startOffset.signedNanos,
      durationNanos = durationNanos,
      initialValue = initialValue,
      targetValue = targetValue,
  )
}

private class RepeatableAnimator<T>(
    private val innerAnimator: Animator<T>,
    private val reverseAnimator: Animator<T>?,
    private val iterations: Int,
    private val cycleDurationNanos: Long,
    private val startShiftNanos: Long,
    override val durationNanos: Long,
    private val initialValue: T,
    private val targetValue: T,
) : Animator<T> {

  override fun getValue(playTimeNanos: Long): T {
    if (isFinished(playTimeNanos)) {
      return if (cycleDurationNanos > 0L && iterations % 2 == 0 && reverseAnimator != null)
          initialValue
      else targetValue
    }
    val shifted = playTimeNanos + startShiftNanos
    // Delay offset: hold at the start value until the shifted play time reaches the first cycle.
    if (shifted < 0L) return initialValue
    if (cycleDurationNanos <= 0L) return targetValue

    val iteration = shifted / cycleDurationNanos
    val localTime = shifted % cycleDurationNanos
    val isReversed = reverseAnimator != null && iteration % 2 == 1L
    return (if (isReversed) reverseAnimator else innerAnimator).getValue(localTime)
  }

  override fun getVelocity(playTimeNanos: Long, velocity: AnimationVector) {
    if (isFinished(playTimeNanos)) {
      velocity.reset()
      return
    }
    val shifted = playTimeNanos + startShiftNanos
    if (shifted < 0L || cycleDurationNanos <= 0L) {
      velocity.reset()
      return
    }

    val iteration = shifted / cycleDurationNanos
    val localTime = shifted % cycleDurationNanos
    val isReversed = reverseAnimator != null && iteration % 2 == 1L
    (if (isReversed) reverseAnimator else innerAnimator).getVelocity(localTime, velocity)
  }
}
