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
 * Creates a [SpringSpec] for physics-based spring animations.
 *
 * ```kotlin
 * // Default spring (medium stiffness, no bounce)
 * spring<Float>()
 *
 * // Bouncy spring
 * spring<Float>(stiffness = Stiffness.Low, dampingRatio = DampingRatio.HighBouncy)
 * ```
 *
 * The animation finishes immediately at the supplied target if endpoints, displacement, or velocity
 * are non-finite, or if its settling duration cannot fit in Long nanoseconds.
 *
 * @param stiffness Controls spring responsiveness. Higher = faster, snappier.
 * @param dampingRatio Controls bounce. Lower = more overshoot. 1.0 = no overshoot.
 * @param visibilityThreshold Optional positive, finite tolerance in the animated value's units.
 *   Smaller values preserve more motion and can delay completion. Null uses the default.
 */
fun <T> spring(
    stiffness: Stiffness = Stiffness.Medium,
    dampingRatio: DampingRatio = DampingRatio.NoBouncy,
    visibilityThreshold: T? = null,
): FiniteAnimationSpec<T> = SpringSpec(stiffness, dampingRatio, visibilityThreshold)

/**
 * A spring-based [AnimationSpec] that produces natural, physics-driven motion.
 *
 * Each component of the animated value is driven by its own [SpringSolver], so a multi-component
 * value such as a size or color animates and settles per component — no single scalar projection
 * can make two genuinely different values look equal and teleport.
 *
 * @see spring
 * @see Stiffness
 * @see DampingRatio
 */
private data class SpringSpec<T>(
    val stiffness: Stiffness = Stiffness.Medium,
    val dampingRatio: DampingRatio = DampingRatio.NoBouncy,
    val visibilityThreshold: T? = null,
) : FiniteAnimationSpec<T> {

  override fun createAnimator(
      initialValue: T,
      targetValue: T,
      typeConverter: TypeConverter<T>,
      initialVelocity: AnimationVector?,
  ): Animator<T> {
    val targetVector = typeConverter.newVectorFromValue(targetValue)
    val valueVector = typeConverter.newVectorFromValue(initialValue)
    val thresholdVector = visibilityThreshold?.let { typeConverter.newVectorFromValue(it) }
    require(
        valueVector.size == targetVector.size &&
            (initialVelocity == null || initialVelocity.size == targetVector.size),
    ) {
      "Spring initial, target, and velocity vectors must have the same size"
    }
    require(thresholdVector == null || thresholdVector.size == targetVector.size) {
      "Spring threshold vector must have the same size as the animated value"
    }
    if (thresholdVector != null) {
      for (i in 0 until thresholdVector.size) {
        require(thresholdVector[i].isFinite() && thresholdVector[i] > 0f) {
          "Spring threshold components must be finite and positive"
        }
      }
    }
    for (i in 0 until targetVector.size) {
      if (
          !(valueVector[i] - targetVector[i]).isFinite() ||
              !(initialVelocity?.get(i) ?: 0f).isFinite()
      ) {
        // Non-finite motion cannot settle. Finish at the requested target so completion still
        // runs without throwing during animation setup.
        return FinishedAnimator(targetValue)
      }
    }
    // Equal endpoints are a no-op only at rest. An interrupted spring can reach its target with
    // non-zero velocity and must move away from it before settling.
    if (initialValue == targetValue && initialVelocity.isNullOrZero()) {
      return FinishedAnimator(targetValue)
    }

    // Each component gets its own solver seeded with its own inherited velocity, so an interrupted
    // multi-component spring preserves every axis's motion across the retarget.
    val solvers =
        Array(targetVector.size) { i ->
          val displacement = valueVector[i] - targetVector[i]
          val threshold =
              thresholdVector?.get(i)
                  ?: run {
                    val minimumVisibleChange = typeConverter.getMinimumVisibleChange(i)
                    require(minimumVisibleChange.isFinite() && minimumVisibleChange >= 0f) {
                      "Converter minimum visible change must be finite and nonnegative"
                    }
                    maxOf(
                        SpringSolver.defaultDisplacementThreshold(displacement),
                        minimumVisibleChange,
                    )
                  }
          val solver =
              SpringSolver.create(
                  stiffness = stiffness.value,
                  dampingRatio = dampingRatio.value,
                  initialDisplacement = displacement,
                  initialVelocity = initialVelocity?.get(i) ?: 0f,
                  displacementThreshold = threshold,
              )
          if (solver.durationNanos == SpringSolver.UNSUPPORTED_DURATION) {
            return FinishedAnimator(targetValue)
          }
          solver
        }
    return SpringAnimator(targetValue, targetVector, valueVector, typeConverter, solvers)
  }
}

/** Supplies a context default only to stock springs without an explicit threshold. */
internal fun FiniteAnimationSpec<Float>.withDefaultVisibilityThreshold(
    threshold: Float,
): FiniteAnimationSpec<Float> =
    if (this is SpringSpec<Float> && visibilityThreshold == null)
        copy(visibilityThreshold = threshold)
    else withCompositionVisibilityThreshold(threshold)

private fun AnimationVector?.isNullOrZero(): Boolean {
  if (this == null) return true
  for (i in 0 until size) {
    if (this[i] != 0f) return false
  }
  return true
}

private class SpringAnimator<T>(
    private val targetValue: T,
    private val targetVector: AnimationVector,
    // Seeded with the initial components at creation, then reused as the per-frame output buffer.
    private val valueVector: AnimationVector,
    private val typeConverter: TypeConverter<T>,
    private val solvers: Array<SpringSolver>,
) : Animator<T> {

  override val durationNanos: Long = run {
    var duration = 0L
    for (solver in solvers) duration = maxOf(duration, solver.durationNanos)
    duration
  }

  override fun getValue(playTimeNanos: Long): T {
    if (isFinished(playTimeNanos)) return targetValue
    for (i in solvers.indices) {
      valueVector[i] = targetVector[i] + solvers[i].getDisplacement(playTimeNanos)
    }
    return typeConverter.fromVector(valueVector)
  }

  override fun getVelocity(playTimeNanos: Long, velocity: AnimationVector) {
    if (isFinished(playTimeNanos)) {
      velocity.reset()
      return
    }
    for (i in solvers.indices) velocity[i] = solvers[i].getVelocity(playTimeNanos)
  }
}

/** An [Animator] that is immediately finished and always returns the given value. */
internal class FinishedAnimator<T>(private val value: T) : Animator<T> {
  override val durationNanos: Long
    get() = 0L

  override fun getValue(playTimeNanos: Long): T = value

  override fun getVelocity(playTimeNanos: Long, velocity: AnimationVector) {
    velocity.reset()
  }
}
