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

package com.facebook.samples.litho.kotlin.animations.expandablecard

import com.facebook.litho.Component
import com.facebook.litho.ComponentScope
import com.facebook.litho.KComponent
import com.facebook.litho.Style
import com.facebook.litho.animation.DampingRatio
import com.facebook.litho.animation.Easing
import com.facebook.litho.animation.Segment
import com.facebook.litho.animation.Stiffness
import com.facebook.litho.animation.alpha
import com.facebook.litho.animation.height
import com.facebook.litho.animation.rotation
import com.facebook.litho.animation.spring
import com.facebook.litho.animation.translationY
import com.facebook.litho.animation.tween
import com.facebook.litho.animation.useAnimationGroup
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.flexbox.flex
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.useState
import com.facebook.litho.view.onClick
import com.facebook.rendercore.dp
import com.facebook.rendercore.ms

/**
 * Expandable trip card with coordinated animations via [useAnimationGroup]. Tapping the header
 * drives four animations from one Boolean:
 * - **Details height**: a quick tween in both directions
 * - **Details alpha**: fade in/out with different timings per direction (directional via [Segment])
 * - **Details offset**: the details settle upwards into place as they fade in
 * - **Chevron rotation**: 0° ↔ 180° with the default spring
 *
 * The group animates between values it is given, so the details height is a constant that must fit
 * the details. [DiffBasedExpandableCardComponent] builds the same card with `Style.transition`,
 * which takes both heights from layout.
 */
@OptIn(ExperimentalLithoApi::class)
class StateBasedExpandableCardComponent : KComponent() {
  override fun ComponentScope.render(): Component {
    val expanded = useState { false }
    val transition = useAnimationGroup(expanded.value, label = "expand")

    val height =
        transition.animateDimen(
            label = "height",
            transitionSpec = { tween(250.ms, easing = Easing.FastOutSlowIn) },
        ) { state ->
          if (state) DETAILS_HEIGHT else 0.dp
        }

    val detailsAlpha =
        transition.animateFloat(
            label = "detailsAlpha",
            transitionSpec = {
              when {
                false.isTransitioningTo(true) ->
                    tween(220.ms, delay = 60.ms, easing = Easing.EaseIn)
                else -> tween(120.ms, easing = Easing.EaseOut)
              }
            },
        ) { state ->
          if (state) 1f else 0f
        }

    val detailsOffset = DETAILS_OFFSET.toPixels().toFloat()
    val detailsTranslation =
        transition.animateFloat(
            label = "detailsTranslation",
            transitionSpec = {
              spring(stiffness = Stiffness.MediumLow, dampingRatio = DampingRatio.NoBouncy)
            },
        ) { state ->
          if (state) 0f else detailsOffset
        }

    val chevronRotation =
        transition.animateFloat(label = "chevronRotation") { state -> if (state) 180f else 0f }

    return TripCardPage {
      child(
          TripHeader(
              expanded = expanded.value,
              chevronStyle = Style.rotation(chevronRotation),
              style = Style.flex(shrink = 0f).onClick { expanded.update { !it } },
          ),
      )
      child(
          Column(style = Style.height(height)) {
            child(
                TripDetails(
                    style =
                        Style.flex(shrink = 0f)
                            .alpha(detailsAlpha)
                            .translationY(detailsTranslation),
                ),
            )
          },
      )
    }
  }
}

// The details' measured height; update it when the details change.
private val DETAILS_HEIGHT = 340.dp
