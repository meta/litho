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
 * Creates a [SnapSpec] that instantly jumps to the target value.
 *
 * ```kotlin
 * val spec = snap<Float>()
 * ```
 */
fun <T> snap(): DurationBasedAnimationSpec<T> = SnapSpec()

/**
 * An [AnimationSpec] that instantly jumps to the target value with no animation.
 *
 * Use the [snap] builder function to create instances:
 * ```kotlin
 * val spec = snap<Float>()
 * ```
 *
 * @see snap
 */
private class SnapSpec<T> : DurationBasedAnimationSpec<T> {

  override val totalDurationNanos: Long = 0L

  override fun createAnimator(
      initialValue: T,
      targetValue: T,
      typeConverter: TypeConverter<T>,
      initialVelocity: AnimationVector?,
  ): Animator<T> = FinishedAnimator(targetValue)

  override fun equals(other: Any?): Boolean = other is SnapSpec<*>

  override fun hashCode(): Int = SnapSpec::class.hashCode()

  override fun toString(): String = "SnapSpec"
}
