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

import android.graphics.Color
import com.facebook.litho.ComponentScope
import com.facebook.litho.animation.Animatable
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
import com.facebook.rendercore.Dimen
import com.facebook.rendercore.dp
import com.facebook.rendercore.ms
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

@Hook
@OptIn(ExperimentalLithoApi::class)
internal fun ComponentScope.useLikeBurstAnimation(): LikeBurstAnimation {
  val animations = useAnimationScope()
  val footerHeart = useAnimatable(1f)
  val originX = useAnimatable(0f)
  val originY = useAnimatable(0f)
  val haloScale = useAnimatable(0f)
  val haloAlpha = useAnimatable(0f)
  val heartScale = useAnimatable(0f)
  val heartAlpha = useAnimatable(0f)
  val heartRotation = useAnimatable(0f)
  val heartLift = useAnimatable(0f)
  val particles = ArrayList<LikeBurstParticle>(PARTICLES.size)
  for (spec in PARTICLES) {
    particles +=
        LikeBurstParticle(
            spec = spec,
            distancePx = spec.distance.toPixels().toFloat(),
            x = useAnimatable(0f),
            y = useAnimatable(0f),
            scale = useAnimatable(0f),
            alpha = useAnimatable(0f),
            rotation = useAnimatable(0f),
        )
  }
  val fallPx = CONFETTI_FALL.toPixels().toFloat()
  val liftPx = HEART_LIFT.toPixels().toFloat()

  return LikeBurstAnimation(
      animations = animations,
      footerHeart = footerHeart,
      originX = originX,
      originY = originY,
      haloScale = haloScale,
      haloAlpha = haloAlpha,
      heartScale = heartScale,
      heartAlpha = heartAlpha,
      heartRotation = heartRotation,
      heartLift = heartLift,
      particles = particles,
      fallPx = fallPx,
      liftPx = liftPx,
  )
}

/** Event-driven runs: a new burst interrupts the previous one; unlike clears the overlay. */
@OptIn(ExperimentalLithoApi::class)
internal class LikeBurstAnimation(
    private val animations: AnimationScope,
    val footerHeart: Animatable<Float>,
    val originX: Animatable<Float>,
    val originY: Animatable<Float>,
    val haloScale: Animatable<Float>,
    val haloAlpha: Animatable<Float>,
    val heartScale: Animatable<Float>,
    val heartAlpha: Animatable<Float>,
    val heartRotation: Animatable<Float>,
    val heartLift: Animatable<Float>,
    val particles: List<LikeBurstParticle>,
    private val fallPx: Float,
    private val liftPx: Float,
) {
  fun burst(offsetX: Float = 0f, offsetY: Float = 0f) {
    val twist = Random.nextFloat() * 2f * PI.toFloat()
    animations.launchLatest {
      launch { footerHeart.pop(1.35f) }
      originX.snapTo(offsetX)
      originY.snapTo(offsetY)
      launch { radiateHalo() }
      launch { scatterConfetti(twist) }
      floatHeart()
    }
  }

  fun unlike() {
    animations.launchLatest {
      launch { footerHeart.pop(0.8f) }
      launch { heartAlpha.animateTo(0f, tween(150.ms)) }
      launch { haloAlpha.animateTo(0f, tween(150.ms)) }
      particles.forEach { particle -> launch { particle.alpha.animateTo(0f, tween(150.ms)) } }
    }
  }

  private suspend fun radiateHalo() = coroutineScope {
    haloScale.snapTo(0.3f)
    haloAlpha.snapTo(0.55f)
    launch { haloScale.animateTo(2.4f, tween(650.ms, easing = Easing.LinearOutSlowIn)) }
    haloAlpha.animateTo(0f, tween(650.ms, easing = Easing.EaseOut))
  }

  private suspend fun AnimationRunScope.scatterConfetti(twist: Float) = coroutineScope {
    particles.forEachIndexed { index, particle ->
      launch {
        wait((index % 4 * 18).ms)
        particle.fly(twist, fallPx)
      }
    }
  }

  private suspend fun AnimationRunScope.floatHeart() = coroutineScope {
    heartLift.snapTo(0f)
    heartAlpha.snapTo(1f)
    launch {
      heartRotation.snapTo(-16f)
      heartRotation.animateTo(
          0f,
          spring(stiffness = Stiffness.Medium, dampingRatio = DampingRatio.HighBouncy),
      )
    }
    heartScale.animateTo(
        1.2f,
        spring(stiffness = Stiffness.Medium, dampingRatio = DampingRatio.LowBouncy),
    )
    heartScale.animateTo(1f, spring(dampingRatio = DampingRatio.MediumBouncy))
    heartScale.animateTo(1.12f, tween(90.ms, easing = Easing.EaseOut))
    heartScale.animateTo(1f, tween(110.ms, easing = Easing.EaseIn))
    heartScale.animateTo(1.06f, tween(80.ms, easing = Easing.EaseOut))
    heartScale.animateTo(1f, tween(120.ms, easing = Easing.EaseIn))
    wait(140.ms)
    launch { heartLift.animateTo(-liftPx, tween(420.ms, easing = Easing.FastOutLinearIn)) }
    launch { heartScale.animateTo(0.7f, tween(420.ms, easing = Easing.EaseIn)) }
    heartAlpha.animateTo(0f, tween(420.ms, easing = Easing.EaseIn))
    heartScale.snapTo(0f)
    heartLift.snapTo(0f)
  }
}

@OptIn(ExperimentalLithoApi::class)
internal class LikeBurstParticle(
    val spec: LikeBurstParticleSpec,
    val distancePx: Float,
    val x: Animatable<Float>,
    val y: Animatable<Float>,
    val scale: Animatable<Float>,
    val alpha: Animatable<Float>,
    val rotation: Animatable<Float>,
)

internal class LikeBurstParticleSpec(
    val angleRadians: Float,
    val distance: Dimen,
    val size: Dimen,
    val color: Int,
    val isHeart: Boolean,
    val spin: Float,
)

/** Flies outward, grows and spins, then falls as it fades. */
@OptIn(ExperimentalLithoApi::class)
private suspend fun LikeBurstParticle.fly(twist: Float, fallPx: Float) = coroutineScope {
  val angle = spec.angleRadians + twist
  val distance = distancePx * (0.85f + Random.nextFloat() * 0.3f)
  val dx = distance * cos(angle)
  val dy = distance * sin(angle)
  x.snapTo(0f)
  y.snapTo(0f)
  scale.snapTo(0.2f)
  alpha.snapTo(1f)
  rotation.snapTo(0f)
  launch { x.animateTo(dx, tween(620.ms, easing = Easing.FastOutSlowIn)) }
  launch { rotation.animateTo(spec.spin, tween(780.ms, easing = Easing.FastOutSlowIn)) }
  launch {
    scale.animateTo(
        1f,
        spring(stiffness = Stiffness.Medium, dampingRatio = DampingRatio.MediumBouncy),
    )
  }
  y.animateTo(dy, tween(380.ms, easing = Easing.FastOutSlowIn))
  launch { y.animateTo(dy + fallPx, tween(460.ms, easing = Easing.EaseIn)) }
  alpha.animateTo(0f, tween(460.ms, easing = Easing.EaseIn))
}

@OptIn(ExperimentalLithoApi::class)
private suspend fun Animatable<Float>.pop(peak: Float) {
  animateTo(peak, tween(110.ms))
  animateTo(1f, spring(dampingRatio = DampingRatio.MediumBouncy))
}

private val CONFETTI_FALL = 36.dp
private val HEART_LIFT = 48.dp

private val CONFETTI_COLORS =
    intArrayOf(0xFFE11D48.toInt(), 0xFFFB7185.toInt(), 0xFFF59E0B.toInt(), Color.WHITE)

private val PARTICLES: List<LikeBurstParticleSpec> =
    List(12) { index ->
      LikeBurstParticleSpec(
          angleRadians = (index * 30.0 + 15.0).toFloat() * PI.toFloat() / 180f,
          distance = if (index % 2 == 0) 104.dp else 132.dp,
          size = if (index % 3 == 0) 18.dp else if (index % 3 == 1) 10.dp else 14.dp,
          color = CONFETTI_COLORS[index % CONFETTI_COLORS.size],
          isHeart = index % 3 == 0,
          spin = if (index % 2 == 0) 220f else -260f,
      )
    }
