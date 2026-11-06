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

package com.facebook.samples.litho.documentation.customlayout

import com.facebook.litho.Component
import com.facebook.litho.ComponentScope
import com.facebook.litho.KComponent
import com.facebook.litho.Layout
import com.facebook.litho.Style
import com.facebook.litho.accessibility.contentDescription
import com.facebook.litho.annotations.ExperimentalLithoApi
import com.facebook.litho.key
import com.facebook.rendercore.Dimen
import com.facebook.rendercore.SizeConstraints
import com.facebook.rendercore.dp
import com.facebook.rendercore.quantityStringRes
import com.facebook.samples.litho.R

// start_example
/**
 * A row of overlapping avatars, each [avatarSize] square: as many as fit, then a "+N" label for the
 * rest, which [moreLabel] builds for the number hidden. The label takes an avatar's place and size.
 * Each avatar overlaps the one before it by [overlap], which must be less than [avatarSize], and is
 * drawn over it. Right to left, the row starts at the right edge.
 *
 * A `Layout` can't create children while it measures, so every avatar is declared up front, with a
 * label for each count from 1 to the number of avatars, and the layout places only the avatars and
 * the one label it shows: the others are resolved but never mounted. Only the avatars and the label
 * shown are measured.
 */
@OptIn(ExperimentalLithoApi::class)
class FacepileLayout(
    private val avatars: List<Component>,
    private val avatarSize: Dimen = 40.dp,
    private val overlap: Dimen = 8.dp,
    private val style: Style = Style,
    private val moreLabel: (hiddenCount: Int) -> Component,
) : KComponent() {
  override fun ComponentScope.render(): Component {
    val avatarCount = avatars.size
    // The label for hiddenCount avatars is child avatarCount + hiddenCount - 1.
    val labels = List(avatarCount) { index -> moreLabel(index + 1) }
    return Layout(avatars + labels, style = style) { measurables, constraints ->
      val sizePx = avatarSize.toPixels()
      val stepPx = sizePx - overlap.toPixels()
      require(stepPx > 0) { "overlap must be less than avatarSize" }
      val avatarConstraints = SizeConstraints.exact(sizePx, sizePx)
      fun rowWidth(count: Int): Int = if (count == 0) 0 else sizePx + stepPx * (count - 1)

      var shownCount = 0
      while (shownCount < avatarCount && rowWidth(shownCount + 1) <= constraints.maxWidth) {
        shownCount++
      }
      // When some don't fit, the label for the rest takes the last avatar's place.
      if (shownCount in 1 until avatarCount) shownCount--
      val hiddenCount = avatarCount - shownCount
      val shown =
          (0 until shownCount).map { index -> measurables[index].measure(avatarConstraints) }
      val placeables =
          if (hiddenCount == 0) shown
          else shown + measurables[avatarCount + hiddenCount - 1].measure(avatarConstraints)

      layout(width = rowWidth(placeables.size), height = if (placeables.isEmpty()) 0 else sizePx) {
        // placeRelative mirrors each start right to left. Placed in order, so each avatar is drawn
        // over the one before it.
        placeables.forEachIndexed { index, placeable ->
          placeable.placeRelative(start = index * stepPx, top = 0)
        }
      }
    }
  }
}

// end_example

/** A group's members, then a count of the others. */
class FacepileLayoutExample : KComponent() {
  override fun ComponentScope.render(): Component = FacepileLayout(
      avatars =
          MEMBERS.mapIndexed { index, initials ->
            key(initials) {
              Avatar(
                  initials,
                  CustomLayoutTheme.AvatarColors[index % CustomLayoutTheme.AvatarColors.size],
              )
            }
          },
      avatarSize = CustomLayoutTheme.AvatarSize,
  ) { hiddenCount ->
    Avatar(
        "+$hiddenCount",
        CustomLayoutTheme.TextSecondary,
        textSize = CustomLayoutTheme.MetaSize,
        style =
            Style.contentDescription(
                quantityStringRes(R.plurals.custom_layout_more_items, hiddenCount, hiddenCount),
            ),
    )
  }
}

private val MEMBERS: List<String> =
    listOf("AL", "BK", "CM", "DN", "EO", "FP", "GQ", "HR", "IS", "JT", "KU", "LV")
