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
import com.facebook.litho.animation.Stiffness
import com.facebook.litho.animation.spring
import com.facebook.litho.animation.transition
import com.facebook.litho.animation.tween
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.core.height
import com.facebook.litho.core.margin
import com.facebook.litho.flexbox.flex
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.useState
import com.facebook.litho.view.alpha
import com.facebook.litho.view.clipChildren
import com.facebook.litho.view.onClick
import com.facebook.litho.view.rotation
import com.facebook.rendercore.dp
import com.facebook.rendercore.ms

/**
 * Expandable trip card animated with [transition]. Tapping the header toggles one Boolean, and
 * Litho animates the style changes between the two layouts:
 * - **Details height**: grows from zero to its measured height and back with a quick tween. `reflow
 *   = true` lays out every frame, so the card and its shadow follow.
 * - **Details alpha**: fades in after a short delay and out quickly.
 * - **Details offset**: a collapsed-only top margin, animated with `y`, so the details settle
 *   upwards into place as they fade in.
 * - **Chevron rotation**: 0° ↔ 180° with the default spring.
 *
 * The alpha spec is picked from the new state, so fading in and out use different timings.
 * [StateBasedExpandableCardComponent] builds the same card with `useAnimationGroup`, which needs
 * the expanded height up front.
 */
@OptIn(ExperimentalLithoApi::class)
class DiffBasedExpandableCardComponent : KComponent() {
  override fun ComponentScope.render(): Component {
    val expanded = useState { false }
    val isExpanded = expanded.value

    return TripCardPage {
      child(
          TripHeader(
              expanded = isExpanded,
              chevronStyle =
                  Style.rotation(if (isExpanded) 180f else 0f).transition {
                    rotation = spring()
                  },
              style = Style.flex(shrink = 0f).onClick { expanded.update { !it } },
          ),
      )
      child(
          Column(
              style =
                  (if (isExpanded) Style.alpha(1f) else Style.height(0.dp).alpha(0f))
                      .clipChildren(true)
                      .transition {
                        size(tween(250.ms, easing = Easing.FastOutSlowIn), reflow = true)
                        alpha =
                            if (isExpanded) tween(220.ms, delay = 60.ms, easing = Easing.EaseIn)
                            else tween(120.ms, easing = Easing.EaseOut)
                      },
          ) {
            child(
                TripDetails(
                    style =
                        Style.flex(shrink = 0f)
                            .margin(top = if (isExpanded) 0.dp else DETAILS_OFFSET)
                            .transition {
                              y =
                                  spring(
                                      stiffness = Stiffness.MediumLow,
                                      dampingRatio = DampingRatio.NoBouncy,
                                  )
                            },
                ),
            )
          },
      )
    }
  }
}
