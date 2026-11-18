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
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.MotionEvent
import com.facebook.litho.Component
import com.facebook.litho.ComponentScope
import com.facebook.litho.KComponent
import com.facebook.litho.ResourcesScope
import com.facebook.litho.Style
import com.facebook.litho.Value
import com.facebook.litho.animation.alpha
import com.facebook.litho.animation.rotation
import com.facebook.litho.animation.scale
import com.facebook.litho.animation.translationX
import com.facebook.litho.animation.translationY
import com.facebook.litho.animation.useAnimationScope
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.core.margin
import com.facebook.litho.core.padding
import com.facebook.litho.core.size
import com.facebook.litho.core.widthPercent
import com.facebook.litho.flexbox.aspectRatio
import com.facebook.litho.flexbox.flex
import com.facebook.litho.kotlin.widget.Image
import com.facebook.litho.kotlin.widget.Text
import com.facebook.litho.layout.StackLayout
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.layout.flexbox.Row
import com.facebook.litho.useCached
import com.facebook.litho.useState
import com.facebook.litho.view.Shape
import com.facebook.litho.view.background
import com.facebook.litho.view.clip
import com.facebook.litho.view.elevation
import com.facebook.litho.view.onClick
import com.facebook.litho.view.onTouch
import com.facebook.rendercore.dp
import com.facebook.rendercore.drawableRes
import com.facebook.rendercore.sp
import com.facebook.rendercore.stringRes
import com.facebook.samples.litho.R
import com.facebook.samples.litho.kotlin.drawable.RoundedRect
import com.facebook.yoga.YogaAlign
import com.facebook.yoga.YogaJustify

/**
 * A photo post whose like is choreographed with [useAnimationScope]. Tapping the photo bursts from
 * the tap point (the heart button bursts from the centre). One `launchLatest` sequence drives four
 * groups in parallel with `launch`:
 * - **Halo**: a soft shockwave grows from the origin and fades.
 * - **Confetti**: twelve dots and hearts fly out with staggered starts (`wait`), spin, then fall
 *   away. The pattern turns and the distances vary on every burst.
 * - **Big heart**: springs in with a wobble, beats twice, then floats up and fades.
 * - **Footer heart**: pops and settles with a bouncy spring.
 *
 * Tapping again mid-burst cancels the running sequence and starts the next one. The big heart and
 * footer heart carry on from their current values; the confetti and halo restart as a fresh burst.
 * Unliking runs a short shrink that also clears anything still in flight.
 */
@OptIn(ExperimentalLithoApi::class)
class LikeBurstComponent : KComponent() {
  override fun ComponentScope.render(): Component {
    val liked = useState { false }
    val isLiked = liked.value
    val animation = useLikeBurstAnimation()
    val glowRadius = GLOW_RADIUS.toPixels().toFloat()
    val sunset = useCached(glowRadius) { sunsetDrawable(glowRadius) }

    val like: (Float, Float) -> Unit = { offsetX, offsetY ->
      liked.update(true)
      animation.burst(offsetX, offsetY)
    }
    val toggleLike: () -> Unit = {
      liked.update { !it }
      if (isLiked) animation.unlike() else animation.burst()
    }

    return Column(style = Style.padding(all = 24.dp).background(RoundedRect(SURFACE, 0.dp))) {
      child(
          Column(
              style =
                  Style.widthPercent(100f)
                      .background(RoundedRect(Color.WHITE, 24.dp))
                      .clip(Shape.RoundedRectangle(24.dp))
                      .elevation(6.dp),
          ) {
            child(PostPhoto(animation, sunset, like))
            child(PostFooter(isLiked, animation.footerHeart.value, toggleLike))
          },
      )
      child(
          Text(
              stringRes(R.string.litho_sample_like_burst_hint),
              textSize = 13.sp,
              textColor = MUTED,
              style = Style.margin(top = 16.dp),
          ),
      )
    }
  }
}

@OptIn(ExperimentalLithoApi::class)
private fun ResourcesScope.PostPhoto(
    animation: LikeBurstAnimation,
    sunset: Drawable,
    onLike: (Float, Float) -> Unit,
): Component = StackLayout(
    alignItems = YogaAlign.CENTER,
    justifyContent = YogaJustify.CENTER,
    style =
        Style.widthPercent(100f).aspectRatio(1.1f).background(sunset).onTouch { event ->
          val motion = event.motionEvent
          if (motion.actionMasked == MotionEvent.ACTION_UP) {
            onLike(
                motion.x - event.view.width / 2f,
                motion.y - event.view.height / 2f,
            )
          }
          true
        },
) {
  child(BurstOverlay(animation))
}

@OptIn(ExperimentalLithoApi::class)
private fun ResourcesScope.BurstOverlay(animation: LikeBurstAnimation): Component = StackLayout(
    alignItems = YogaAlign.CENTER,
    justifyContent = YogaJustify.CENTER,
    style =
        Style.size(OVERLAY_SIZE)
            .translationX(animation.originX.value)
            .translationY(animation.originY.value),
) {
  child(
      Column(
          style =
              Style.size(HALO_SIZE)
                  .scale(animation.haloScale.value)
                  .alpha(animation.haloAlpha.value)
                  .background(RoundedRect(HALO, HALO_SIZE / 2)),
      ),
  )
  for (particle in animation.particles) {
    child(ConfettiPiece(particle))
  }
  child(
      Image(
          drawable = drawableRes(R.drawable.litho_sample_like_burst_heart),
          style =
              Style.size(96.dp)
                  .translationY(animation.heartLift.value)
                  .rotation(animation.heartRotation.value)
                  .scale(animation.heartScale.value)
                  .alpha(animation.heartAlpha.value),
      ),
  )
}

@OptIn(ExperimentalLithoApi::class)
private fun ResourcesScope.PostFooter(
    isLiked: Boolean,
    heartScale: Value<Float>,
    onToggleLike: () -> Unit,
): Component =
    Row(alignItems = YogaAlign.CENTER, style = Style.padding(all = 16.dp)) {
      child(
          Row(
              alignItems = YogaAlign.CENTER,
              justifyContent = YogaJustify.CENTER,
              style =
                  Style.size(48.dp)
                      .scale(heartScale)
                      .background(RoundedRect(if (isLiked) LIKE else IDLE, 24.dp))
                      .onClick { onToggleLike() },
          ) {
            child(
                Image(
                    drawable = drawableRes(R.drawable.litho_sample_like_burst_heart),
                    style = Style.size(24.dp),
                ),
            )
          },
      )
      child(
          Column(style = Style.flex(grow = 1f, shrink = 1f).margin(start = 14.dp)) {
            child(
                Text(
                    stringRes(R.string.litho_sample_like_burst_title),
                    textSize = 16.sp,
                    textStyle = Typeface.BOLD,
                    textColor = INK,
                ),
            )
            child(
                Text(
                    stringRes(
                        if (isLiked) R.string.litho_sample_like_burst_liked
                        else R.string.litho_sample_like_burst_like,
                    ),
                    textSize = 13.sp,
                    textColor = if (isLiked) LIKE else MUTED,
                    style = Style.margin(top = 2.dp),
                ),
            )
          },
      )
    }

@OptIn(ExperimentalLithoApi::class)
private fun ResourcesScope.ConfettiPiece(particle: LikeBurstParticle): Component {
  val style =
      Style.size(particle.spec.size)
          .translationX(particle.x.value)
          .translationY(particle.y.value)
          .rotation(particle.rotation.value)
          .scale(particle.scale.value)
          .alpha(particle.alpha.value)
  return if (particle.spec.isHeart) {
    Image(drawable = drawableRes(R.drawable.litho_sample_like_burst_heart), style = style)
  } else {
    Column(style = style.background(RoundedRect(particle.spec.color, particle.spec.size / 2)))
  }
}

/** A dusk sky with a warm glow low on the horizon. */
private fun sunsetDrawable(glowRadius: Float): Drawable {
  val sky = GradientDrawable(
      GradientDrawable.Orientation.TOP_BOTTOM,
      intArrayOf(0xFF312E81.toInt(), 0xFF9D174D.toInt(), 0xFFF97316.toInt(), 0xFFFDE68A.toInt()),
  )
  val glow =
      GradientDrawable().apply {
        gradientType = GradientDrawable.RADIAL_GRADIENT
        colors = intArrayOf(0x99FFF7ED.toInt(), 0x00FFF7ED)
        gradientRadius = glowRadius
        setGradientCenter(0.5f, 0.8f)
      }
  return LayerDrawable(arrayOf(sky, glow))
}

private val SURFACE = 0xFFF1F5F4.toInt()
private val INK = 0xFF142D2A.toInt()
private val MUTED = 0xFF627570.toInt()
private val IDLE = 0xFFB8C4C1.toInt()
private val LIKE = 0xFFE11D48.toInt()
private val HALO = 0xCCFFE4E6.toInt()

private val OVERLAY_SIZE = 440.dp
private val HALO_SIZE = 120.dp
private val GLOW_RADIUS = 220.dp
