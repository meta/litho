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
import kotlin.time.Duration.Companion.nanoseconds

/**
 * Holds the initial value before running this spec. A positive delay discards incoming velocity;
 * zero delay preserves this spec and its incoming velocity. The delay follows playback time.
 *
 * The delay plus this spec's fixed durations must fit in Long nanoseconds. If the bound animation
 * doesn't fit in what remains, it finishes at its end value when the delay ends.
 */
fun <T> FiniteAnimationSpec<T>.delayed(by: Duration): FiniteAnimationSpec<T> =
    delayedNanos(by.toFiniteNanos())

private fun <T> FiniteAnimationSpec<T>.delayedNanos(delayNanos: Long): FiniteAnimationSpec<T> {
  if (delayNanos == 0L) return this
  val animation: FiniteAnimationSpec<T>
  val combinedDelay: Long
  if (this is DelayedSpec<T>) {
    animation = this.animation
    combinedDelay = addDurations(this.delayNanos, delayNanos)
  } else {
    animation = this
    combinedDelay = delayNanos
  }
  return if (animation is DurationBasedAnimationSpec<T>) {
    DurationBasedDelayedSpec(animation, combinedDelay)
  } else {
    DelayedSpec(animation, combinedDelay)
  }
}

/**
 * Like [FiniteAnimationSpec.delayed], but preserves the [DurationBasedAnimationSpec] type of the
 * input.
 */
fun <T> DurationBasedAnimationSpec<T>.delayed(by: Duration): DurationBasedAnimationSpec<T> =
    (this as FiniteAnimationSpec<T>).delayed(by) as DurationBasedAnimationSpec<T>

/**
 * Runs animation legs and waits in order, ending with [ChainBuilder.animateToTarget].
 *
 * ```kotlin
 * chain<Float> {
 *   animateTo(1.2f, spring())
 *   wait(80.ms)
 *   animateToTarget(tween(200.ms))
 * }
 * ```
 *
 * Each leg starts from the previous leg's end value. Only the first leg receives incoming velocity,
 * so a chain that starts with [ChainBuilder.wait] discards it. The chain plays even when the start
 * and target values are equal.
 *
 * Fixed durations, including those of nested specs, must fit in Long nanoseconds in total. A leg
 * whose bound duration doesn't fit in what remains finishes at its end value; the other legs keep
 * their timing.
 */
fun <T> chain(block: ChainBuilder<T>.() -> Unit): FiniteAnimationSpec<T> =
    ChainBuilder<T>().apply(block).build()

/** Declares the legs of a [chain]. */
class ChainBuilder<T> internal constructor() {
  private val steps = mutableListOf<ChainStep<T>>()

  /** Animates toward the absolute [value] using [animation]. */
  fun animateTo(value: T, animation: FiniteAnimationSpec<T>) {
    checkCanAppend()
    steps.add(ChainStep.To(value, animation))
  }

  /** Holds the current value for [duration]. Zero duration has no effect. */
  fun wait(duration: Duration) {
    checkCanAppend()
    val nanos = duration.toFiniteNanos()
    if (nanos == 0L) return
    val previous = steps.lastOrNull()
    if (previous is ChainStep.Hold) {
      steps[steps.lastIndex] = ChainStep.Hold(addDurations(previous.durationNanos, nanos))
    } else {
      steps.add(ChainStep.Hold(nanos))
    }
  }

  /** Declares the final leg toward the target supplied when the chain runs. */
  fun animateToTarget(animation: FiniteAnimationSpec<T>) {
    checkCanAppend()
    steps.add(ChainStep.ToTarget(animation))
  }

  private fun checkCanAppend() {
    require(steps.lastOrNull() !is ChainStep.ToTarget) {
      "animateToTarget must be the last chain leg."
    }
  }

  internal fun build(): FiniteAnimationSpec<T> {
    val final = steps.lastOrNull()
    require(final is ChainStep.ToTarget<T>) { "A chain must end with animateToTarget." }
    if (steps.size == 1) return final.animation
    val first = steps.first()
    if (steps.size == 2 && first is ChainStep.Hold) {
      return final.animation.delayedNanos(first.durationNanos)
    }
    return ChainSpec(steps.toList())
  }
}

private open class DelayedSpec<T>(
    val animation: FiniteAnimationSpec<T>,
    val delayNanos: Long,
) : FiniteAnimationSpec<T>, DeclaredDurationSpec {
  final override val declaredDurationNanos: Long =
      addDurations(delayNanos, animation.declaredDurationNanos)

  override fun createAnimator(
      initialValue: T,
      targetValue: T,
      typeConverter: TypeConverter<T>,
      initialVelocity: AnimationVector?,
  ): Animator<T> {
    val inner =
        animation
            .createAnimator(initialValue, targetValue, typeConverter, null)
            .fitWithin(Long.MAX_VALUE - delayNanos)
    return DelayedAnimator(initialValue, inner, delayNanos, delayNanos + inner.durationNanos)
  }

  override fun equals(other: Any?): Boolean =
      other is DelayedSpec<*> && animation == other.animation && delayNanos == other.delayNanos

  override fun hashCode(): Int = 31 * animation.hashCode() + delayNanos.hashCode()

  override fun toString(): String =
      "DelayedSpec(animation=$animation, delay=${delayNanos.nanoseconds})"
}

private class DurationBasedDelayedSpec<T>(
    animation: DurationBasedAnimationSpec<T>,
    delayNanos: Long,
) : DelayedSpec<T>(animation, delayNanos), DurationBasedAnimationSpec<T> {
  override val totalDurationNanos: Long
    get() = declaredDurationNanos
}

private class DelayedAnimator<T>(
    private val initialValue: T,
    private val inner: Animator<T>,
    private val delayNanos: Long,
    override val durationNanos: Long,
) : Animator<T> {
  override fun getValue(playTimeNanos: Long): T =
      if (playTimeNanos < delayNanos) initialValue else inner.getValue(playTimeNanos - delayNanos)

  override fun getVelocity(playTimeNanos: Long, velocity: AnimationVector) {
    if (playTimeNanos < delayNanos || playTimeNanos >= durationNanos) velocity.reset()
    else inner.getVelocity(playTimeNanos - delayNanos, velocity)
  }
}

private sealed interface ChainStep<out T> {
  data class To<T>(val value: T, val animation: FiniteAnimationSpec<T>) : ChainStep<T>

  data class ToTarget<T>(val animation: FiniteAnimationSpec<T>) : ChainStep<T>

  data class Hold(val durationNanos: Long) : ChainStep<Nothing>
}

private data class ChainSpec<T>(val steps: List<ChainStep<T>>) :
    FiniteAnimationSpec<T>, DeclaredDurationSpec {
  override val declaredDurationNanos: Long = run {
    var duration = 0L
    for (i in steps.indices) {
      val next =
          when (val step = steps[i]) {
            is ChainStep.To -> step.animation.declaredDurationNanos
            is ChainStep.ToTarget -> step.animation.declaredDurationNanos
            is ChainStep.Hold -> step.durationNanos
          }
      duration = addDurations(duration, next)
    }
    duration
  }

  override fun createAnimator(
      initialValue: T,
      targetValue: T,
      typeConverter: TypeConverter<T>,
      initialVelocity: AnimationVector?,
  ): Animator<T> {
    val animators = ArrayList<Animator<T>>(steps.size)
    val ends = LongArray(steps.size)
    var value = initialValue
    var velocity = initialVelocity
    var duration = 0L
    for (i in steps.indices) {
      val animator =
          when (val step = steps[i]) {
            is ChainStep.To ->
                step.animation.createAnimator(value, step.value, typeConverter, velocity)
            is ChainStep.ToTarget ->
                step.animation.createAnimator(value, targetValue, typeConverter, velocity)
            is ChainStep.Hold -> HoldAnimator(value, step.durationNanos)
          }.fitWithin(Long.MAX_VALUE - duration)
      duration += animator.durationNanos
      value = animator.getValue(animator.durationNanos)
      velocity = null
      animators.add(animator)
      ends[i] = duration
    }
    return ChainAnimator(animators, ends, value)
  }
}

private class HoldAnimator<T>(
    private val value: T,
    override val durationNanos: Long,
) : Animator<T> {
  override fun getValue(playTimeNanos: Long): T = value

  override fun getVelocity(playTimeNanos: Long, velocity: AnimationVector) {
    velocity.reset()
  }
}

private class ChainAnimator<T>(
    private val animators: List<Animator<T>>,
    private val ends: LongArray,
    private val finalValue: T,
) : Animator<T> {
  override val durationNanos: Long
    get() = ends.last()

  override fun getValue(playTimeNanos: Long): T {
    if (playTimeNanos >= durationNanos) return finalValue
    val index = legAt(playTimeNanos)
    val start = if (index == 0) 0L else ends[index - 1]
    return animators[index].getValue(playTimeNanos - start)
  }

  override fun getVelocity(playTimeNanos: Long, velocity: AnimationVector) {
    if (playTimeNanos < 0L || playTimeNanos >= durationNanos) {
      velocity.reset()
      return
    }
    val index = legAt(playTimeNanos)
    val start = if (index == 0) 0L else ends[index - 1]
    animators[index].getVelocity(playTimeNanos - start, velocity)
  }

  private fun legAt(time: Long): Int {
    var low = 0
    var high = ends.lastIndex
    while (low < high) {
      val middle = low + (high - low) / 2
      if (time < ends[middle]) high = middle else low = middle + 1
    }
    return low
  }
}

/**
 * A spec whose declaration states part or all of its playback duration. Parts known only at binding
 * count as zero, so this is not the bound animator's duration. Declarations whose stated durations
 * exceed Long nanoseconds are rejected at construction.
 */
internal interface DeclaredDurationSpec {
  val declaredDurationNanos: Long
}

internal val FiniteAnimationSpec<*>.declaredDurationNanos: Long
  get() =
      when (this) {
        is DeclaredDurationSpec -> declaredDurationNanos
        is DurationBasedAnimationSpec<*> -> totalDurationNanos
        else -> 0L
      }

private fun addDurations(first: Long, second: Long): Long {
  require(second >= 0L) { "Declared animation durations must be nonnegative." }
  require(second <= Long.MAX_VALUE - first) {
    "Declared animation durations must fit in Long nanoseconds."
  }
  return first + second
}

private fun <T> Animator<T>.fitWithin(remainingNanos: Long): Animator<T> {
  val duration = durationNanos
  require(duration >= 0L) { "FiniteAnimationSpec must create an animator with a finite duration." }
  if (duration <= remainingNanos) return this
  return FinishedAnimator(getValue(duration))
}

internal fun FiniteAnimationSpec<Float>.withCompositionVisibilityThreshold(
    threshold: Float,
): FiniteAnimationSpec<Float> =
    when (this) {
      is DelayedSpec<Float> -> {
        val inner = animation.withDefaultVisibilityThreshold(threshold)
        if (inner === animation) this else inner.delayedNanos(delayNanos)
      }
      is ChainSpec<Float> -> {
        var updated: MutableList<ChainStep<Float>>? = null
        for (i in steps.indices) {
          val step = steps[i]
          val next = step.withDefaultVisibilityThreshold(threshold)
          if (next === step) continue
          if (updated == null) updated = steps.toMutableList()
          updated[i] = next
        }
        if (updated == null) this else ChainSpec(updated)
      }
      else -> this
    }

private fun ChainStep<Float>.withDefaultVisibilityThreshold(threshold: Float): ChainStep<Float> =
    when (this) {
      is ChainStep.To -> {
        val inner = animation.withDefaultVisibilityThreshold(threshold)
        if (inner === animation) this else copy(animation = inner)
      }
      is ChainStep.ToTarget -> {
        val inner = animation.withDefaultVisibilityThreshold(threshold)
        if (inner === animation) this else copy(animation = inner)
      }
      is ChainStep.Hold -> this
    }
