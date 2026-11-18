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

package com.facebook.samples.litho.kotlin.animations.statebased

import com.facebook.litho.ComponentScope
import com.facebook.litho.animation.Animatable
import com.facebook.litho.animation.AnimationPlayback
import com.facebook.litho.animation.AnimationRunScope
import com.facebook.litho.animation.AnimationScope
import com.facebook.litho.animation.DampingRatio
import com.facebook.litho.animation.Easing
import com.facebook.litho.animation.Stiffness
import com.facebook.litho.animation.animateTo
import com.facebook.litho.animation.snapTo
import com.facebook.litho.animation.spring
import com.facebook.litho.animation.tween
import com.facebook.litho.animation.useAnimatable
import com.facebook.litho.animation.useAnimationScope
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.annotations.Hook
import com.facebook.litho.useCached
import com.facebook.rendercore.ms
import com.facebook.samples.litho.R
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Hook
@OptIn(ExperimentalLithoApi::class)
internal fun ComponentScope.usePrismChoreography(playback: AnimationPlayback): PrismChoreography {
  val animations = useAnimationScope(playback)
  val form = useAnimatable(PrismScene.Halo.form, PrismFormConverter, label = "prism-form")
  val rotation = useAnimatable(0f, label = "prism-rotation")
  val radius = useAnimatable(1f, label = "prism-radius")
  val tilt = useAnimatable(PrismScene.Halo.tilt, label = "prism-tilt")
  val warmth = useAnimatable(0f, label = "prism-warmth")
  val breath = useAnimatable(0f, label = "prism-breath")
  val flare = useAnimatable(0f, label = "prism-flare")
  val wave = useAnimatable(1f, label = "prism-wave")
  return useCached(animations, form, rotation, radius, tilt, warmth, breath, flare, wave) {
    PrismChoreography(
        animations = animations,
        form = form,
        rotation = rotation,
        radius = radius,
        tilt = tilt,
        warmth = warmth,
        breath = breath,
        flare = flare,
        wave = wave,
    )
  }
}

@OptIn(ExperimentalLithoApi::class)
internal class PrismChoreography(
    private val animations: AnimationScope,
    val form: Animatable<PrismForm>,
    val rotation: Animatable<Float>,
    val radius: Animatable<Float>,
    val tilt: Animatable<Float>,
    val warmth: Animatable<Float>,
    val breath: Animatable<Float>,
    val flare: Animatable<Float>,
    val wave: Animatable<Float>,
) {
  fun introduce() {
    animateScene(PrismScene.Halo, startRotation = 0f)
  }

  fun play(scene: PrismScene) {
    animateScene(scene, startRotation = rotation.value.value)
  }

  private fun animateScene(scene: PrismScene, startRotation: Float) {
    animations.launchLatest { sculpt(scene, startRotation) }
  }

  private suspend fun AnimationRunScope.sculpt(scene: PrismScene, startRotation: Float) =
      coroutineScope {
        launch { rotate(startRotation) }
        contract(scene)
        morph(scene)
        expand(scene)
        breathe()
      }

  private suspend fun AnimationRunScope.rotate(startRotation: Float) {
    var targetRotation = startRotation
    while (currentCoroutineContext().isActive) {
      awaitMotion()
      targetRotation += PRISM_TAU
      rotation.animateTo(targetRotation, tween(24_000.ms, Easing.Linear))
    }
  }

  private suspend fun AnimationRunScope.contract(scene: PrismScene) = coroutineScope {
    launch { radius.animateTo(0.18f, tween(420.ms, Easing.EaseInOut)) }
    launch { flare.animateTo(1f, tween(420.ms, Easing.EaseIn)) }
    launch { tilt.animateTo(1.3f, tween(420.ms, Easing.EaseInOut)) }
    launch { warmth.animateTo(scene.warmth, tween(700.ms)) }
  }

  private suspend fun AnimationRunScope.morph(scene: PrismScene) {
    form.animateTo(scene.form, tween(260.ms, Easing.EaseInOut))
    wait(80.ms)
  }

  private suspend fun AnimationRunScope.expand(scene: PrismScene) = coroutineScope {
    launch {
      wave.snapTo(0f)
      wave.animateTo(1f, tween(1_400.ms, Easing.EaseOut))
    }
    launch {
      radius.animateTo(
          1f,
          spring(stiffness = Stiffness.Low, dampingRatio = DampingRatio.MediumBouncy),
      )
    }
    launch {
      tilt.animateTo(
          scene.tilt,
          spring(stiffness = Stiffness.Low, dampingRatio = DampingRatio.NoBouncy),
      )
    }
    launch { flare.animateTo(0f, tween(1_100.ms, Easing.EaseOut)) }
  }

  private suspend fun AnimationRunScope.breathe() {
    while (currentCoroutineContext().isActive) {
      awaitMotion()
      breath.animateTo(1f, tween(1_800.ms, Easing.EaseInOut))
      wait(180.ms)
      breath.animateTo(0f, tween(1_800.ms, Easing.EaseInOut))
      wait(180.ms)
    }
  }
}

internal enum class PrismScene(
    val labelRes: Int,
    val titleRes: Int,
    val descriptionRes: Int,
    val form: PrismForm,
    val tilt: Float,
    val warmth: Float,
) {
  Halo(
      R.string.litho_sample_prism_halo,
      R.string.litho_sample_prism_halo_title,
      R.string.litho_sample_prism_halo_description,
      PrismForm(1f, 0f, 0f),
      0.95f,
      0f,
  ),
  Bloom(
      R.string.litho_sample_prism_bloom,
      R.string.litho_sample_prism_bloom_title,
      R.string.litho_sample_prism_bloom_description,
      PrismForm(0f, 1f, 0f),
      0.25f,
      0.65f,
  ),
  Galaxy(
      R.string.litho_sample_prism_galaxy,
      R.string.litho_sample_prism_galaxy_title,
      R.string.litho_sample_prism_galaxy_description,
      PrismForm(0f, 0f, 1f),
      0.5f,
      1f,
  ),
}

internal const val PRISM_TAU = 6.2831855f
