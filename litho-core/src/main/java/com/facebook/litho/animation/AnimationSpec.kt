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
 * An immutable configuration that defines how an animation should behave.
 *
 * [AnimationSpec] is a lightweight, shareable description of animation parameters — spring
 * stiffness, tween duration, easing curve, etc. It does not hold animation state. When an animation
 * needs to run, [createAnimator] produces an [Animator] bound to specific initial conditions.
 *
 * ## Spec vs Animator
 * - **[AnimationSpec]** = recipe (what kind of animation, reusable)
 * - **[Animator]** = computation engine (bound to specific from/to values, time-driven)
 *
 * ## Built-in Specs
 *
 * ```kotlin
 * spring<Float>(stiffness = Stiffness.Low, dampingRatio = DampingRatio.MediumBouncy)
 * tween<Float>(duration = 300.ms, easing = Easing.FastOutSlowIn)
 * keyframes<Float>(duration = 500.ms) { ... }
 * snap<Float>()
 * repeatable<Float>(iterations = 3, animation = tween())
 * ```
 *
 * ## Type Safety
 *
 * [AnimationSpec] is parameterized by the value type [T]. The [TypeConverter] is provided at
 * animator creation time — not at spec construction time — so a single spec like `spring<Float>()`
 * is both lightweight and type-safe without needing to carry a converter reference.
 *
 * ## Interruption
 *
 * When an animation is interrupted mid-flight, the current per-component velocity can be captured
 * from the running [Animator] and passed as [initialVelocity] to a new animator. This enables
 * smooth retargeting without velocity discontinuities.
 *
 * ```kotlin
 * // Interruption with per-component velocity preservation
 * val velocity = FloatTypeConverter.newVector()
 * runningAnimator.getVelocity(elapsedNanos, velocity)
 * val newAnimator = spec.createAnimator(
 *     initialValue = currentValue,
 *     targetValue = newTarget,
 *     typeConverter = FloatTypeConverter,
 *     initialVelocity = velocity,
 * )
 * ```
 *
 * @param T The type of value this spec animates.
 * @see Animator
 * @see TypeConverter
 */
interface AnimationSpec<T> {

  /**
   * Creates an [Animator] configured with the given initial conditions.
   *
   * The returned animator is a stateless computation engine that can be queried at any elapsed time
   * to produce the animated value and velocity.
   *
   * @param initialValue The value at the start of the animation.
   * @param targetValue The value at the end of the animation.
   * @param typeConverter Converts between [T] and [AnimationVector] for internal computation.
   * @param initialVelocity A read-only snapshot of the starting per-component velocity in
   *   float-space units per second, shaped by [typeConverter]. Defaults to `null` (at rest).
   *   Non-null values are primarily useful for spring animations during interruption, where the
   *   previous animation's per-component velocity carries over for smooth retargeting. Non-spring
   *   specs may ignore it.
   * @return An [Animator] that computes values for this animation over time.
   */
  fun createAnimator(
      initialValue: T,
      targetValue: T,
      typeConverter: TypeConverter<T>,
      initialVelocity: AnimationVector? = null,
  ): Animator<T>
}

/**
 * An [AnimationSpec] that completes in finite time: it eventually reaches its target and settles.
 *
 * Target-settling APIs ([useAnimatedValue], [useAnimationGroup], `Style.transition`, enter/exit
 * transitions, [SizeTransform]) require finite specs, because their completion-dependent behavior —
 * `onSettled`, retained-content unmount, converging to the resolved mount value — only works if the
 * animation actually finishes. Infinite specs are excluded by type ([InfiniteAnimationSpec] is not
 * a [FiniteAnimationSpec]). Created animators must report a nonnegative [Animator.durationNanos].
 */
interface FiniteAnimationSpec<T> : AnimationSpec<T>

/**
 * A [FiniteAnimationSpec] whose forward cycle has a known, fixed duration. Repeating a cycle is
 * only possible when its length is known up front, so [repeatable] / [infiniteRepeatable] accept
 * only duration-based specs (springs, which have no fixed cycle, are excluded).
 */
interface DurationBasedAnimationSpec<T> : FiniteAnimationSpec<T> {
  /**
   * Playback nanoseconds of one forward cycle, including any initial delay baked into the spec.
   * Nonnegative, and equal to the [Animator.durationNanos] of the animators this spec creates.
   */
  val totalDurationNanos: Long
}

/**
 * An [AnimationSpec] that never settles — it loops for the lifetime of its owner. Produced by
 * [infiniteRepeatable] and consumed by `useInfiniteAnimation`. Deliberately NOT a
 * [FiniteAnimationSpec], so the type system keeps it out of target-settling APIs that depend on
 * completion.
 */
interface InfiniteAnimationSpec<T> : AnimationSpec<T>

internal fun Duration.toFiniteNanos(): Long {
  require(isFinite() && this >= Duration.ZERO && this <= Long.MAX_VALUE.nanoseconds) {
    "Animation duration must be finite, nonnegative, and fit in Long nanoseconds."
  }
  return inWholeNanoseconds
}
