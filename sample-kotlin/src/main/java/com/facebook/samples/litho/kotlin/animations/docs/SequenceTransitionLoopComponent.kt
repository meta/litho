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

package com.facebook.samples.litho.kotlin.animations.docs

import com.facebook.litho.Component
import com.facebook.litho.ComponentScope
import com.facebook.litho.KComponent
import com.facebook.litho.Style
import com.facebook.litho.Transition
import com.facebook.litho.TransitionEndEvent
import com.facebook.litho.animation.AnimatedProperties
import com.facebook.litho.core.heightPercent
import com.facebook.litho.eventHandler
import com.facebook.litho.layout.flexbox.Row
import com.facebook.litho.transition.transitionKey
import com.facebook.litho.transition.useTransition
import com.facebook.litho.useState
import com.facebook.litho.view.onClick
import com.facebook.samples.litho.kotlin.bordereffects.NiceColor
import com.facebook.yoga.YogaAlign
import com.facebook.yoga.YogaJustify

private const val YellowKey = "YellowKey"
private const val BlueKey = "BlueKey"
private const val MagentaKey = "MagentaKey"

/**
 * Demonstrates Litho's legacy transition API (`useTransition`, `Style.transitionKey`), kept as a
 * reference for existing code. For the modern version, see the "Sequence" row of
 * [com.facebook.samples.litho.kotlin.animations.diffbased.ChoreographyComponent], and
 * [com.facebook.samples.litho.kotlin.animations.statebased.AnimationCallbackComponent] for looping
 * when an animation settles.
 */
// start_example
class SequenceTransitionLoopComponent : KComponent() {

  override fun ComponentScope.render(): Component {
    val shown = useState { false }
    val isLooping = useState { false }

    // Create an event handler for transition end
    val onTransitionEnd =
        eventHandler<TransitionEndEvent> { _ ->
          // When the last animation completes, toggle state to loop
          if (isLooping.value) {
            shown.update { !it }
          }
        }

    // Animate transitions in sequence with a callback on the last one
    useTransition(
        Transition.sequence(
            Transition.create(YellowKey).animate(AnimatedProperties.Y),
            Transition.create(BlueKey).animate(AnimatedProperties.Y),
            Transition.create(MagentaKey)
                .animate(AnimatedProperties.Y)
                .transitionEndHandler(onTransitionEnd),
        ),
    )

    return Row(
        alignItems = if (shown.value) YogaAlign.FLEX_END else YogaAlign.FLEX_START,
        justifyContent = YogaJustify.CENTER,
        style =
            Style.heightPercent(100f).onClick {
              isLooping.update { !it }
              shown.update { !it }
            },
    ) {
      child(ColorCell(color = NiceColor.Yellow, style = Style.transitionKey(context, YellowKey)))
      child(ColorCell(color = NiceColor.Blue, style = Style.transitionKey(context, BlueKey)))
      child(ColorCell(color = NiceColor.Magenta, style = Style.transitionKey(context, MagentaKey)))
    }
  }
}
// end_example
