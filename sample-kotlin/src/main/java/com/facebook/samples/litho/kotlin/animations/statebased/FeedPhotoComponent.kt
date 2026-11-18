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
import android.graphics.drawable.GradientDrawable
import com.facebook.litho.Component
import com.facebook.litho.ComponentScope
import com.facebook.litho.KComponent
import com.facebook.litho.Style
import com.facebook.litho.animation.DampingRatio
import com.facebook.litho.animation.Easing
import com.facebook.litho.animation.Stiffness
import com.facebook.litho.animation.alpha
import com.facebook.litho.animation.height
import com.facebook.litho.animation.scale
import com.facebook.litho.animation.spring
import com.facebook.litho.animation.tween
import com.facebook.litho.animation.useAnimationGroup
import com.facebook.litho.animation.width
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.core.height
import com.facebook.litho.core.margin
import com.facebook.litho.core.padding
import com.facebook.litho.core.width
import com.facebook.litho.core.widthPercent
import com.facebook.litho.flexbox.flex
import com.facebook.litho.kotlin.widget.Text
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.layout.flexbox.Row
import com.facebook.litho.useState
import com.facebook.litho.view.Shape
import com.facebook.litho.view.background
import com.facebook.litho.view.clip
import com.facebook.litho.view.onClick
import com.facebook.rendercore.dp
import com.facebook.rendercore.ms
import com.facebook.rendercore.sp
import com.facebook.samples.litho.kotlin.drawable.RoundedRect
import com.facebook.yoga.YogaAlign
import com.facebook.yoga.YogaJustify

/**
 * State-based animation demo: tap to expand and collapse a feed post photo strip.
 *
 * Demonstrates a coordinated [useAnimationGroup] where one Boolean state drives photo height,
 * secondary photo width, opacity, and scale together. Each property carries its own spec, so they
 * retarget as a group but settle at different times.
 */
@OptIn(ExperimentalLithoApi::class)
class FeedPhotoComponent : KComponent() {
  override fun ComponentScope.render(): Component {
    val expanded = useState { false }
    val transition = useAnimationGroup(expanded.value, label = "feed-photo-strip")
    val mediaHeight =
        transition.animateDimen(
            label = "mediaHeight",
            transitionSpec = {
              when {
                false.isTransitioningTo(true) ->
                    spring(stiffness = Stiffness.Medium, dampingRatio = DampingRatio.NoBouncy)
                else -> tween(240.ms, easing = Easing.FastOutSlowIn)
              }
            },
        ) { visible ->
          if (visible) 210.dp else 132.dp
        }
    val secondaryWidth =
        transition.animateDimen(
            label = "secondaryWidth",
            transitionSpec = { tween(260.ms, easing = Easing.FastOutSlowIn) },
        ) { visible ->
          if (visible) 0.dp else 132.dp
        }
    val primaryAlpha =
        transition.animateFloat(
            label = "primaryAlpha",
            transitionSpec = {
              when {
                false.isTransitioningTo(true) -> tween(220.ms, easing = Easing.EaseIn)
                else -> tween(140.ms, easing = Easing.EaseOut)
              }
            },
        ) { visible ->
          if (visible) 1f else 0.76f
        }
    val primaryScale =
        transition.animateFloat(
            label = "primaryScale",
            transitionSpec = { tween(260.ms, easing = Easing.FastOutSlowIn) },
        ) { visible ->
          if (visible) 1f else 0.96f
        }
    val secondaryAlpha =
        transition.animateFloat(
            label = "secondaryAlpha",
            transitionSpec = {
              when {
                false.isTransitioningTo(true) -> tween(120.ms, easing = Easing.EaseOut)
                else -> tween(180.ms, easing = Easing.EaseIn)
              }
            },
        ) { visible ->
          if (visible) 0f else 0.62f
        }
    val secondaryScale =
        transition.animateFloat(
            label = "secondaryScale",
            transitionSpec = { tween(260.ms, easing = Easing.FastOutSlowIn) },
        ) { visible ->
          if (visible) 0.88f else 0.94f
        }

    return Column(style = Style.padding(all = 16.dp)) {
      child(
          Column(
              style =
                  Style.widthPercent(100f)
                      .clip(Shape.RoundedRectangle(18.dp))
                      .background(RoundedRect(0xFFFFFFFF.toInt(), 18.dp)),
          ) {
            child(FeedHeader())
            child(FeedCopy())
            child(
                Row(
                    alignItems = YogaAlign.CENTER,
                    style =
                        Style.widthPercent(100f)
                            .height(mediaHeight)
                            .margin(top = 12.dp)
                            .padding(horizontal = 14.dp),
                ) {
                  child(
                      PhotoCard(
                          createPrimaryPhotoDrawable(),
                          Style.flex(grow = 1f, shrink = 1f)
                              .height(mediaHeight)
                              .alpha(primaryAlpha)
                              .scale(primaryScale)
                              .onClick { expanded.update { !it } },
                      ),
                  )
                  child(
                      PhotoCard(
                          createSecondaryPhotoDrawable(),
                          Style.width(secondaryWidth)
                              .height(mediaHeight)
                              .margin(start = if (expanded.value) 0.dp else 8.dp)
                              .alpha(secondaryAlpha)
                              .scale(secondaryScale),
                      ),
                  )
                },
            )
            child(
                FeedToggle(
                    expanded.value,
                    Style.margin(top = 12.dp).onClick { expanded.update { !it } },
                ),
            )
            child(FeedActions())
          },
      )
    }
  }
}

private fun ComponentScope.FeedHeader(): Component = Row(
    alignItems = YogaAlign.CENTER,
    style = Style.widthPercent(100f).padding(horizontal = 14.dp, vertical = 12.dp),
) {
  child(
      Column(
          alignItems = YogaAlign.CENTER,
          justifyContent = YogaJustify.CENTER,
          style =
              Style.width(42.dp)
                  .height(42.dp)
                  .margin(end = 10.dp)
                  .background(RoundedRect(0xFF1877F2.toInt(), 21.dp)),
      ) {
        child(Text("SM", textSize = 13.sp, textColor = Color.WHITE, textStyle = Typeface.BOLD))
      },
  )
  child(
      Column(style = Style.flex(grow = 1f, shrink = 1f)) {
        child(Text("Sarah Mitchell", textSize = 16.sp, textColor = INK, textStyle = Typeface.BOLD))
        child(
            Text(
                "2 hours ago",
                textSize = 12.sp,
                textColor = MUTED,
                style = Style.margin(top = 2.dp),
            ),
        )
      },
  )
}

private fun ComponentScope.FeedCopy(): Component = Text(
    "Quick reset in the mountains before launch week. Two favorite frames from the climb.",
    textSize = 15.sp,
    textColor = INK,
    style = Style.padding(horizontal = 14.dp),
)

private fun ComponentScope.PhotoCard(drawable: GradientDrawable, style: Style): Component =
    Column(style = style.clip(Shape.RoundedRectangle(14.dp)).background(drawable))

private fun ComponentScope.FeedToggle(expanded: Boolean, style: Style): Component = Text(
    if (expanded) "Show album strip" else "Expand first photo",
    textSize = 13.sp,
    textColor = 0xFF1877F2.toInt(),
    textStyle = Typeface.BOLD,
    style = style.padding(horizontal = 14.dp, vertical = 6.dp),
)

private fun ComponentScope.FeedActions(): Component = Row(
    justifyContent = YogaJustify.SPACE_AROUND,
    alignItems = YogaAlign.CENTER,
    style = Style.widthPercent(100f).padding(vertical = 10.dp),
) {
  child(ActionLabel("Like"))
  child(ActionLabel("Comment"))
  child(ActionLabel("Share"))
}

private fun ComponentScope.ActionLabel(text: String): Component = Text(
    text,
    textSize = 13.sp,
    textColor = MUTED,
    textStyle = Typeface.BOLD,
    style = Style.padding(horizontal = 12.dp, vertical = 6.dp),
)

private fun createPrimaryPhotoDrawable(): GradientDrawable = GradientDrawable(
    GradientDrawable.Orientation.TL_BR,
    intArrayOf(
        0xFF0F766E.toInt(),
        0xFF2563EB.toInt(),
        0xFFDB2777.toInt(),
    ),
)

private fun createSecondaryPhotoDrawable(): GradientDrawable = GradientDrawable(
    GradientDrawable.Orientation.BL_TR,
    intArrayOf(
        0xFFF97316.toInt(),
        0xFFDB2777.toInt(),
        0xFF7C3AED.toInt(),
    ),
)

private val INK = 0xFF0F172A.toInt()
private val MUTED = 0xFF64748B.toInt()
