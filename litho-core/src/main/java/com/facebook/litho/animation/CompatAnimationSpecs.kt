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
import android.view.animation.Interpolator
import com.facebook.rendercore.ms
import kotlin.math.sqrt

/**
 * Equivalent of `Transition.SPRING_WITH_OVERSHOOT`, the default `Transition` animator. Keeps the
 * existing motion of code moving off `Transition`; prefer [spring] for new code.
 *
 * @see compatSpring
 */
fun <T> compatSpring(): FiniteAnimationSpec<T> = compatSpring(DEFAULT_TENSION, DEFAULT_FRICTION)

/**
 * Equivalent of `Transition.springWithConfig(tension, friction)`. Keeps the existing motion of code
 * moving off `Transition`; prefer [spring] for new code.
 *
 * Both engines solve the same unit-mass oscillator, `x'' = -tension * x - friction * x'`, so the
 * mapping to stiffness and damping ratio is exact. The motion is equivalent within tolerance rather
 * than bit-identical: this runs the closed-form solver instead of the fixed-step integrator behind
 * `Transition`, and settles on slightly different thresholds.
 *
 * Under a dropped frame the two differ. `Transition` advances its spring by at most 64ms per frame,
 * so after a long frame it falls behind real time; this spec evaluates the full elapsed time. A
 * stateless spec cannot reproduce that cap, since it depends on how frames were spaced.
 *
 * [friction] must be positive: with no damping the spring never settles, which a finite spec cannot
 * express.
 */
fun <T> compatSpring(tension: Double, friction: Double): FiniteAnimationSpec<T> {
  val stiffness = tension.toFloat()
  val dampingRatio = (friction / (2 * sqrt(tension))).toFloat()
  require(stiffness > 0 && stiffness.isFinite()) {
    "Tension must be a positive, finite Float, but was $tension"
  }
  require(dampingRatio > 0 && dampingRatio.isFinite()) {
    "Friction must be positive and finite, but was $friction"
  }
  return spring(stiffness = Stiffness(stiffness), dampingRatio = DampingRatio(dampingRatio))
}

/** Equivalent of `Transition.SPRING_WITHOUT_OVERSHOOT`. */
fun <T> compatSpringNoOvershoot(): FiniteAnimationSpec<T> =
    compatSpring(NO_OVERSHOOT_TENSION, NO_OVERSHOOT_FRICTION)

/**
 * Equivalent of `Transition.timing(durationMs)`, which eases with accelerate-decelerate. Keeps the
 * existing motion of code moving off `Transition`; prefer [tween] for new code. A negative
 * [durationMs] finishes at once, as it does for `Transition`.
 */
fun <T> compatTiming(durationMs: Int): DurationBasedAnimationSpec<T> =
    tween(duration = durationMs.coerceAtLeast(0).ms, easing = ACCELERATE_DECELERATE)

/**
 * Equivalent of `Transition.timing(durationMs, interpolator)`. A `null` [interpolator] is linear,
 * as it is for `Transition`, and a negative [durationMs] finishes at once.
 */
fun <T> compatTiming(
    durationMs: Int,
    interpolator: Interpolator?,
): DurationBasedAnimationSpec<T> = tween(
    duration = durationMs.coerceAtLeast(0).ms,
    easing = interpolator?.asEasing() ?: Easing.Linear,
)

// Mirror `SpringConfig.defaultConfig` and `SpringConfig.noOvershootConfig`. Duplicated rather than
// referenced so this file has no dependency on the engine it replaces.
private const val DEFAULT_TENSION = 230.2
private const val DEFAULT_FRICTION = 22.0
private const val NO_OVERSHOOT_TENSION = 338.8
private const val NO_OVERSHOOT_FRICTION = 34.0

private val ACCELERATE_DECELERATE: Easing = AccelerateDecelerateInterpolator().asEasing()
