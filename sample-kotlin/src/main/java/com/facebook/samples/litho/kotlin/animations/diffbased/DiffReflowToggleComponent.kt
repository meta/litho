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

package com.facebook.samples.litho.kotlin.animations.diffbased

import android.graphics.Color
import androidx.core.graphics.toColorInt
import com.facebook.litho.Component
import com.facebook.litho.ComponentScope
import com.facebook.litho.KComponent
import com.facebook.litho.ResourcesScope
import com.facebook.litho.Style
import com.facebook.litho.animation.spring
import com.facebook.litho.animation.transition
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.core.height
import com.facebook.litho.core.margin
import com.facebook.litho.core.padding
import com.facebook.litho.core.width
import com.facebook.litho.key
import com.facebook.litho.kotlin.widget.Text
import com.facebook.litho.layout.flexbox.Column
import com.facebook.litho.useState
import com.facebook.litho.view.backgroundColor
import com.facebook.litho.view.onClick
import com.facebook.rendercore.dp
import com.facebook.rendercore.sp

/**
 * A single playground for read-based layout reflow, driven by two runtime toggles:
 * - **reflow**: switches the blue card's `Style.transition { bounds(...) }` between `reflow = true`
 *   (a real per-frame relayout — the green sibling tracks the interpolated size) and `reflow =
 *   false` (a visual mount-bounds animation — the sibling snaps to the final layout).
 * - **banner**: whether expanding also inserts a banner above the card, so the resize co-occurs
 *   with a structural change. Reflow animates the size-driven shift; the structural shift still
 *   jumps.
 *
 * Tap the blue card to expand/collapse. The rows are keyed so the banner's insert/removal doesn't
 * shift their identity.
 */
@OptIn(ExperimentalLithoApi::class)
class DiffReflowToggleComponent : KComponent() {
  override fun ComponentScope.render(): Component {
    val reflowOn = useState { true }
    val bannerOn = useState { false }
    val expanded = useState { false }

    return Column(style = Style.padding(all = 24.dp)) {
      child(toggle("reflow = ${reflowOn.value}") { reflowOn.update { !it } })
      child(toggle("banner (structural) = ${bannerOn.value}") { bannerOn.update { !it } })
      child(
          Text(
              text =
                  "Tap the blue card to expand. reflow = true → the green sibling tracks the " +
                      "resize frame by frame; reflow = false → it snaps. With banner on, expanding " +
                      "also inserts a structural sibling above the card.",
              textSize = 14.sp,
              textColor = Color.GRAY,
              style = Style.margin(top = 4.dp, bottom = 16.dp),
          ),
      )
      if (expanded.value && bannerOn.value) {
        child(
            key("banner") {
              Column(
                  style =
                      Style.width(320.dp)
                          .height(72.dp)
                          .margin(bottom = 12.dp)
                          .backgroundColor("#FBBC04".toColorInt())
                          .padding(all = 14.dp),
              ) {
                child(Text(text = "Banner (appears)", textSize = 15.sp, textColor = Color.WHITE))
              }
            },
        )
      }
      child(
          key("card") {
            Column(
                style =
                    Style.width(320.dp)
                        .height(if (expanded.value) 260.dp else 96.dp)
                        .margin(bottom = 12.dp)
                        .backgroundColor("#4285F4".toColorInt())
                        .onClick { expanded.update { !it } }
                        .padding(all = 18.dp)
                        .transition { bounds(spring(), reflow = reflowOn.value) },
            ) {
              child(Text(text = "Expanding card", textSize = 17.sp, textColor = Color.WHITE))
            }
          },
      )
      child(
          key("sibling") {
            Column(
                style =
                    Style.width(320.dp)
                        .height(96.dp)
                        .backgroundColor("#34A853".toColorInt())
                        .padding(all = 18.dp),
            ) {
              child(Text(text = "Sibling", textSize = 15.sp, textColor = Color.WHITE))
            }
          },
      )
    }
  }

  private fun ResourcesScope.toggle(label: String, onToggle: () -> Unit): Component = Column(
      style =
          Style.margin(bottom = 8.dp)
              .padding(all = 14.dp)
              .backgroundColor("#616161".toColorInt())
              .onClick { onToggle() },
  ) {
    child(Text(text = "$label  ·  tap to toggle", textSize = 16.sp, textColor = Color.WHITE))
  }
}
