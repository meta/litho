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

package com.facebook.samples.litho.kotlin.animations.expandableelement

import com.facebook.litho.ClickEvent
import com.facebook.litho.Component
import com.facebook.litho.ComponentScope
import com.facebook.litho.KComponent
import com.facebook.litho.Style
import com.facebook.litho.Transition
import com.facebook.litho.animation.AnimatedProperties
import com.facebook.litho.core.padding
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.transition.transitionKey
import com.facebook.litho.transition.useTransition
import com.facebook.litho.useCallback
import com.facebook.litho.useState
import com.facebook.litho.view.onClick
import com.facebook.rendercore.dp

const val TransitionMsgParent = "transition_msg_parent"
const val TransitionTopDetail = "transition_top_detail"
const val TransitionBottomDetail = "transition_bottom_detail"

/**
 * Demonstrates Litho's legacy transition API (`useTransition`, `Style.transitionKey`), kept as a
 * reference for existing code. There is no modern version of this demo yet. The closest are
 * [com.facebook.samples.litho.kotlin.animations.expandablecard.DiffBasedExpandableCardComponent]
 * and [com.facebook.samples.litho.kotlin.animations.statebased.AnimatedVisibilityDemoComponent].
 */
internal class ExpandableElement(
    private val content: Component,
    private val timestamp: String,
    private val seen: Boolean = false,
) : KComponent() {

  override fun ComponentScope.render(): Component {
    val expanded = useState { false }
    val toggleExpanded = useCallback { _: ClickEvent -> expanded.update { !it } }

    useTransition(
        Transition.parallel(
            Transition.allLayout(),
            Transition.create(TransitionMsgParent)
                .animate(AnimatedProperties.HEIGHT)
                .appearFrom(0f),
            Transition.create(
                    Transition.TransitionKeyType.GLOBAL,
                    TransitionTopDetail,
                    TransitionBottomDetail,
                )
                .animate(AnimatedProperties.HEIGHT)
                .appearFrom(0f)
                .disappearTo(0f),
        ),
    )

    return Column(
        style =
            Style.padding(top = 8.dp)
                .transitionKey(context, TransitionMsgParent)
                .onClick(toggleExpanded),
    ) {
      if (expanded.value) {
        child(ExpandableElementTopDetail(timestamp))
      }
      child(
          Column {
            child(content)
            if (expanded.value) {
              child(ExpandableElementBottomDetail(seen))
            }
          },
      )
    }
  }
}
